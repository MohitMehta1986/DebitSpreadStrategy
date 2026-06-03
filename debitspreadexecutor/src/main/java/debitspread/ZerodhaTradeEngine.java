package debitspread;


import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * ZerodhaTradeEngine
 *
 * Full Zerodha-integrated trade engine. Replaces the simulated TradeEngine
 * with live market data from KiteConnect and real order placement.
 *
 * State machine: SCANNING → IN_TRADE → EXITED → STOPPED
 */
public class ZerodhaTradeEngine {

    private enum State { IDLE, SCANNING, IN_TRADE, EXITED, STOPPED }

    private final TradeConfig              config;
    private final KiteConnect              kite;
    private final ZerodhaMarketDataService marketData;
    private final ZerodhaOrderExecutor     executor;
    private final TrendConfirmationDetector trendDetector;
    private final DebitSpreadEngine        spreadEngine;      // ← debit spread strategy
    private final TickRecorder             tickRecorder;

    private State            state    = State.IDLE;

    // ── Short strangle position + monitor (debit spread uses DebitSpreadEngine) ──
    private final PortfolioMonitor   monitor;
    private       Position           position = null;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public ZerodhaTradeEngine(TradeConfig config, KiteConnect kite) {
        this.config     = config;
        this.kite       = kite;
        this.marketData = new ZerodhaMarketDataService(config, kite);
        this.executor   = new ZerodhaOrderExecutor(config);
        this.trendDetector = new TrendConfirmationDetector(kite);
        this.spreadEngine  = new DebitSpreadEngine(config, executor, marketData);
        this.monitor       = new PortfolioMonitor(config);
        this.tickRecorder  = new TickRecorder();
    }

    /**
     * Must be called once before run() to load instrument cache.
     */
    public void initialize() throws KiteException, IOException {
        marketData.initialize();

        // ── Market Regime Check ────────────────────────────────────────────
        // Evaluate market conditions before committing to trade today.
        // AVOID verdict stops the engine before any order is placed.


        try {
            trendDetector.initialize();
        } catch (Exception e) {
            Logger.warn("[TrendDetector] Seed failed: " + e.getMessage() + " — will build from live ticks.");
        }

        state = State.SCANNING;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Main Loop
    // ════════════════════════════════════════════════════════════════════════

    public void run() {
        Logger.info("Engine running. State: " + state);

        while (state != State.STOPPED) {
            try {
                tick();
                Thread.sleep(config.pollingIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.warn("Interrupted. Stopping.");
                state = State.STOPPED;
            } catch (KiteException e) {
                Logger.error("Kite API error: [" + e.code + "] " + e.getMessage());
                handleKiteError(e);
            } catch (IOException e) {
                Logger.error("Network error: " + e.getMessage());
                // Retry on next tick
            } catch (Exception e) {
                Logger.error("Unexpected error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        printSessionSummary();
    }

    public void stop() {
        Logger.info("Stop requested.");


        // Force-exit open spread position
        if (spreadEngine.hasActivePosition()) {
            Logger.warn("Force-exiting spread position on shutdown...");
            try {
                // Trigger a force exit via onTick with market-closing flag
                spreadEngine.onTick(
                        marketData.fetchSnapshot(),
                        new double[0], new double[0], new double[0],
                        0.5, 30.0,
                        LocalTime.of(15, 16)  // past force-exit time → triggers FORCE_EXIT
                );
            } catch (KiteException | IOException e) {
                Logger.error("Failed to exit spread on shutdown: " + e.getMessage());
            } catch (Exception e) {
                Logger.error("Unexpected error exiting spread on shutdown: " + e.getMessage());
            }
        }

        marketData.stopTicker();
        tickRecorder.close();
        state = State.STOPPED;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tick Logic
    // ════════════════════════════════════════════════════════════════════════

    private void tick() throws KiteException, IOException {
        LocalTime now = LocalTime.now();

        if (!isMarketOpen(now)) {
            if (state != State.IDLE) {
                Logger.info("Outside market hours. Waiting...");
                state = State.IDLE;
            }
            return;
        }

        // If spread engine has an active position, handle it regardless of state
        if (spreadEngine.hasActivePosition()) {
            handleInTrade(now);
            return;
        }


        switch (state) {
            case IDLE:
                state = State.SCANNING;
                break;

            case SCANNING:
                handleScanning();
                break;

            case IN_TRADE:
                handleInTrade(now);
                break;

            case EXITED:
                Logger.info("Position exited. Session complete.");
                state = State.STOPPED;
                break;

            default:
                break;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCANNING
    // ════════════════════════════════════════════════════════════════════════

    private void handleScanning() throws KiteException, IOException {
        MarketSnapshot snapshot = marketData.fetchSnapshot();

        // ── VIX hard block ────────────────────────────────────────────────────
        if (snapshot.getIndiaVix() >= config.vixNoTradeThreshold) {
            Logger.warn(String.format("🚫 VIX=%.2f ≥ %.0f — NO TRADE (either strategy).",
                    snapshot.getIndiaVix(), config.vixNoTradeThreshold));
            return;
        }

        // ── Feed tick to trend detector ───────────────────────────────────────
        trendDetector.addTick(snapshot.getNiftySpotPrice(), java.time.LocalDateTime.now());
        TrendConfirmationDetector.TrendResult trend = trendDetector.analyse();

        Logger.info(String.format("[DETECTOR] Candles=%d | ADX=%.1f | BBW=%.2f%% | %%B=%.2f | EMASlope=%.3f%% | Verdict=%s",
                trend.candleCount, trend.adx, trend.bbWidth, trend.percentB, trend.emaSlope, trend.verdict));

        tickRecorder.record(snapshot, trend);

        if (trend.isFlat()) {
            Logger.debug("[SCAN] No trend signal yet — waiting.");
            return;
        }

        // TRENDING or COMPRESSING — pass to spread engine for full validation
        if (!spreadEngine.isDayEnded()) {
            spreadEngine.onTick(
                    snapshot,
                    trend.closes, trend.highs, trend.lows,
                    trend.percentB, trend.adx,
                    LocalTime.now());
        } else {
            Logger.info("[NO-TRADE] Force-exit time passed — no further entries today.");
        }

    }

    // ════════════════════════════════════════════════════════════════════════
    // IN_TRADE
    // ════════════════════════════════════════════════════════════════════════

    private void handleInTrade(LocalTime now) throws KiteException, IOException {
        // ── If spread engine has an active position, delegate to it ───────────
        if (spreadEngine.hasActivePosition()) {
            // Fetch snapshot FIRST so we pass real spot price to trendDetector.
            // Passing 0 corrupts the candle window (low=0, close=0) causing
            // BBW=545% and garbage candle arrays passed to the spread engine.
            MarketSnapshot snapshot = marketData.fetchSnapshot();
            trendDetector.addTick(snapshot.getNiftySpotPrice(), java.time.LocalDateTime.now());
            TrendConfirmationDetector.TrendResult trend = trendDetector.analyse();

            Logger.info(String.format("[DETECTOR] Candles=%d | ADX=%.1f | BBW=%.2f%% | %%B=%.2f | EMASlope=%.3f%% | Verdict=%s",
                    trend.candleCount, trend.adx, trend.bbWidth, trend.percentB, trend.emaSlope, trend.verdict));

            tickRecorder.record(snapshot, trend);

            spreadEngine.onTick(
                    snapshot,
                    trend.closes, trend.highs, trend.lows,
                    trend.percentB, trend.adx,
                    now);

            if (spreadEngine.isDayEnded()) {
                Logger.info("[SPREAD] Day ended. Moving to EXITED state.");
                state = State.EXITED;
            }
            return;
        }

        // ── Short strangle / straddle position monitoring ────────────────────
        // position is null unless a strangle/straddle was opened earlier this session
        if (position == null || position.isExited()) {
            Logger.debug("[IN_TRADE] No strangle position active — nothing to monitor.");
            state = State.SCANNING;
            return;
        }

        // Force-exit check (market closing soon)
        Position.ExitReason forceExit = monitor.evaluateForceExit(position, isMarketClosingSoon(now));
        if (forceExit != Position.ExitReason.NONE) {
            exitTrade(forceExit);
            return;
        }

        // P&L-based exit
        Position.ExitReason reason = monitor.evaluate(position);
        if (reason != Position.ExitReason.NONE) {
            exitTrade(reason);
        }
    }


    private void exitTrade(Position.ExitReason reason) throws IOException {
        Logger.info("[EXIT] Exiting strangle position. Reason: " + reason);

        marketData.stopTicker();

        double callExit = executor.buyBack(position.getCallLeg());
        double putExit  = executor.buyBack(position.getPutLeg());

        position.markExited(callExit, putExit, reason, java.time.LocalDateTime.now());
        monitor.resetForNewPosition(); // reset trail floor for next position
        state = State.EXITED;

        Logger.info(String.format("[EXIT] DONE | Reason=%s | PnL=₹%.2f",
                reason, position.getTotalRealisedPnl()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private boolean isMarketOpen(LocalTime now) {
        LocalTime open  = LocalTime.parse(config.marketOpenTime, TIME_FMT);
        LocalTime close = LocalTime.parse(config.marketCloseTime, TIME_FMT);
        return now.isAfter(open) && now.isBefore(close);
    }

    private boolean isMarketClosingSoon(LocalTime now) {
        LocalTime close = LocalTime.parse(config.marketCloseTime, TIME_FMT);
        return now.isAfter(close.minusMinutes(config.forceExitMinutesBeforeClose));
    }

    /**
     * Handle Kite-specific errors:
     *   403 → session expired, stop engine
     *   429 → rate limit, back off
     *   5xx → transient, retry
     */
    private void handleKiteError(KiteException e) {
        if (e.code == 403) {
            Logger.error("Session expired (403). Please renew access_token. Stopping.");
            state = State.STOPPED;
        } else if (e.code == 429) {
            Logger.warn("Rate limit hit (429). Backing off 10 seconds...");
            try { Thread.sleep(10_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        // 5xx → just log and retry on next tick
    }

    private void printSessionSummary() {
        Logger.info("═══════════════════════════════════════════════");
        Logger.info("              SESSION SUMMARY");
        Logger.info("═══════════════════════════════════════════════");

        boolean anyTrade = false;

        StringBuilder summary = new StringBuilder();

        summary.append("═══════════════════════════════════════════════\n");
        summary.append("              SESSION SUMMARY\n");
        summary.append("═══════════════════════════════════════════════\n");

        // ── Debit Spread — full session history (all re-entries) ─────────────
        java.util.List<DebitSpreadPosition> allSpreads = spreadEngine.getAllPositions();
        if (!allSpreads.isEmpty()) {
            anyTrade = true;

            String header = String.format(
                    "── Debit Spread (%d trade(s)) ──────────────────", allSpreads.size());
            Logger.info(header);
            summary.append(header).append("\n");

            for (int i = 0; i < allSpreads.size(); i++) {
                DebitSpreadPosition sp = allSpreads.get(i);
                double pnl = sp.isExited() ? sp.getRealisedPnl() : sp.getUnrealisedPnl();
                String line = String.format(
                        "  Trade #%d | %s | Exit=%-16s | P&L=\u20b9%.2f | MaxProfit=\u20b9%.2f | MaxLoss=\u20b9%.2f",
                        i + 1, sp.direction, sp.getExitReason(),
                        pnl, sp.getMaxProfitAmount(), sp.getMaxLossAmount());
                Logger.info(line);
                summary.append(line).append("\n");
            }

            long wins = allSpreads.stream().filter(p -> p.getRealisedPnl() > 0).count();
            String totals = String.format(
                    "  Session Total: \u20b9%.2f | Trades=%d | Wins=%d | WinRate=%.0f%%",
                    spreadEngine.getTotalSessionPnl(),
                    allSpreads.size(), wins,
                    wins * 100.0 / allSpreads.size());
            Logger.info(totals);
            summary.append(totals).append("\n");
        }

        if (!anyTrade) {
        Logger.info("No positions were entered this session.");
        summary.append("No positions were entered this session.\n");
    }

        Logger.info("═══════════════════════════════════════════════");

        summary.append("═══════════════════════════════════════════════\n");

    writeSummaryToFile(summary.toString());
}

    private void writeSummaryToFile(String content) {
        try {
            // Create summaries directory if not exists
            Path dir = Paths.get("summaries");
            Files.createDirectories(dir);

            // Date format DDMMYYYY
            String date = LocalDate.now()
                    .format(DateTimeFormatter.ofPattern("ddMMyyyy"));

            // Find next run count
            int runCount = 1;

            try (Stream<Path> files = Files.list(dir)) {
                runCount = (int) files
                        .filter(p -> p.getFileName()
                                .toString()
                                .startsWith(date))
                        .count() + 1;
            }

            String fileName = String.format(
                    "%s_%03d_DebitSpread.txt",
                    date,
                    runCount
            );

            Path filePath = dir.resolve(fileName);

            Files.writeString(
                    filePath,
                    content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

            Logger.info("Summary written to file: " + filePath);

        } catch (Exception e) {
            Logger.error("Failed to write session summary file :" +e.toString());
        }
    }
}