package debitspread;

import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DebitSpreadBacktest
 *
 * Drives DebitSpreadEngine through two weeks of historical NIFTY candles
 * to validate entry/exit logic, confirm re-entry between trades, and
 * produce a full per-trade and session P&L summary.
 *
 * How to run:
 * ─────────────────────────────────────────────────────────────────────────
 *   export KITE_API_KEY=xxxxxxxxxxxxxxxx
 *   export KITE_ACCESS_TOKEN=yyyyyyyyyyyyyyyy
 *
 *   // In your main class:
 *   DebitSpreadBacktest.run();
 * ─────────────────────────────────────────────────────────────────────────
 *
 * What it tests:
 *   - ADX/BBW trending detection gate
 *   - Breakout confirmation (2-of-3 sliding window)
 *   - EMA slope gate
 *   - Entry: BULL CALL SPREAD or BEAR PUT SPREAD selection
 *   - Exit: TARGET (80%), STOP_LOSS (50% debit), TREND_REVERSAL, FORCE_EXIT
 *   - Re-entry after each exit (dayEnded=false until 15:15)
 *   - VIX emergency exit
 *
 * The engine's ZerodhaOrderExecutor is replaced with a BacktestOrderExecutor
 * that fills at the candle's option LTP — no real orders are placed.
 */
public class DebitSpreadBacktest {

    // ── Lookback window for indicators ───────────────────────────────────────
    // Engine needs at least 30 candles of history for EMA(20), ADX(14)x2+1=29, BB(20)
    private static final int WARMUP_CANDLES = 30; // ADX(14) needs period*2+1=29 candles min (5-min candles)

    // ── Simulated config (adjust to match your live TradeConfig) ─────────────
    private static final int    LOTS     = 1;
    private static final int    LOT_SIZE = 75;   // NIFTY lot size
    private static final String TRADE_URL = "http://localhost:8080"; // unused in backtest

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception, KiteException {
        run();
    }

    public static void run() throws KiteException, IOException {

        Logger.info("====================================================");
        Logger.info("  DEBIT SPREAD BACKTEST — last 2 weeks");
        Logger.info("====================================================");

        // ── 1. Fetch data ─────────────────────────────────────────────────────
        BacktestDataFetcher fetcher = BacktestDataFetcher.fromEnv();
        fetcher.initialize();
        List<BacktestCandle> allCandles = fetcher.fetchTwoWeeks();

        if (allCandles.isEmpty()) {
            Logger.warn("[BACKTEST] No candles returned. Check API key and token.");
            return;
        }

        // ── 2. Group candles by trading day ───────────────────────────────────
        Map<LocalDate, List<BacktestCandle>> byDay = allCandles.stream()
                .collect(Collectors.groupingBy(
                        c -> c.time.toLocalDate(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Logger.info(String.format("[BACKTEST] %d candles across %d trading days.",
                allCandles.size(), byDay.size()));

        // ── 3. Per-day results accumulator ────────────────────────────────────
        List<DayResult> dayResults = new ArrayList<>();

        // ── 4. Replay each day ────────────────────────────────────────────────
        for (Map.Entry<LocalDate, List<BacktestCandle>> entry : byDay.entrySet()) {
            LocalDate            date        = entry.getKey();
            List<BacktestCandle> dayCandles  = entry.getValue();

            Logger.info("\n----------------------------------------------------");
            Logger.info("[DAY] " + date + " | Candles=" + dayCandles.size());
            Logger.info("----------------------------------------------------");

            DayResult dr = replayDay(date, dayCandles);
            dayResults.add(dr);

            Logger.info(String.format("[DAY RESULT] %s | Trades=%d | P&L=Rs%.2f",
                    date, dr.trades.size(), dr.totalPnl()));
        }

        // ── 5. Print full summary ─────────────────────────────────────────────
        printSummary(dayResults);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Replay one trading day
    // ════════════════════════════════════════════════════════════════════════

    private static DayResult replayDay(LocalDate date, List<BacktestCandle> candles)
            throws KiteException, IOException {

        // ── Build a fresh engine for this day ─────────────────────────────────
        TradeConfig config    = buildConfig();
        BacktestOrderExecutor      executor   = new BacktestOrderExecutor();
        BacktestMarketDataService  marketData = new BacktestMarketDataService();
        DebitSpreadEngine          engine     = new DebitSpreadEngine(config, executor, marketData);

        // Rolling candle history arrays (OHLC) for indicator computation
        // DebitSpreadEngine.onTick() expects double[] of recent closes/highs/lows
        // We maintain a sliding window of the last 40 candles
        final int WINDOW = 40;
        double[] closes = new double[WINDOW];
        double[] highs  = new double[WINDOW];
        double[] lows   = new double[WINDOW];
        int      filled = 0;

        for (int i = 0; i < candles.size(); i++) {
            BacktestCandle candle = candles.get(i);

            // Slide the window
            if (filled < WINDOW) {
                closes[filled] = candle.niftyClose;
                highs [filled] = candle.niftyHigh;
                lows  [filled] = candle.niftyLow;
                filled++;
            } else {
                System.arraycopy(closes, 1, closes, 0, WINDOW - 1);
                System.arraycopy(highs,  1, highs,  0, WINDOW - 1);
                System.arraycopy(lows,   1, lows,   0, WINDOW - 1);
                closes[WINDOW - 1] = candle.niftyClose;
                highs [WINDOW - 1] = candle.niftyHigh;
                lows  [WINDOW - 1] = candle.niftyLow;
            }

            // Skip warmup period — indicators need history to be meaningful
            if (filled < WARMUP_CANDLES) {
                Logger.debug(String.format("  [WARMUP %d/%d] %s | C=%.2f",
                        filled, WARMUP_CANDLES, candle.time, candle.niftyClose));
                continue;
            }

            // Compute ADX and %B from the rolling window
            // ADX needs period*2+1 candles; guard here in case filled is right at warmup edge
            double adx      = filled >= 29
                    ? TechnicalIndicators.adx(highs, lows, closes, filled, 14)
                    : 0.0;
            double percentB = TechnicalIndicators.percentB(closes, filled, 20);

            // Build MarketSnapshot from this candle
            MarketSnapshot snapshot = new MarketSnapshot(
                    candle.time.atZone(java.time.ZoneId.of("Asia/Kolkata"))
                            .toLocalDateTime(),
                    candle.niftyClose,
                    candle.vix,
                    candle.atm,
                    candle.optionChain != null ? candle.optionChain : Collections.emptyList()
            );

            // Update market data service with current candle so LTP/VIX calls are accurate
            marketData.setCurrentCandle(candle);

            // Feed to engine
            try {
                engine.onTick(snapshot,
                        Arrays.copyOf(closes, filled),
                        Arrays.copyOf(highs,  filled),
                        Arrays.copyOf(lows,   filled),
                        percentB, adx,
                        candle.time.toLocalTime());
            } catch (Exception e) {
                Logger.warn("[BACKTEST] Tick error at " + candle.time + ": " + e.getMessage());
            }
        }

        // Collect trades from this day
        DayResult dr = new DayResult(date);
        dr.trades.addAll(engine.getAllPositions());
        return dr;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Summary printer
    // ════════════════════════════════════════════════════════════════════════

    private static void printSummary(List<DayResult> days) {
        Logger.info("\n====================================================");
        Logger.info("  BACKTEST SUMMARY");
        Logger.info("====================================================");

        int    totalTrades = 0;
        int    totalWins   = 0;
        double totalPnl    = 0;
        int    tradeNum    = 0;

        for (DayResult dr : days) {
            if (dr.trades.isEmpty()) continue;
            Logger.info("\n[" + dr.date + "] " + dr.trades.size() + " trade(s)");
            for (DebitSpreadPosition pos : dr.trades) {
                tradeNum++;
                double pnl = pos.isExited() ? pos.getRealisedPnl() : pos.getUnrealisedPnl();
                totalPnl   += pnl;
                totalTrades++;
                if (pnl > 0) totalWins++;

                Logger.info(String.format(
                        "  #%02d | %-15s | Entry=Rs%.2f (debit=Rs%.2f) | " +
                                "MaxProfit=Rs%.2f MaxLoss=Rs%.2f | P&L=Rs%.2f | Exit=%-16s | %s",
                        tradeNum,
                        pos.direction,
                        pos.netDebit * pos.quantity,
                        pos.netDebit,
                        pos.getMaxProfitAmount(),
                        pos.getMaxLossAmount(),
                        pnl,
                        pos.getExitReason(),
                        pos.isExited() ? "CLOSED" : "OPEN"));
            }
            Logger.info(String.format("  Day P&L: Rs%.2f", dr.totalPnl()));
        }

        double winRate = totalTrades > 0 ? (totalWins * 100.0 / totalTrades) : 0;

        Logger.info("\n====================================================");
        Logger.info(String.format("  Total Trades : %d", totalTrades));
        Logger.info(String.format("  Wins         : %d (%.0f%%)", totalWins, winRate));
        Logger.info(String.format("  Losses       : %d", totalTrades - totalWins));
        Logger.info(String.format("  Total P&L    : Rs%.2f", totalPnl));
        Logger.info("====================================================");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static TradeConfig buildConfig() {
        TradeConfig cfg = new TradeConfig.Builder().lots(LOTS).build();
        return cfg;
    }

    // ── Day result accumulator ────────────────────────────────────────────────

    static class DayResult {
        final LocalDate                 date;
        final List<DebitSpreadPosition> trades = new ArrayList<>();

        DayResult(LocalDate date) { this.date = date; }

        double totalPnl() {
            return trades.stream()
                    .mapToDouble(p -> p.isExited() ? p.getRealisedPnl() : p.getUnrealisedPnl())
                    .sum();
        }
    }

    // ── Backtest order executor — fills at candle LTP, no real orders ─────────

    /**
     * Replaces ZerodhaOrderExecutor during backtesting.
     * Reads fill price directly from OptionContract.getLtp() — no REST calls,
     * no polling, no side effects.
     */
    static class BacktestOrderExecutor extends ZerodhaOrderExecutor {

        BacktestOrderExecutor() {
            super(buildConfig());
        }

        @Override
        public TradeLeg buyOption(OptionContract option) {
            double fill = option.getLtp();
            Logger.info(String.format("[SIM] BUY  %s @ Rs%.2f", option.getSymbol(), fill));
            return new TradeLeg(option, TradeLeg.Side.BUY,
                    LOTS * LOT_SIZE, fill, java.time.LocalDateTime.now());
        }

        @Override
        public TradeLeg sellOption(OptionContract option) {
            double fill = option.getLtp();
            Logger.info(String.format("[SIM] SELL %s @ Rs%.2f", option.getSymbol(), fill));
            return new TradeLeg(option, TradeLeg.Side.SELL,
                    LOTS * LOT_SIZE, fill, java.time.LocalDateTime.now());
        }

        @Override
        public double sellBack(TradeLeg leg) {
            double fill = leg.getContract().getLtp();
            Logger.info(String.format("[SIM] EXIT SELL %s @ Rs%.2f (entry Rs%.2f)",
                    leg.getContract().getSymbol(), fill, leg.getEntryPrice()));
            return fill;
        }

        @Override
        public double buyBack(TradeLeg leg) {
            double fill = leg.getContract().getLtp();
            Logger.info(String.format("[SIM] EXIT BUY  %s @ Rs%.2f (entry Rs%.2f)",
                    leg.getContract().getSymbol(), fill, leg.getEntryPrice()));
            return fill;
        }
    }
    // ── Backtest MarketDataService — serves LTPs from current candle ──────────

    /**
     * Replaces ZerodhaMarketDataService during backtesting.
     * Call setCurrentCandle() before each engine.onTick() so that
     * fetchLtp() and fetchSnapshot() return values from the current candle.
     */
    static class BacktestMarketDataService extends ZerodhaMarketDataService {

        private BacktestCandle current;

        BacktestMarketDataService() {
            super(buildConfig(), null); // KiteConnect not needed in backtest
        }

        public void setCurrentCandle(BacktestCandle candle) {
            this.current = candle;
        }

        @Override
        public double fetchLtp(OptionContract contract) {
            if (current == null || current.optionChain == null) return contract.getLtp();
            return current.optionChain.stream()
                    .filter(oc -> oc.getSymbol().equals(contract.getSymbol()))
                    .mapToDouble(OptionContract::getLtp)
                    .findFirst()
                    .orElse(contract.getLtp()); // fallback to last known LTP
        }

        @Override
        public MarketSnapshot fetchSnapshot() {
            if (current == null) return null;
            return new MarketSnapshot(
                    current.time,
                    current.niftyClose,
                    current.vix,
                    current.atm,
                    current.optionChain != null ? current.optionChain : java.util.Collections.emptyList()
            );
        }

        @Override
        public void startTickerFor(java.util.List<OptionContract> contracts) {
            // no-op in backtest
        }

        @Override
        public void stopTicker() {
            // no-op in backtest
        }
    }

}