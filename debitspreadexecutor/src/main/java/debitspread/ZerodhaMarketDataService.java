package debitspread;


import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.Quote;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ZerodhaMarketDataService
 *
 * Fetches live market data via Zerodha Kite Connect REST API:
 *   • NIFTY 50 spot price   → NSE:NIFTY 50
 *   • India VIX             → NSE:INDIA VIX
 *   • Option chain (NFO)    → instrument dump + live quotes
 *   • Greeks                → from live Quote (delta, theta, gamma, vega, IV)
 *
 * WebSocket ticker (KiteTicker) is also wired for low-latency LTP streaming
 * during active position monitoring.
 *
 * Prerequisites:
 *   1. Zerodha Kite Connect account + API key
 *   2. Complete the login flow to get a valid access_token each day
 *   3. Set KITE_API_KEY and KITE_ACCESS_TOKEN as env variables (or via KiteConfig)
 */
public class ZerodhaMarketDataService {

    // ── Zerodha instrument tokens (static, rarely change) ─────────────────────
    private static final String NIFTY_SPOT_SYMBOL = "NSE:NIFTY 50";
    private static final String INDIA_VIX_SYMBOL  = "NSE:INDIA VIX";
    private static final String NFO_EXCHANGE       = "NFO";
    private static final String NIFTY_UNDERLYING   = "NIFTY";
    private static final int    NIFTY_STRIKE_STEP  = 50;

    private final TradeConfig  config;
    private final KiteConnect  kite;

    /** Live LTP cache updated by WebSocket ticker */
    private final ConcurrentHashMap<String, Double> ltpCache = new ConcurrentHashMap<>();

    /** NFO instrument list loaded once at startup */
    private List<Instrument> nfoInstruments = new ArrayList<>();

    /** WebSocket ticker for streaming quotes during live position */
    private KiteTickerService tickerService;

    private static final List<DateTimeFormatter> EXPIRY_FORMATS = List.of(
            DateTimeFormatter.ofPattern("ddMMMyy",    Locale.ENGLISH),  // 28APR26  ← NSE symbol suffix
            DateTimeFormatter.ofPattern("ddMMMyyyy",  Locale.ENGLISH),  // 28APR2026
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),  // 2025-04-28 ← instrument CSV
            DateTimeFormatter.ofPattern("dd-MMM-yyyy",Locale.ENGLISH),  // 28-Apr-2025
            DateTimeFormatter.ofPattern("dMMMyy",     Locale.ENGLISH)   // 8APR26 single-digit day
    );
    private static final DateTimeFormatter EXPIRY_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()                    // handles APR / Apr / apr
            .appendPattern("dd")
            .appendPattern("MMM")
            .appendValueReduced(ChronoField.YEAR, 2, 2, LocalDate.now().getYear() - 50)
            // ↑ tells Java: 2-digit year, pivot = current year minus 50
            // so "26" → 2026, "99" → 1999 won't happen for near-future expiries
            .toFormatter(Locale.ENGLISH);
    // ──────────────────────────────────────────────────────────────────────────

    public ZerodhaMarketDataService(TradeConfig config, KiteConnect kite) {
        this.config = config;
        this.kite   = kite;
    }

    /**
     * Call once after login. Loads NFO instrument dump into memory.
     * Kite provides a full instrument CSV (~5MB) that we filter to NIFTY options.
     */
    public void initialize() throws KiteException, IOException {
        Logger.info("Loading NFO instrument dump from Kite...");
        List<Instrument> all = kite.getInstruments(NFO_EXCHANGE);
        nfoInstruments = all.stream()
            .filter(i -> i.tradingsymbol.startsWith(NIFTY_UNDERLYING))
            .filter(i -> i.instrument_type.equals("CE") || i.instrument_type.equals("PE"))
            .collect(Collectors.toList());
        Logger.info("Loaded " + nfoInstruments.size() + " NIFTY option instruments.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API (same contract as original MarketDataService)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetch a complete real-time market snapshot.
     * Calls Kite REST API for spot, VIX, and option chain quotes.
     */
    public MarketSnapshot fetchSnapshot() throws KiteException, IOException {
        double spot     = fetchNiftySpot();
        double vix      = fetchIndiaVix();
        double atm      = computeAtmStrike(spot);
        String expiry   = getNearestWeeklyExpiry();

        List<OptionContract> chain = fetchOptionChain(spot, atm, expiry);

        Logger.info(String.format("📡 Snapshot | NIFTY=%.2f | VIX=%.2f | ATM=%.0f | Chain=%d",
            spot, vix, atm, chain.size()));

        return new MarketSnapshot(LocalDateTime.now(), spot, vix, atm, chain);
    }

    /**
     * Fetch live LTP for a single option contract.
     * Checks WebSocket cache first; falls back to REST quote.
     */
    public double fetchLtp(OptionContract contract) throws KiteException, IOException {
        // Try WebSocket cache first (lowest latency)
//        Double cached = ltpCache.get(contract.getSymbol());
//        if (cached != null) {
//            return cached;
//        }
        // Fall back to REST LTP call
        return fetchLtpViaRest(contract.getSymbol());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Spot & VIX
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetches NIFTY 50 spot price via Kite getLTP.
     */
    private double fetchNiftySpot() throws KiteException, IOException {
        Map<String, LTPQuote> ltp = kite.getLTP(new String[]{NIFTY_SPOT_SYMBOL});
        LTPQuote quote = ltp.get(NIFTY_SPOT_SYMBOL);
        if (quote == null) {
            throw new RuntimeException("NIFTY spot LTP not returned by Kite API");
        }
        Logger.debug("NIFTY Spot LTP: " + quote.lastPrice);
        return quote.lastPrice;
    }

    /**
     * Fetches India VIX via Kite getLTP.
     */
    private double fetchIndiaVix() throws KiteException, IOException {
        Map<String, LTPQuote> ltp = kite.getLTP(new String[]{INDIA_VIX_SYMBOL});
        LTPQuote quote = ltp.get(INDIA_VIX_SYMBOL);
        if (quote == null) {
            throw new RuntimeException("India VIX LTP not returned by Kite API");
        }
        Logger.debug("India VIX: " + quote.lastPrice);
        return quote.lastPrice;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Option Chain
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Builds a live option chain for NIFTY around ATM ± 10 strikes.
     *
     * Steps:
     *  1. Find matching instruments from the pre-loaded NFO dump (by strike + expiry)
     *  2. Batch-fetch live quotes from Kite (supports up to 500 instruments per call)
     *  3. Map Quote → OptionContract (with greeks from Kite if available,
     *     else compute via Black-Scholes)
     */
    private List<OptionContract> fetchOptionChain(double spot, double atm,
                                                   String expiry) throws KiteException, IOException {
        // Step 1: Find the ±10 strikes around ATM
        List<Double> targetStrikes = new ArrayList<>();
        for (int i = -10; i <= 10; i++) {
            targetStrikes.add(atm + (i * NIFTY_STRIKE_STEP));
        }

        // Step 2: Resolve instruments for each strike/expiry/type
        LocalDate expiryDate = parseExpiry(expiry);
        List<Instrument> selectedInstruments = nfoInstruments.stream()
            .filter(inst -> {
                double strike = Double.valueOf(inst.strike);
                return targetStrikes.contains(strike)
                    && inst.expiry != null
                    && LocalDate.ofInstant(inst.expiry.toInstant(), ZoneId.systemDefault()).equals(expiryDate);
            })
            .collect(Collectors.toList());

        //TODO: come back
        if (selectedInstruments.isEmpty()) {
            Logger.warn("No instruments found for expiry=" + expiry + ". Falling back to BS simulation.");
            //return buildOptionChainFallback(spot, atm, expiry);
            throw new IOException("Instruments not found");
        }

        // Step 3: Build Kite symbols (e.g. "NFO:NIFTY24APR22500CE")
        String[] kiteSymbols = selectedInstruments.stream()
            .map(i -> NFO_EXCHANGE + ":" + i.tradingsymbol)
            .toArray(String[]::new);

        // Step 4: Fetch full quotes (includes greeks if enabled on your Kite plan)
        Map<String, Quote> quotes = kite.getQuote(kiteSymbols);

        // Step 5: Map to OptionContract
        List<OptionContract> chain = new ArrayList<>();
        double sigma = 0.15; // default IV if not in quote
        double r     = 0.065;
        double T     = daysToExpiry(expiryDate) / 365.0;

        for (Instrument inst : selectedInstruments) {
            String kiteKey  = NFO_EXCHANGE + ":" + inst.tradingsymbol;
            Quote  quote    = quotes.get(kiteKey);
            if (quote == null) continue;

            double ltp      = quote.lastPrice;
            double iv       = quote.oi > 0 ? estimateIvFromQuote(quote, spot, inst, T, r) : sigma;
            double delta    = computeDelta(spot, Double.valueOf(inst.strike), r, iv, T,
                                           inst.instrument_type.equals("CE"));

            OptionContract.OptionType type = inst.instrument_type.equals("CE") ? OptionContract.OptionType.CALL : OptionContract.OptionType.PUT;

            OptionContract oc = new OptionContract(
                inst.tradingsymbol, Double.valueOf(inst.strike), type, expiry, ltp, delta, iv
            );

            //TODO: check if calculation required otherwise remove it get it from kite
            // Store extra greeks
            BlackScholes.Greeks g = BlackScholes.compute(spot, Double.valueOf(inst.strike), r, iv, T,
                type == OptionContract.OptionType.CALL);
            oc.setTheta(g.theta);
            oc.setGamma(g.gamma);
            oc.setVega(g.vega);

            chain.add(oc);

            // Seed LTP cache for WebSocket fallback
            ltpCache.put(inst.tradingsymbol, ltp);
        }

        Logger.info("Option chain loaded: " + chain.size() + " contracts.");
        return chain;
    }

    // ════════════════════════════════════════════════════════════════════════
    // WebSocket Ticker (for live position monitoring)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Start streaming LTP updates via Kite WebSocket for specific contracts.
     * Call this after entering a position for low-latency P&L updates.
     *
     * @param contracts List of option contracts to stream
     */
    public void startTickerFor(List<OptionContract> contracts) {
        List<Long> tokens = nfoInstruments.stream()
            .filter(i -> contracts.stream()
                .anyMatch(c -> c.getSymbol().equals(i.tradingsymbol)))
            .map(i -> i.instrument_token)
            .collect(Collectors.toList());

        if (tokens.isEmpty()) {
            Logger.warn("No instrument tokens found for ticker — using REST polling.");
            return;
        }

        tickerService = new KiteTickerService(kite, tokens, ltpCache);
        tickerService.connect();
        Logger.info("WebSocket ticker started for " + tokens.size() + " contracts.");
    }

    /**
     * Stop the WebSocket ticker (call on position exit or shutdown).
     */
    public void stopTicker() {
        if (tickerService != null) {
            tickerService.disconnect();
            Logger.info("WebSocket ticker stopped.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private double fetchLtpViaRest(String tradingsymbol) throws KiteException, IOException {
        String kiteSymbol = NFO_EXCHANGE + ":" + tradingsymbol;
        Map<String, LTPQuote> ltp = kite.getLTP(new String[]{kiteSymbol});
        LTPQuote q = ltp.get(kiteSymbol);
        return q != null ? q.lastPrice : 0.0;
    }

    /**
     * ATM = nearest 50-point multiple.
     */
    private double computeAtmStrike(double spot) {
        return Math.round(spot / NIFTY_STRIKE_STEP) * NIFTY_STRIKE_STEP;
    }

    /**
     * Derives the nearest Thursday expiry for NIFTY weekly options.
     * NIFTY weekly options expire every Thursday.
     */
    private String getNearestWeeklyExpiry() {
        LocalDate today    = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate tuesday = today;
        // Advance to nearest future Thursday
        while (tuesday.getDayOfWeek() != DayOfWeek.TUESDAY
               || tuesday.isBefore(today)) {
            tuesday = tuesday.plusDays(1);
        }
        // Format as Kite expects: e.g. "24APR25" (DDMMMYY)
        return tuesday.format(DateTimeFormatter.ofPattern("ddMMMyy")).toUpperCase();
    }

    /**
     * Parse expiry string back to LocalDate for instrument matching.
     * Handles formats like "25APR24" (Kite instrument dump uses java.util.Date).
     */
    public static LocalDate parseExpiry(String raw) {
        return LocalDate.parse(raw.trim().toUpperCase(), EXPIRY_FORMATTER);
    }

    private double daysToExpiry(LocalDate expiry) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(
            LocalDate.now(ZoneId.of("Asia/Kolkata")), expiry);
        return Math.max(days, 0.5); // at least half a day
    }

    /**
     * Estimate IV from the quote using inverse Black-Scholes (Newton–Raphson).
     * Falls back to 15% if the market price is negligible or calculation diverges.
     */
    private double estimateIvFromQuote(Quote quote, double spot, Instrument inst,
                                        double T, double r) {
        double marketPrice = quote.lastPrice;
        if (marketPrice < 0.5 || T <= 0) return 0.15;

        boolean isCall = inst.instrument_type.equals("CE");
        double  iv     = 0.15; // initial guess

        // Newton–Raphson: 10 iterations typically sufficient
        for (int i = 0; i < 10; i++) {
            BlackScholes.Greeks g = BlackScholes.compute(spot, Double.valueOf(inst.strike), r, iv, T, isCall);
            double diff  = g.price - marketPrice;
            double vega  = g.vega * 100; // vega per 1% IV → per unit
            if (Math.abs(vega) < 1e-6) break;
            iv -= diff / vega;
            if (iv <= 0.01) { iv = 0.01; break; }
            if (iv > 5.0)   { iv = 0.15; break; }
        }
        return iv;
    }

    private double computeDelta(double spot, double strike, double r,
                                 double iv, double T, boolean isCall) {
        return BlackScholes.compute(spot, strike, r, iv, T, isCall).delta;
    }

    /**
     * Fallback: build option chain from Black-Scholes when Kite returns no instruments.
     * Typically only happens outside market hours or for non-existent expiries.
     */
    private List<OptionContract> buildOptionChainFallback(double spot, double atm,
                                                           String expiry) {
        Logger.warn("Using Black-Scholes fallback for option chain (no live data).");
        List<OptionContract> chain = new ArrayList<>();
        double sigma = 0.15;
        double r     = 0.065;
        double T     = 7.0 / 365.0;

        for (int i = -10; i <= 10; i++) {
            double strike = atm + (i * NIFTY_STRIKE_STEP);

            BlackScholes.Greeks cg = BlackScholes.compute(spot, strike, r, sigma, T, true);
            chain.add(new OptionContract(
                "NIFTY" + expiry + (int) strike + "CE", strike,
                OptionContract.OptionType.CALL, expiry, Math.max(0.5, cg.price), cg.delta, sigma));

            BlackScholes.Greeks pg = BlackScholes.compute(spot, strike, r, sigma, T, false);
            chain.add(new OptionContract(
                "NIFTY" + expiry + (int) strike + "PE", strike,
                OptionContract.OptionType.PUT,  expiry, Math.max(0.5, pg.price), pg.delta, sigma));
        }
        return chain;
    }
}
