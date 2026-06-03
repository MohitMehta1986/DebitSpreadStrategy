package debitspread;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Quote;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BacktestDataFetcher
 *
 * Fetches everything needed to replay two weeks of NIFTY debit spread signals:
 *
 *   1. NIFTY spot 3-min candles  → via kite.getHistoricalData() on NIFTY 50 index
 *   2. India VIX 3-min candles   → via kite.getHistoricalData() on INDIA VIX
 *   3. Option chain snapshots    → one REST quote batch per backtest candle
 *      (Only CE/PE quotes at ATM ± 5 strikes for the nearest weekly expiry)
 *
 * Usage:
 *   KiteConnect kite = new KiteConnect("your_api_key");
 *   kite.setAccessToken("your_access_token");
 *   BacktestDataFetcher fetcher = new BacktestDataFetcher(kite);
 *   fetcher.initialize();                  // loads NFO instrument dump
 *   List<BacktestCandle> candles = fetcher.fetchTwoWeeks();
 *
 * Access token:
 *   Set env var KITE_ACCESS_TOKEN (generated daily via Kite login flow).
 *   API key is set via KITE_API_KEY.
 *   Example:
 *     export KITE_API_KEY=xxxxxxxxxxxxxxxx
 *     export KITE_ACCESS_TOKEN=yyyyyyyyyyyyyyyy
 */
public class BacktestDataFetcher {

    // ── Kite instrument tokens (fixed — rarely change) ────────────────────────
    // NIFTY 50 index token on NSE: 256265
    // INDIA VIX token on NSE:      264969
    private static final long   NIFTY_INDEX_TOKEN = 256265L;
    private static final long   VIX_INDEX_TOKEN   = 264969L;
    private static final String NFO_EXCHANGE       = "NFO";
    private static final String NIFTY_UNDERLYING   = "NIFTY";
    private static final int    STRIKE_STEP        = 50;
    private static final int    CHAIN_DEPTH        = 5;   // ATM ± 5 strikes
    private static final String CANDLE_INTERVAL    = "5minute";

    private final KiteConnect       kite;
    private List<Instrument>        nfoInstruments = new ArrayList<>();

    private static final DateTimeFormatter KITE_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter KITE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public BacktestDataFetcher(KiteConnect kite) {
        this.kite = kite;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Init — load NFO instrument dump once
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Call once before fetchTwoWeeks().
     * Downloads the full NFO instrument CSV (~5 MB) and filters to NIFTY options.
     */
    public void initialize() throws KiteException, IOException {
        Logger.info("[BACKTEST] Loading NFO instrument dump...");
        List<Instrument> all = kite.getInstruments(NFO_EXCHANGE);
        nfoInstruments = all.stream()
                .filter(i -> i.tradingsymbol != null && i.tradingsymbol.startsWith(NIFTY_UNDERLYING))
                .filter(i -> "CE".equals(i.instrument_type) || "PE".equals(i.instrument_type))
                .collect(Collectors.toList());
        Logger.info("[BACKTEST] Loaded " + nfoInstruments.size() + " NIFTY option instruments.");

        // Log distinct expiry dates so we can verify format matches getNearestWeeklyExpiry()
        nfoInstruments.stream()
                .filter(i -> i.expiry != null)
                .map(i -> LocalDate.ofInstant(i.expiry.toInstant(), ZoneId.of("Asia/Kolkata")))
                .distinct()
                .sorted()
                .limit(10)
                .forEach(d -> Logger.info("[BACKTEST] Available expiry: " + d +
                        " (" + d.getDayOfWeek() + ") = " +
                        d.format(DateTimeFormatter.ofPattern("ddMMMyy", java.util.Locale.ENGLISH)).toUpperCase()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Main fetch: last two weeks of market days
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns a time-ordered list of BacktestCandles covering the last two weeks
     * of trading sessions (Mon–Fri, 09:15–15:30 IST).
     *
     * Each candle contains:
     *   - OHLCV for NIFTY spot
     *   - VIX value (3-min, interpolated to candle time)
     *   - Live option chain snapshot at that candle's close time
     *
     * Rate limit note:
     *   Kite allows ~3 historical data requests/second and ~1 request/second for quotes.
     *   For 2 weeks × ~75 candles/day × option quotes, this fetcher batches quotes
     *   per unique option symbol and rate-limits to 1 quote fetch per candle
     *   (covering the ATM chain only — not the full 21-strike chain).
     */
    public List<BacktestCandle> fetchTwoWeeks() throws KiteException, IOException {
        LocalDate today    = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate fromDate = today.minusWeeks(2);

        // Only market days (Mon–Fri); skip weekends
        List<LocalDate> tradingDays = new ArrayList<>();
        LocalDate d = fromDate;
        while (!d.isAfter(today)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                tradingDays.add(d);
            }
            d = d.plusDays(1);
        }

        Logger.info(String.format("[BACKTEST] Fetching data for %d trading days (%s to %s)",
                tradingDays.size(), fromDate, today));

        // ── Step 1: Fetch NIFTY spot candles for full range ───────────────────
        List<HistoricalData> niftyCandles = fetchHistoricalData(
                NIFTY_INDEX_TOKEN, fromDate, today, CANDLE_INTERVAL);
        Logger.info("[BACKTEST] NIFTY candles: " + niftyCandles.size());

        // ── Step 2: Fetch VIX candles for full range ──────────────────────────
        List<HistoricalData> vixCandles = fetchHistoricalData(
                VIX_INDEX_TOKEN, fromDate, today, CANDLE_INTERVAL);
        Logger.info("[BACKTEST] VIX candles: " + vixCandles.size());

        // Build a VIX lookup map: candle-time (truncated to minute) → vix close
        Map<LocalDateTime, Double> vixByTime = new LinkedHashMap<>();
        for (HistoricalData v : vixCandles) {
            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

            LocalDateTime t = OffsetDateTime
                    .parse(v.timeStamp, formatter)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.MINUTES);
           // LocalDateTime t = toLocalDateTime(v.timeStamp).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            vixByTime.put(t, v.close);
        }

        // ── Step 3: Filter to market hours only, attach VIX ──────────────────
        LocalTime marketOpen  = LocalTime.of(9, 15);
        LocalTime marketClose = LocalTime.of(15, 30);

        List<BacktestCandle> result = new ArrayList<>();
        for (HistoricalData hd : niftyCandles) {
            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");

            LocalDateTime cdt = OffsetDateTime
                    .parse(hd.timeStamp, formatter)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.MINUTES);
            //LocalDateTime cdt = toLocalDateTime(hd.timeStamp);
            LocalTime     ct  = cdt.toLocalTime();

            if (ct.isBefore(marketOpen) || ct.isAfter(marketClose)) continue;

            LocalDateTime key = cdt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            double vix = vixByTime.getOrDefault(key, 15.0); // 15 = neutral fallback

            BacktestCandle candle = new BacktestCandle(
                    cdt,
                    hd.open, hd.high, hd.low, hd.close, hd.volume,
                    vix
            );
            result.add(candle);
        }

        Logger.info("[BACKTEST] Market-hours candles: " + result.size());

        // ── Step 4: Attach option chain snapshots (one batch per candle) ──────
        attachOptionChains(result);

        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Option chain attachment
    // ════════════════════════════════════════════════════════════════════════

    /**
     * For each candle, fetches a live quote batch for ATM ± CHAIN_DEPTH strikes.
     *
     * To avoid hitting Kite rate limits (1 req/s for quotes), this fetches
     * quotes ONCE per unique (date, atm) combination rather than per candle —
     * ATM shifts only when NIFTY moves by ≥ 50 pts.  Within that ATM bucket,
     * LTPs are interpolated by scaling the end-of-day option prices proportionally
     * to the spot move. This is accurate enough for backtesting entry/exit logic.
     *
     * For higher-fidelity testing, set FETCH_EACH_CANDLE = true (much slower).
     */
    private static final boolean FETCH_EACH_CANDLE = false;

    private void attachOptionChains(List<BacktestCandle> candles)
            throws KiteException, IOException {

        // Group candles by (date, atm) so we batch quote fetches
        // ATM = round(close / 50) * 50
        Map<String, List<BacktestCandle>> groups = new LinkedHashMap<>();
        for (BacktestCandle c : candles) {
            double atm = computeAtm(c.niftyClose);
            String key = c.time.toLocalDate() + "_" + (long) atm;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }

        int groupIdx = 0;
        for (Map.Entry<String, List<BacktestCandle>> entry : groups.entrySet()) {
            groupIdx++;
            List<BacktestCandle> group = entry.getValue();
            BacktestCandle representative = group.get(group.size() / 2); // mid-candle
            LocalDate date = representative.time.toLocalDate();
            double atm     = computeAtm(representative.niftyClose);
            double spot    = representative.niftyClose;

            Logger.info(String.format("[BACKTEST] Fetching option chain %d/%d | %s | ATM=%.0f",
                    groupIdx, groups.size(), date, atm));

            String expiry = getNearestWeeklyExpiry(date);
            List<OptionContract> chain = fetchOptionChainForDate(spot, atm, expiry, date);

            if (chain.isEmpty()) {
                Logger.warn("[BACKTEST] Empty chain for " + date + " ATM=" + atm + " — skipping group.");
                continue;
            }

            for (BacktestCandle c : group) {
                // Scale chain LTPs to this candle's spot (proportional approximation)
                double spotRatio = c.niftyClose / spot;
                List<OptionContract> scaled = scaledChain(chain, spotRatio, c.niftyClose, expiry);
                c.optionChain = scaled;
                c.atm         = atm;
                c.expiry      = expiry;
            }

            // Rate limit: 1 req/sec for quotes
            sleep(1100);
        }

        // Drop candles with no chain (gaps in data)
        candles.removeIf(c -> c.optionChain == null || c.optionChain.isEmpty());
        Logger.info("[BACKTEST] Candles with valid option chain: " + candles.size());
    }

    private List<OptionContract> fetchOptionChainForDate(double spot, double atm,
                                                         String expiry, LocalDate date) throws KiteException, IOException {

        LocalDate expiryDate = ZerodhaMarketDataService.parseExpiry(expiry);

        // Find matching instruments
        List<Instrument> instruments = nfoInstruments.stream()
                .filter(i -> {
                    if (i.expiry == null) return false;
                    LocalDate iExpiry = LocalDate.ofInstant(
                            i.expiry.toInstant(), ZoneId.of("Asia/Kolkata")); // always IST, not Windows local TZ
                    if (!iExpiry.equals(expiryDate)) return false;
                    double strike = Double.parseDouble(i.strike.toString());
                    double diff   = Math.abs(strike - atm);
                    return diff <= CHAIN_DEPTH * STRIKE_STEP;
                })
                .collect(Collectors.toList());

        if (instruments.isEmpty()) {
            Logger.warn("[BACKTEST] No instruments found for expiry=" + expiry + " ATM=" + atm);
            return Collections.emptyList();
        }

        // Batch quote fetch
        String[] symbols = instruments.stream()
                .map(i -> NFO_EXCHANGE + ":" + i.tradingsymbol)
                .toArray(String[]::new);

        Map<String, Quote> quotes;
        try {
            quotes = kite.getQuote(symbols);
        } catch (Exception e) {
            Logger.warn("[BACKTEST] Quote fetch failed for " + date + ": " + e.getMessage());
            return Collections.emptyList();
        }

        double T = Math.max(
                java.time.temporal.ChronoUnit.DAYS.between(date, expiryDate), 0.5) / 365.0;
        double r = 0.065;

        List<OptionContract> chain = new ArrayList<>();
        for (Instrument inst : instruments) {
            String kiteKey = NFO_EXCHANGE + ":" + inst.tradingsymbol;
            Quote  q       = quotes.get(kiteKey);
            if (q == null || q.lastPrice <= 0) continue;

            boolean isCall = "CE".equals(inst.instrument_type);
            double  strike = Double.parseDouble(inst.strike.toString());
            double  ltp    = q.lastPrice;
            double  iv     = estimateIv(ltp, spot, strike, r, T, isCall);
            double  delta  = BlackScholes.compute(spot, strike, r, iv, T, isCall).delta;

            OptionContract.OptionType type =
                    isCall ? OptionContract.OptionType.CALL : OptionContract.OptionType.PUT;
            OptionContract oc = new OptionContract(
                    inst.tradingsymbol, strike, type, expiry, ltp, delta, iv);

            BlackScholes.Greeks g = BlackScholes.compute(spot, strike, r, iv, T, isCall);
            oc.setTheta(g.theta);
            oc.setGamma(g.gamma);
            oc.setVega(g.vega);
            chain.add(oc);
        }

        return chain;
    }

    /**
     * Scale option LTPs proportionally when spot has moved since the batch fetch.
     * Uses intrinsic + extrinsic split:  new_ltp ≈ max(intrinsic(new_spot), old_ltp * ratio)
     * Good enough for testing entry/exit signals; not a substitute for live quotes.
     */
    private List<OptionContract> scaledChain(List<OptionContract> base,
                                             double spotRatio, double newSpot, String expiry) {
        List<OptionContract> scaled = new ArrayList<>();
        for (OptionContract oc : base) {
            double newLtp;
            double strike = oc.getStrikePrice();
            if (oc.getOptionType() == OptionContract.OptionType.CALL) {
                double intrinsic = Math.max(newSpot - strike, 0);
                double extrinsic = Math.max(oc.getLtp() - Math.max(oc.getLtp() - strike, 0), 0.5);
                newLtp = Math.max(intrinsic + extrinsic * spotRatio, 0.05);
            } else {
                double intrinsic = Math.max(strike - newSpot, 0);
                double extrinsic = Math.max(oc.getLtp() - Math.max(strike - oc.getLtp(), 0), 0.5);
                newLtp = Math.max(intrinsic + extrinsic * (2.0 - spotRatio), 0.05);
            }
            OptionContract sc = new OptionContract(
                    oc.getSymbol(), strike, oc.getOptionType(), expiry, newLtp, oc.getDelta(), oc.getIv());
            sc.setTheta(oc.getTheta());
            sc.setGamma(oc.getGamma());
            sc.setVega(oc.getVega());
            scaled.add(sc);
        }
        return scaled;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Kite API helpers
    // ════════════════════════════════════════════════════════════════════════

    private List<HistoricalData> fetchHistoricalData(long instrumentToken,
                                                     LocalDate from, LocalDate to, String interval) throws KiteException, IOException {
        // Kite getHistoricalData params: token, from (Date), to (Date), interval, continuous, oi
        java.util.Date fromDate = toDate(from.atStartOfDay());
        java.util.Date toDate   = toDate(to.atTime(23, 59));

        HistoricalData hd = kite.getHistoricalData(
                fromDate, toDate, String.valueOf(instrumentToken), interval, false, false);

        return hd != null && hd.dataArrayList != null
                ? hd.dataArrayList
                : Collections.emptyList();
    }

    private static java.util.Date toDate(LocalDateTime ldt) {
        return java.util.Date.from(
                ldt.atZone(ZoneId.of("Asia/Kolkata")).toInstant());
    }

    private static LocalDateTime toLocalDateTime(java.util.Date d) {
        return d.toInstant().atZone(ZoneId.of("Asia/Kolkata")).toLocalDateTime();
    }

    private double computeAtm(double spot) {
        return Math.round(spot / STRIKE_STEP) * STRIKE_STEP;
    }

    /**
     * Nearest TUESDAY expiry from a given date (NIFTY weekly options expire on Tuesday).
     * If today IS Tuesday (expiry day), advance to next Tuesday so we fetch
     * next week's chain — we don't trade on expiry day.
     */
    private String getNearestWeeklyExpiry(LocalDate from) {
        LocalDate d = from;
        // If it's already Tuesday, step forward to avoid matching today's expiring chain
        if (d.getDayOfWeek() == DayOfWeek.TUESDAY) {
            d = d.plusDays(1);
        }
        while (d.getDayOfWeek() != DayOfWeek.TUESDAY) {
            d = d.plusDays(1);
        }
        return d.format(DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH)).toUpperCase();
    }

    private double estimateIv(double price, double spot, double strike,
                              double r, double T, boolean isCall) {
        if (price < 0.5 || T <= 0) return 0.15;
        double iv = 0.15;
        for (int i = 0; i < 10; i++) {
            BlackScholes.Greeks g = BlackScholes.compute(spot, strike, r, iv, T, isCall);
            double diff = g.price - price;
            double vega = g.vega * 100;
            if (Math.abs(vega) < 1e-6) break;
            iv -= diff / vega;
            if (iv <= 0.01) { iv = 0.01; break; }
            if (iv > 5.0)   { iv = 0.15; break; }
        }
        return iv;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Static factory — builds KiteConnect from env vars
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Convenience factory.
     * Reads KITE_API_KEY and KITE_ACCESS_TOKEN from environment variables.
     *
     * Usage:
     *   BacktestDataFetcher fetcher = BacktestDataFetcher.fromEnv();
     *   fetcher.initialize();
     *   List<BacktestCandle> candles = fetcher.fetchTwoWeeks();
     */
    public static BacktestDataFetcher fromEnv() {
        String apiKey      = "zyppp731lbi6co2g";
        String accessToken = "GXnr0sURYqDlY5HeTyhROIWEWyWaaUHg";

        KiteConnect kite = new KiteConnect(apiKey);
        kite.setAccessToken(accessToken);
        Logger.info("[BACKTEST] KiteConnect initialised. API key ends in: ..."
                + apiKey.substring(Math.max(0, apiKey.length() - 4)));

        return new BacktestDataFetcher(kite);
    }

    private static String requireEnv(String name) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException(
                    "Missing environment variable: " + name + "\n" +
                            "Set it before running:  export " + name + "=<value>");
        }
        return val;
    }
}