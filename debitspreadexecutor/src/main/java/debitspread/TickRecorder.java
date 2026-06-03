package debitspread;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * TickRecorder
 *
 * Writes every polling tick to a CSV file so the full day can be replayed
 * without hitting the Kite API again.
 *
 * ── Two CSV files per day ──────────────────────────────────────────────────
 *
 *   ticks_YYYYMMDD.csv       — one row per tick
 *     timestamp, nifty_spot, vix, atm,
 *     adx, bbw, percentB, ema_slope, candle_count, verdict,
 *     [option columns for ATM-4 to ATM+4 strikes, both CE and PE]
 *
 *   chain_YYYYMMDD.csv       — full option chain per tick (separate file,
 *                              avoids making ticks.csv unreadably wide)
 *     timestamp, symbol, strike, type, ltp, delta, iv, theta, vega, gamma
 *
 * ── Usage ──────────────────────────────────────────────────────────────────
 *
 *   // In ZerodhaTradeEngine, after trendDetector.analyse():
 *   recorder.record(snapshot, trend);
 *
 *   // Output directory (created if absent):
 *   export TICK_RECORD_DIR=C:/myprojects/TradingProject/ticks
 *   // or defaults to: ./tick_data/
 *
 * ── Replay ─────────────────────────────────────────────────────────────────
 *
 *   TickReplayer replayer = new TickReplayer("C:/myprojects/.../ticks", "20260601");
 *   replayer.replay(tradeEngine);
 */
public class TickRecorder {

    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int               CHAIN_DEPTH = 5; // ATM ± 5 strikes saved in chain file

    private final String   dir;
    private final String   dateStr;

    // Tick file
    private BufferedWriter tickWriter;
    private boolean        tickHeaderWritten = false;

    // Chain file
    private BufferedWriter chainWriter;
    private boolean        chainHeaderWritten = false;

    private int tickCount = 0;

    public TickRecorder() {
        this(resolveDir());
    }

    public TickRecorder(String outputDir) {
        this.dir     = outputDir;
        this.dateStr = LocalDate.now().format(DATE_FMT);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Records one tick. Call this after every trendDetector.analyse() call.
     *
     * @param snapshot  current market snapshot (spot, vix, atm, option chain)
     * @param trend     result from TrendConfirmationDetector.analyse()
     */
    public void record(MarketSnapshot snapshot,
                       TrendConfirmationDetector.TrendResult trend) {
        try {
            ensureTickWriter();
            ensureChainWriter();

            LocalDateTime ts = LocalDateTime.now();
            String tsStr = ts.format(DT_FMT);

            // ── Tick row ──────────────────────────────────────────────────────
            String tickRow = String.join(",",
                tsStr,
                fmt(snapshot.getNiftySpotPrice()),
                fmt(snapshot.getIndiaVix()),
                fmt(snapshot.getAtmStrike()),
                fmt(trend.adx),
                fmt(trend.bbWidth),
                fmt(trend.percentB),
                fmt(trend.emaSlope),
                String.valueOf(trend.candleCount),
                trend.verdict.name()
            );
            tickWriter.write(tickRow);
            tickWriter.newLine();
            tickWriter.flush();

            // ── Chain rows (one per contract) ─────────────────────────────────
            List<OptionContract> chain = snapshot.getOptionChain();
            double atm = snapshot.getAtmStrike();

            for (OptionContract oc : chain) {
                double dist = Math.abs(oc.getStrikePrice() - atm);
                if (dist > CHAIN_DEPTH * 50) continue; // only ATM ± 5 strikes

                String chainRow = String.join(",",
                    tsStr,
                    oc.getSymbol(),
                    fmt(oc.getStrikePrice()),
                    oc.getOptionType().name(),
                    fmt(oc.getLtp()),
                    fmt(oc.getDelta()),
                    fmt(oc.getIv()),
                    fmt(safe(oc.getTheta())),
                    fmt(safe(oc.getVega())),
                    fmt(safe(oc.getGamma()))
                );
                chainWriter.write(chainRow);
                chainWriter.newLine();
            }
            chainWriter.flush();

            tickCount++;

            if (tickCount % 100 == 0) {
                Logger.info(String.format("[TickRecorder] %d ticks saved → %s",
                    tickCount, tickFilePath()));
            }

        } catch (IOException e) {
            Logger.warn("[TickRecorder] Write failed: " + e.getMessage());
        }
    }

    public void close() {
        try { if (tickWriter  != null) tickWriter.close();  } catch (IOException ignored) {}
        try { if (chainWriter != null) chainWriter.close(); } catch (IOException ignored) {}
        Logger.info(String.format("[TickRecorder] Closed. Total ticks saved: %d | Files: %s, %s",
            tickCount, tickFilePath(), chainFilePath()));
    }

    public int getTickCount() { return tickCount; }

    // ════════════════════════════════════════════════════════════════════════
    // Writer init
    // ════════════════════════════════════════════════════════════════════════

    private void ensureTickWriter() throws IOException {
        if (tickWriter != null) return;
        Files.createDirectories(Paths.get(dir));
        tickWriter = new BufferedWriter(new FileWriter(tickFilePath(), true));
        // Write header only if file is new (append mode)
        if (new File(tickFilePath()).length() == 0) {
            tickWriter.write("timestamp,nifty_spot,vix,atm,adx,bbw_pct,percent_b," +
                             "ema_slope_pct,candle_count,verdict");
            tickWriter.newLine();
            tickHeaderWritten = true;
        }
        Logger.info("[TickRecorder] Tick file: " + tickFilePath());
    }

    private void ensureChainWriter() throws IOException {
        if (chainWriter != null) return;
        Files.createDirectories(Paths.get(dir));
        chainWriter = new BufferedWriter(new FileWriter(chainFilePath(), true));
        if (new File(chainFilePath()).length() == 0) {
            chainWriter.write("timestamp,symbol,strike,type,ltp,delta,iv,theta,vega,gamma");
            chainWriter.newLine();
        }
        Logger.info("[TickRecorder] Chain file: " + chainFilePath());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private String tickFilePath()  { return dir + File.separator + "ticks_"  + dateStr + ".csv"; }
    private String chainFilePath() { return dir + File.separator + "chain_"  + dateStr + ".csv"; }

    private static String fmt(double v) { return String.format("%.4f", v); }

    private static double safe(Double v) { return v != null ? v : 0.0; }

    private static String resolveDir() {
        String env = System.getenv("TICK_RECORD_DIR");
        return (env != null && !env.isBlank()) ? env : "tick_data";
    }
}
