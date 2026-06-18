package debitspread;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * TickReplayer
 *
 * Reads the CSV files written by TickRecorder and replays the full trading day
 * through DebitSpreadEngine without hitting the Kite API.
 *
 * ── How it works ──────────────────────────────────────────────────────────
 *
 *   1. Reads ticks_YYYYMMDD.csv  → timestamp, indicators, verdict per tick
 *   2. Reads chain_YYYYMMDD.csv  → option chain snapshot per tick
 *   3. Joins them on timestamp
 *   4. Rebuilds the TrendConfirmationDetector's rolling candle window from
 *      recorded indicator values (no recomputation needed)
 *   5. Calls spreadEngine.onTick() for each tick exactly as ZerodhaTradeEngine
 *      would in live mode
 *   6. Prints a full trade log + session P&L summary
 *
 * ── Usage ─────────────────────────────────────────────────────────────────
 *
 *   // Replay today:
 *   TickReplayer.replay(config);
 *
 *   // Replay a specific date:
 *   TickReplayer.replay(config, "20260601");
 *
 *   // Or run directly:
 *   export TICK_RECORD_DIR=C:/myprojects/.../ticks
 *   // Call TickReplayer.main() or TickReplayer.replay(config)
 *
 * ── What gets replayed ─────────────────────────────────────────────────────
 *
 *   - Every entry/exit decision with the exact same market data seen live
 *   - P&L per trade and session total
 *   - All gate logs (ADX, BBW, slope, confidence, breakout confirmation)
 *   - Any difference from live = a bug in the engine (replay is deterministic)
 */
public class TickReplayer {

    private static final DateTimeFormatter DT_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String dir;
    private final String dateStr;

    public TickReplayer(String tickDir, String dateStr) {
        this.dir     = tickDir;
        this.dateStr = dateStr;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Static entry points
    // ════════════════════════════════════════════════════════════════════════

    /** Replay today's recorded ticks. */
    public static void replay(TradeConfig config) throws IOException, KiteException {
        String today = LocalDate.now().minusDays(0).format(DATE_FMT);
        new TickReplayer(resolveDir(), today).run(config);
    }

    /** Replay a specific date (format: YYYYMMDD). */
    public static void replay(TradeConfig config, String dateStr) throws IOException, KiteException {
        new TickReplayer(resolveDir(), dateStr).run(config);
    }

    /** main() for direct execution */
    public static void main(String[] args) throws Exception, KiteException {
        TradeConfig config = TradeConfig.defaultConfig();
        String date = args.length > 0 ? args[0] : LocalDate.now().minusDays(2).format(DATE_FMT);
        new TickReplayer(resolveDir(), date).run(config);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Core replay
    // ════════════════════════════════════════════════════════════════════════

    public void run(TradeConfig config) throws IOException, KiteException {
        Logger.info("====================================================");
        Logger.info("  TICK REPLAYER — " + dateStr);
        Logger.info("====================================================");

        // ── Load tick rows ────────────────────────────────────────────────────
        List<TickRow> ticks = loadTicks();
        if (ticks.isEmpty()) {
            Logger.warn("[Replayer] No ticks found for " + dateStr + " in " + dir);
            return;
        }
        Logger.info(String.format("[Replayer] Loaded %d ticks.", ticks.size()));

        // ── Load chain rows grouped by timestamp ──────────────────────────────
        Map<String, List<ChainRow>> chainByTs = loadChain();
        Logger.info(String.format("[Replayer] Chain snapshots: %d timestamps.", chainByTs.size()));

        // ── Build engine with replay executor (fills at recorded LTPs) ────────
        ReplayMarketData     marketData = new ReplayMarketData();
        ReplayOrderExecutor  executor   = new ReplayOrderExecutor(config, marketData);
        DebitSpreadEngine    engine     = new DebitSpreadEngine(config, executor, marketData);

        int processed = 0;
        int skipped   = 0;

        // Bug 3 fix: pre-build 5-minute OHLC candles from the full tick list so that
        // buildTrendResult can supply real closes/highs/lows instead of a flat array.
        // A flat array makes EMA slope = 0, candle body = 0, and VWAP-break always false —
        // those are 3 of the 5 breakout-confirmation signals, so the engine almost never
        // reaches the 2/5 threshold required to re-enter after the first trade.
        List<double[]> ohlcCandles = buildOhlcCandles(ticks); // each double[4]: open,high,low,close

        for (TickRow tick : ticks) {
            List<ChainRow> chain = chainByTs.getOrDefault(tick.timestamp, Collections.emptyList());

            if (chain.isEmpty()) {
                skipped++;
                continue;
            }

            // Build OptionContract list from chain rows
            List<OptionContract> contracts = chain.stream()
                    .map(ChainRow::toOptionContract)
                    .collect(Collectors.toList());

            // Build MarketSnapshot
            MarketSnapshot snapshot = new MarketSnapshot(
                    tick.dateTime,
                    tick.niftySpot,
                    tick.vix,
                    tick.atm,
                    contracts
            );

            // Update marketData so engine's fetchLtp() returns the recorded value
            marketData.setSnapshot(snapshot);

            // Determine which candles are complete up to this tick's time
            // so the engine's indicator recomputation sees real OHLC data.
            //
            // Bug 4 fix: candlesAvailable must be the SLIDING upper bound (only
            // candles completed strictly before this tick), and must NOT be
            // floored at 30 against the full-day candle list. The old
            // `Math.max(candlesAvailable, Math.max(tick.candleCount, 30))`
            // forced windowSize >= 30 from the start of the day, which made
            // buildTrendResult's startIdx = ohlcCandles.size() - n collapse to
            // (almost) the same fixed slice of the LAST ~30 candles of the
            // ENTIRE DAY on every tick — so closes/highs/lows (and therefore
            // BBW) barely changed tick-to-tick (frozen ~0.32%), unlike live
            // where the rolling buffer genuinely slides forward in time.
            //
            // Fix: pass candlesAvailable as-is (the true count of completed
            // candles up to "now"). buildTrendResult handles the < 30 warm-up
            // case itself by padding the front with spot-based placeholders,
            // exactly as the live engine's buffer would look during warm-up.
            LocalTime tickTime = tick.dateTime.toLocalTime();
            int candlesAvailable = (int) ohlcCandles.stream()
                    .filter(c -> c[4] < tickTime.toSecondOfDay()) // c[4] = candle end-epoch seconds
                    .count();

            // Build fake TrendResult from recorded values (no recomputation)
            TrendConfirmationDetector.TrendResult trend =
                    buildTrendResult(tick, contracts, ohlcCandles, candlesAvailable);

            // Skip FLAT ticks (engine would have been skipped in live too)
            if (trend.isFlat()) {
                continue;
            }

            // Call engine exactly as ZerodhaTradeEngine.handleScanning/handleInTrade does
            try {
                engine.onTick(
                        snapshot,
                        trend.closes, trend.highs, trend.lows,
                        trend.percentB, trend.adx,
                        tick.dateTime.toLocalTime()
                );
            } catch (Exception e) {
                Logger.warn("[Replayer] onTick error at " + tick.timestamp + ": " + e.getMessage());
            }

            processed++;
        }

        // ── Print summary ─────────────────────────────────────────────────────
        printSummary(engine, processed, skipped, ticks.size());
    }

    // ════════════════════════════════════════════════════════════════════════
    // CSV loaders
    // ════════════════════════════════════════════════════════════════════════

    private List<TickRow> loadTicks() throws IOException {
        Path path = Paths.get(dir, "ticks_" + dateStr + ".csv");
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Tick file not found: " + path);
        }

        List<TickRow> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; } // skip header
                if (line.isBlank()) continue;
                try {
                    rows.add(TickRow.parse(line));
                } catch (Exception e) {
                    Logger.warn("[Replayer] Bad tick row: " + line + " → " + e.getMessage());
                }
            }
        }
        return rows;
    }

    private Map<String, List<ChainRow>> loadChain() throws IOException {
        Path path = Paths.get(dir, "chain_" + dateStr + ".csv");
        if (!Files.exists(path)) {
            Logger.warn("[Replayer] Chain file not found: " + path + " — options will be empty.");
            return Collections.emptyMap();
        }

        Map<String, List<ChainRow>> map = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                try {
                    ChainRow row = ChainRow.parse(line);
                    map.computeIfAbsent(row.timestamp, k -> new ArrayList<>()).add(row);
                } catch (Exception e) {
                    Logger.warn("[Replayer] Bad chain row: " + e.getMessage());
                }
            }
        }
        return map;
    }

    // ════════════════════════════════════════════════════════════════════════
    // TrendResult builder from recorded indicator values
    // Bug 3 fix: uses real OHLC candle window so the engine's internal indicator
    // recomputation (EMA slope, candle body ratio, VWAP-break) produces live-accurate
    // values instead of zeros from a flat array.
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Builds 5-minute OHLC candles from the recorded tick stream.
     * Returns a list where each element is double[5]: {open, high, low, close, endEpochSec}
     * endEpochSec is the candle's close time in seconds-of-day, used to determine
     * how many candles are complete at a given tick.
     */
    private List<double[]> buildOhlcCandles(List<TickRow> ticks) {
        List<double[]> candles = new ArrayList<>();
        if (ticks.isEmpty()) return candles;

        final int CANDLE_MINUTES = 5;
        double open = 0, high = 0, low = Double.MAX_VALUE, close = 0;
        int currentBucket = -1;

        for (TickRow tick : ticks) {
            LocalTime t  = tick.dateTime.toLocalTime();
            // minutes since midnight, rounded down to 5-min bucket
            int bucket = (t.toSecondOfDay() / 60) / CANDLE_MINUTES;

            if (currentBucket < 0) {
                currentBucket = bucket;
                open = high = low = close = tick.niftySpot;
            } else if (bucket != currentBucket) {
                // close out previous candle
                int endSec = (currentBucket + 1) * CANDLE_MINUTES * 60;
                candles.add(new double[]{open, high, low, close, endSec});
                // start new candle
                currentBucket = bucket;
                open = high = low = close = tick.niftySpot;
            }

            // update running OHLC
            if (tick.niftySpot > high) high = tick.niftySpot;
            if (tick.niftySpot < low)  low  = tick.niftySpot;
            close = tick.niftySpot;
        }
        // trailing partial candle (not added — it's incomplete)
        Logger.info(String.format("[Replayer] Built %d 5-min OHLC candles from %d ticks.",
                candles.size(), ticks.size()));
        return candles;
    }

    private TrendConfirmationDetector.TrendResult buildTrendResult(
            TickRow tick, List<OptionContract> contracts,
            List<double[]> ohlcCandles, int windowSize) {

        // Use real candle OHLC data for the closes/highs/lows arrays.
        //
        // windowSize = number of candles completed strictly before this tick
        // (the true "now" boundary — see Bug 4 fix in run()).
        //
        // Bug 4 fix (cont'd): the available candle set is ohlcCandles[0:available],
        // NOT ohlcCandles[ohlcCandles.size()-n : ohlcCandles.size()] — the old code
        // sliced from the END OF THE FULL-DAY LIST regardless of windowSize, so
        // early-session ticks "saw" late-session candles and the window barely
        // moved across the whole replay (BBW frozen ~0.32%).
        //
        // The engine needs >= 30 candles for most indicator paths, so we still
        // produce an array of at least 30, but pad the FRONT with synthetic
        // placeholders during warm-up (mirroring live, where the rolling buffer
        // is genuinely short early in the day) rather than borrowing real
        // candles from later in the session.
        int available = Math.min(windowSize, ohlcCandles.size());
        int n = Math.max(available, 30);

        double[] closes = new double[n];
        double[] highs  = new double[n];
        double[] lows   = new double[n];

        int pad = n - available; // number of synthetic warm-up candles at the front

        // Front-pad with spot-based placeholders (warm-up guard)
        for (int i = 0; i < pad; i++) {
            closes[i] = tick.niftySpot;
            highs[i]  = tick.niftySpot + 5;
            lows[i]   = tick.niftySpot - 5;
        }

        // Fill the remaining slots with the 'available' most-recent completed
        // candles, in chronological order: ohlcCandles[0 : available]
        for (int i = 0; i < available; i++) {
            double[] c = ohlcCandles.get(i);
            closes[pad + i] = c[3]; // close
            highs[pad + i]  = c[1]; // high
            lows[pad + i]   = c[2]; // low
        }

        // Use the recorded verdict to determine TRENDING vs FLAT
        TrendConfirmationDetector.Verdict verdict;
        try {
            verdict = TrendConfirmationDetector.Verdict.valueOf(tick.verdict);
        } catch (Exception e) {
            verdict = TrendConfirmationDetector.Verdict.FLAT;
        }

        return new TrendConfirmationDetector.TrendResult(
                verdict,
                tick.adx,
                tick.bbw,
                tick.percentB,
                tick.emaSlope,
                closes, highs, lows,
                tick.niftySpot,
                tick.candleCount,
                "REPLAYED"
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    // Summary
    // ════════════════════════════════════════════════════════════════════════

    private void printSummary(DebitSpreadEngine engine, int processed, int skipped, int total) {
        Logger.info("\n====================================================");
        Logger.info("  REPLAY COMPLETE — " + dateStr);
        Logger.info("====================================================");
        Logger.info(String.format("  Ticks total   : %d", total));
        Logger.info(String.format("  Ticks replayed: %d", processed));
        Logger.info(String.format("  Ticks skipped : %d (no chain data)", skipped));

        List<DebitSpreadPosition> positions = engine.getAllPositions();
        if (positions.isEmpty()) {
            Logger.info("  No trades executed.");
        } else {
            long wins = positions.stream().filter(p -> p.getRealisedPnl() > 0).count();
            Logger.info(String.format("\n  Trades  : %d", positions.size()));
            Logger.info(String.format("  Wins    : %d", wins));
            Logger.info(String.format("  Win %%  : %.0f%%", wins * 100.0 / positions.size()));
            Logger.info(String.format("  Total P&L: \u20b9%.2f", engine.getTotalSessionPnl()));
            Logger.info("");
            for (int i = 0; i < positions.size(); i++) {
                DebitSpreadPosition p = positions.get(i);
                double pnl = p.isExited() ? p.getRealisedPnl() : p.getUnrealisedPnl();
                Logger.info(String.format(
                        "  #%d | %s | Debit=\u20b9%.2f | P&L=\u20b9%.2f | Exit=%-16s | %s",
                        i + 1, p.direction, p.netDebit * p.quantity,
                        pnl, p.getExitReason(),
                        p.isExited() ? "CLOSED" : "OPEN"));
            }
        }
        Logger.info("====================================================");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Data classes
    // ════════════════════════════════════════════════════════════════════════

    static class TickRow {
        String        timestamp;
        LocalDateTime dateTime;
        double        niftySpot, vix, atm;
        double        adx, bbw, percentB, emaSlope;
        int           candleCount;
        String        verdict;

        static TickRow parse(String line) {
            String[] p  = line.split(",", -1);
            TickRow  r  = new TickRow();
            r.timestamp  = p[0].trim();
            r.dateTime   = LocalDateTime.parse(r.timestamp, DT_FMT);
            r.niftySpot  = Double.parseDouble(p[1]);
            r.vix        = Double.parseDouble(p[2]);
            r.atm        = Double.parseDouble(p[3]);
            r.adx        = Double.parseDouble(p[4]);
            r.bbw        = Double.parseDouble(p[5]);
            r.percentB   = Double.parseDouble(p[6]);
            r.emaSlope   = Double.parseDouble(p[7]);
            r.candleCount = Integer.parseInt(p[8].trim());
            r.verdict     = p[9].trim();
            return r;
        }
    }

    static class ChainRow {
        String timestamp;
        String symbol;
        double strike, ltp, delta, iv, theta, vega, gamma;
        String type;

        static ChainRow parse(String line) {
            String[] p  = line.split(",", -1);
            ChainRow r  = new ChainRow();
            r.timestamp  = p[0].trim();
            r.symbol     = p[1].trim();
            r.strike     = Double.parseDouble(p[2]);
            r.type       = p[3].trim();
            r.ltp        = Double.parseDouble(p[4]);
            r.delta      = Double.parseDouble(p[5]);
            r.iv         = Double.parseDouble(p[6]);
            r.theta      = Double.parseDouble(p[7]);
            r.vega       = Double.parseDouble(p[8]);
            r.gamma      = Double.parseDouble(p[9]);
            return r;
        }

        OptionContract toOptionContract() {
            OptionContract.OptionType optType =
                    "CALL".equals(type) ? OptionContract.OptionType.CALL : OptionContract.OptionType.PUT;
            // Extract expiry from symbol — e.g. NIFTY2660223900PE → 26602 (YYDDMM) or similar
            String expiry = extractExpiry(symbol);
            OptionContract oc = new OptionContract(symbol, strike, optType, expiry, ltp, delta, iv);
            oc.setTheta(theta);
            oc.setVega(vega);
            oc.setGamma(gamma);
            return oc;
        }

        private String extractExpiry(String sym) {
            // NIFTY + 5-char date code + strike + type
            // e.g. NIFTY2660223900PE → "26602" = expiry portion
            if (sym != null && sym.startsWith("NIFTY") && sym.length() > 10) {
                return sym.substring(5, 10); // raw — enough for chain matching
            }
            return "UNKNOWN";
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Replay stubs — fill at recorded LTPs, no real orders
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Order executor for replay — fills at the recorded LTP in the chain snapshot.
     * No REST calls, no WebSocket, no side effects.
     *
     * Bug 2 fix: sellBack/buyBack now look up the exit LTP from the ReplayMarketData
     * (which holds the current tick's snapshot) rather than leg.getContract().getLtp().
     * The contract object was created at entry and its LTP field is frozen at entry-time
     * price → every exit fill equalled entry fill → realisedPnl = ₹0 for every trade.
     */
    static class ReplayOrderExecutor extends ZerodhaOrderExecutor {
        private final ReplayMarketData marketData;

        ReplayOrderExecutor(TradeConfig config, ReplayMarketData marketData) {
            super(config);
            this.marketData = marketData;
        }

        @Override
        public TradeLeg buyOption(OptionContract option) {
            Logger.info(String.format("[REPLAY] BUY  %s @ \u20b9%.2f", option.getSymbol(), option.getLtp()));
            return new TradeLeg(option, TradeLeg.Side.BUY,
                    option.getLtp() > 0 ? 1 : 1, option.getLtp(), LocalDateTime.now());
        }

        @Override
        public TradeLeg sellOption(OptionContract option) {
            Logger.info(String.format("[REPLAY] SELL %s @ \u20b9%.2f", option.getSymbol(), option.getLtp()));
            return new TradeLeg(option, TradeLeg.Side.SELL,
                    1, option.getLtp(), LocalDateTime.now());
        }

        @Override
        public double sellBack(TradeLeg leg) {
            // fetch current-tick LTP from the live snapshot, not the entry-frozen contract
            double fill = marketData.fetchLtp(leg.getContract());
            Logger.info(String.format("[REPLAY] EXIT SELL %s @ ₹%.2f (entry ₹%.2f)",
                    leg.getContract().getSymbol(), fill, leg.getEntryPrice()));
            return fill;
        }

        @Override
        public double buyBack(TradeLeg leg) {
            double fill = marketData.fetchLtp(leg.getContract());
            Logger.info(String.format("[REPLAY] EXIT BUY  %s @ ₹%.2f (entry ₹%.2f)",
                    leg.getContract().getSymbol(), fill, leg.getEntryPrice()));
            return fill;
        }
    }

    /**
     * Market data for replay — serves LTPs and snapshots from the current recorded tick.
     * Updated by the replayer before each engine.onTick() call.
     */
    static class ReplayMarketData extends ZerodhaMarketDataService {
        private MarketSnapshot current;

        ReplayMarketData() { super(null, null); } // no Kite connection needed

        public void setSnapshot(MarketSnapshot snapshot) { this.current = snapshot; }

        @Override
        public double fetchLtp(OptionContract contract) {
            if (current == null) return contract.getLtp();
            return current.getOptionChain().stream()
                    .filter(oc -> oc.getSymbol().equals(contract.getSymbol()))
                    .mapToDouble(OptionContract::getLtp)
                    .findFirst()
                    .orElse(contract.getLtp());
        }

        @Override
        public MarketSnapshot fetchSnapshot() { return current; }

        @Override
        public void startTickerFor(List<OptionContract> contracts) { /* no-op */ }

        @Override
        public void stopTicker() { /* no-op */ }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static String resolveDir() {
        String env = System.getenv("TICK_RECORD_DIR");
        return (env != null && !env.isBlank()) ? env : "tick_data";
    }
}