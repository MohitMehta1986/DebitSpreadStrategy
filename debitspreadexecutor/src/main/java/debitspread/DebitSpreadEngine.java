package debitspread;


import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;/**
 * DebitSpreadEngine
 *
 * Runs a Directional Debit Spread strategy on NIFTY FNO.
 * Called by ZerodhaTradeEngine when SidewaysMarketDetector returns TRENDING.
 *
 * ── Strategy ──────────────────────────────────────────────────────────────
 *
 *   BULLISH (ADX trending up, +DI > -DI, %B > 0.7, EMA9 > EMA21):
 *     BUY  ATM Call  (delta ~0.50, strike closest to spot)
 *     SELL OTM Call  (delta ~0.25, strike 150–200 pts above ATM)
 *     → Profits if NIFTY rises above short strike before expiry
 *
 *   BEARISH (ADX trending down, -DI > +DI, %B < 0.3, EMA9 < EMA21):
 *     BUY  ATM Put   (delta ~-0.50, strike closest to spot)
 *     SELL OTM Put   (delta ~-0.25, strike 150–200 pts below ATM)
 *     → Profits if NIFTY falls below short strike before expiry
 *
 * ── Entry Filters ─────────────────────────────────────────────────────────
 *   • Long leg:  |delta| 0.45 – 0.60  (ATM or slightly ITM)
 *   • Short leg: |delta| 0.20 – 0.35  (OTM — offsets cost)
 *   • Net debit ≤ 45% of strike width (decent R:R)
 *   • Max loss / Max profit ratio ≤ 1:1.2  (min 1.2 R:R required)
 *   • Direction confidence ≥ 60%
 *
 * ── Exit Conditions ───────────────────────────────────────────────────────
 *   • TARGET : Spread value reaches 80% of max theoretical profit
 *   • STOP   : Loss hits -₹600 (was -₹650) OR 50% of net debit, whichever is tighter
 *   • TRAIL  : Floor = max(ratchet step, peak × 40%) — floor can only rise
 *   • FORCE  : 15 min before market close
 *   • COOLDOWN: 5-min re-entry block after any exit (prevents whipsaw re-entry)
 *
 * ── Entry Filters (additional) ────────────────────────────────────────────
 *   • Peak profit gate: max theoretical profit ≥ ₹350 (thin spreads rejected)
 *
 * ── Usage from ZerodhaTradeEngine ────────────────────────────────────────
 *   DebitSpreadEngine spreadEngine = new DebitSpreadEngine(config, executor, marketData);
 *   if (sideways.isTrending()) {
 *       spreadEngine.onTick(snapshot, sideways, now);
 *   }
 */
public class DebitSpreadEngine {

    // ── Entry thresholds ──────────────────────────────────────────────────────
    private static final double LONG_DELTA_MIN   = 0.45;
    private static final double LONG_DELTA_MAX   = 0.60;
    private static final double SHORT_DELTA_MIN  = 0.20;
    private static final double SHORT_DELTA_MAX  = 0.35;
    private static final double MAX_DEBIT_RATIO  = 0.45;
    private static final double MIN_RR_RATIO     = 1.2;

    // ── Exit thresholds ───────────────────────────────────────────────────────
    private static final double PROFIT_TARGET_PCT  = 80.0;
    private static final double STOP_LOSS_PCT      = 50.0;
    private static final LocalTime FORCE_EXIT_TIME = LocalTime.of(15, 15);

    // ── VIX thresholds (Rec #1 — tiered, not binary block) ───────────────────
    private static final double VIX_NORMAL_MAX    = 22.0;  // normal qty if VIX < 22
    private static final double VIX_REDUCED_MAX   = 28.0;  // reduced qty if 22 ≤ VIX < 28
    private static final double VIX_SKIP_ABOVE    = 28.0;  // skip entirely if VIX ≥ 28
    private static final double VIX_EMERGENCY_EXIT = 28.0; // exit open position if VIX spikes here
    private static final int    REDUCED_QTY_DIVISOR = 2;   // half lots when VIX in reduced zone

    // ── ADX thresholds (Rec #2 — not sole indicator) ─────────────────────────
    private static final double ADX_TREND_MIN  = 18.0;  // developing trend threshold (aligns with SidewaysMarketDetector)
    private static final double ADX_TREND_MAX  = 45.0;  // exhaustion cap

    // ── Compression detection (Rec #3) ───────────────────────────────────────
    private static final double BB_COMPRESSION_WIDTH = 0.55; // BBW < 0.55% = squeeze (avoids dead zone at 0.50-0.55)
    private static final double ADX_COMPRESSION_MIN  = 18.0; // ADX > 18 during squeeze (aligned with trend min)

    // ── Breakout confirmation thresholds (Rec #5) ────────────────────────────
    private static final int    MIN_BREAKOUT_CONFIRMATIONS = 2;  // need ≥ 2 of 5 signals
    private static final double LARGE_BODY_RATIO           = 0.6; // body/range ≥ 60%
    private static final double EMA_SLOPE_STRONG_THRESHOLD = 0.01; // slope > 0.01%/candle (calibrated for 5-min NIFTY ~24000)

    // ── Direction confidence (Rec #7) ─────────────────────────────────────────
    private static final int MIN_DIRECTION_CONFIDENCE = 60; // needs 2+ signals (EMA+DI=65, EMA+%B=55+15=70...)

    // ── Internal state machine ────────────────────────────────────────────────
    private enum ScanState {
        SCANNING,           // normal — looking for entry
        WAIT_BREAKOUT,      // compression detected — waiting for confirmation
        BREAKOUT_CONFIRMED  // confirmed — ready to enter
    }
    private ScanState scanState        = ScanState.SCANNING;
    private final int[] breakoutWindow  = new int[3]; // rolling 3-tick window of confirmation counts
    private int       breakoutWinIdx  = 0;              // next write index (ring buffer)
    private Bias      pendingBias      = null; // direction when compression was detected

    private final TradeConfig              config;
    private final ZerodhaOrderExecutor     executor;
    private final ZerodhaMarketDataService marketData;

    // ── Multi-trade session tracking ──────────────────────────────────────────
    // No longer stops after 1 trade — re-enters when conditions are met again.
    // All completed positions stored for session P&L report.
    private DebitSpreadPosition          spreadPosition  = null;   // current active position
    private final List<DebitSpreadPosition> allPositions = new ArrayList<>(); // full history
    private boolean                      dayEnded        = false;  // set at FORCE_EXIT time

    // ── Post-exit cooldown (Fix 1) ────────────────────────────────────────────
    // After any exit (except FORCE_EXIT) a 5-minute cooldown prevents immediate
    // re-entry into a degraded/whipsawing market. Stored as LocalTime so it
    // integrates cleanly with the existing LocalTime-based now parameter.
    private static final int COOLDOWN_MINUTES = 5;
    private LocalTime lastExitTime = null;  // null = no exit yet this session

    // ── %B persistence filter (FIX 03) ───────────────────────────────────────
    // A single large candle can spike %B across the 0.60/0.40 threshold without
    // representing a real trend. Require %B to stay in the bullish/bearish zone
    // for at least MIN_PB_PERSIST_TICKS consecutive ticks before allowing entry.
    // On June 3: %B jumped 0.242→0.758 in ONE candle (12:25→12:30), causing a
    // late bullish entry into an already-completed move.
    private static final int MIN_PB_PERSIST_TICKS = 3;
    private int pbBullTicks = 0;  // consecutive ticks with %B >= 0.60
    private int pbBearTicks = 0;  // consecutive ticks with %B <= 0.40

    public DebitSpreadEngine(TradeConfig config,
                             ZerodhaOrderExecutor executor,
                             ZerodhaMarketDataService marketData) {
        this.config     = config;
        this.executor   = executor;
        this.marketData = marketData;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Main entry point — called by ZerodhaTradeEngine on every tick
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Called by ZerodhaTradeEngine when the market is TRENDING.
     * Manages the full spread lifecycle: scan → enter → monitor → exit.
     *
     * @param snapshot   current market snapshot (spot, VIX, option chain)
     * @param closes     recent close prices from SidewaysDetector candle window
     * @param highs      recent high prices
     * @param lows       recent low prices
     * @param percentB   current %B value (from SidewaysDetector)
     * @param adx        current ADX value (from SidewaysDetector)
     * @param now        current market time
     * @return true if a spread position is now active or was just exited
     */
    public boolean onTick(MarketSnapshot snapshot,
                          double[] closes, double[] highs, double[] lows,
                          double percentB, double adx,
                          LocalTime now) throws IOException, KiteException {

        // Day fully ended (force exit time passed) — no more entries today
        if (dayEnded) return false;

        if (spreadPosition == null || spreadPosition.isExited()) {
            // No active position — scan for next entry
            // (re-entry allowed after exit, unlike old sessionComplete=true)
            return tryScanAndEnter(snapshot, closes, highs, lows, percentB, adx, now);
        } else {
            // Active position — monitor it
            return monitorAndExit(now, closes, highs, lows, percentB, adx);
        }
    }

    public boolean hasActivePosition() {
        return spreadPosition != null && !spreadPosition.isExited();
    }

    /** True only after 15:15 force-exit — no more entries for the day */
    public boolean isDayEnded() { return dayEnded; }

    /** @deprecated use isDayEnded() — sessionComplete kept for API compatibility */
    public boolean isSessionComplete() { return dayEnded; }

    public DebitSpreadPosition          getPosition()    { return spreadPosition; }
    public List<DebitSpreadPosition>    getAllPositions() { return Collections.unmodifiableList(allPositions); }

    /** Total realised P&L across all completed trades this session */
    public double getTotalSessionPnl() {
        return allPositions.stream()
                .filter(DebitSpreadPosition::isExited)
                .mapToDouble(DebitSpreadPosition::getRealisedPnl)
                .sum();
    }

    /** Total number of trades entered this session */
    public int getTradeCount() { return allPositions.size(); }

    // ════════════════════════════════════════════════════════════════════════
    // SCANNING — detect direction, select strikes, enter
    // ════════════════════════════════════════════════════════════════════════

    private boolean tryScanAndEnter(MarketSnapshot snapshot,
                                    double[] closes, double[] highs, double[] lows,
                                    double percentB, double adx,
                                    LocalTime now) throws IOException {

        double vix = snapshot.getIndiaVix();

        // ── Gate: Expiry day (NIFTY weekly expires every TUESDAY) ──────────
        // Use snapshot timestamp — not LocalDate.now() — so backtest works correctly.
        java.time.LocalDate candleDate = snapshot.getTimestamp().toLocalDate();
//        if (candleDate.getDayOfWeek() == DayOfWeek.TUESDAY) {
//            Logger.info("[NO-TRADE] EXPIRY DAY (Tuesday) — no new spread entries.");
//            dayEnded = true;
//            return false;
//        }

        // ── Fix 1: 5-minute cooldown after any exit ───────────────────────────
        // Prevents re-entry into a market still whipsawing from the just-exited move.
        // lastExitTime is set by exitSpread() for every non-FORCE_EXIT exit.
        if (lastExitTime != null && now.isBefore(lastExitTime.plusMinutes(COOLDOWN_MINUTES))) {
            Logger.debug(String.format("[COOLDOWN] %d-min cooldown active — last exit=%s, now=%s. Skip.",
                    COOLDOWN_MINUTES, lastExitTime, now));
            return false;
        }

        // ── Rec #1: Tiered VIX — not a binary block ───────────────────────────
        if (vix >= VIX_SKIP_ABOVE) {
            Logger.info(String.format("[NO-TRADE] VIX=%.2f ≥ %.0f — skip spread entirely.", vix, VIX_SKIP_ABOVE));
            return false;
        }
        // qty will be decided at entry time based on VIX zone
        boolean reducedQty = (vix >= VIX_NORMAL_MAX);
        if (reducedQty) {
            Logger.info(String.format("[WARN]  VIX=%.2f in reduced zone [%.0f–%.0f] — will trade half lots.",
                    vix, VIX_NORMAL_MAX, VIX_REDUCED_MAX));
        }

        // ── Rec #2 + #3: Detect compression or real trend ────────────────────
        // ADX=0 means TrendConfirmationDetector is still warming up — all values
        // including bbWidth and percentB are unreliable. Skip silently.
        if (adx == 0.0) {
            Logger.debug("[WAIT] ADX=0 — detector warming up. Skip.");
            return false;
        }

        double bbWidth = computeBbWidth(closes, 20);

        boolean isCompression = bbWidth < BB_COMPRESSION_WIDTH && adx > ADX_COMPRESSION_MIN;
        boolean isRealTrend   = adx >= ADX_TREND_MIN && adx <= ADX_TREND_MAX && !isCompression;

        Logger.debug(String.format("  BBW=%.2f%% | ADX=%.1f | compression=%b | realTrend=%b",
                bbWidth, adx, isCompression, isRealTrend));

        // ── Rec #3: Enter WAIT_BREAKOUT on compression ────────────────────────
        if (isCompression && scanState == ScanState.SCANNING) {
            DirectionSignal dir = detectDirection(closes, highs, lows, percentB, adx);
            if (dir.confidence >= MIN_DIRECTION_CONFIDENCE) {
                scanState      = ScanState.WAIT_BREAKOUT;
                pendingBias    = dir.bias;
                java.util.Arrays.fill(breakoutWindow, 0);
                breakoutWinIdx = 0;
                Logger.info(String.format(
                        "[COMPRESS] COMPRESSION detected (BBW=%.2f%%, ADX=%.1f). Waiting for breakout. Bias=%s",
                        bbWidth, adx, dir.bias));
            }
            return false; // wait — don't enter during compression
        }

        // ── Rec #4+#5: Wait for breakout confirmation (2-of-3 sliding window) ──
        if (scanState == ScanState.WAIT_BREAKOUT) {
            int confirmations = countBreakoutConfirmations(closes, highs, lows, percentB, adx, pendingBias);

            // Write into ring buffer and advance index
            breakoutWindow[breakoutWinIdx % 3] = confirmations;
            breakoutWinIdx++;

            // Count how many of the last 3 ticks had enough confirmations
            int qualifyingTicks = 0;
            for (int c : breakoutWindow) {
                if (c >= MIN_BREAKOUT_CONFIRMATIONS) qualifyingTicks++;
            }
            int ticksSeen = Math.min(breakoutWinIdx, 3);

            Logger.info(String.format(
                    "WAIT_BREAKOUT | Confirmations=%d/%d | qualifying=%d/%d ticks | bias=%s",
                    confirmations, MIN_BREAKOUT_CONFIRMATIONS, qualifyingTicks, ticksSeen, pendingBias));

            if (qualifyingTicks >= 2) { // 2 of last 3 ticks had sufficient confirmations
                scanState = ScanState.BREAKOUT_CONFIRMED;
                Logger.info("BREAKOUT CONFIRMED (2-of-3 ticks) — proceeding to entry.");
            } else {
                return false; // still waiting
            }
        }

        // ── If no compression detected, require real trend ─────────────────
        // ADX max cap (45) only applies to the direct SCANNING→ENTRY path.
        // On the compression→BREAKOUT_CONFIRMED path, high ADX (e.g. 46) is
        // momentum confirming the breakout — do not block it.
        if (scanState == ScanState.SCANNING && !isRealTrend) {
            Logger.info(String.format("[WARN]  ADX=%.1f not in real-trend range [%.0f–%.0f]. Skip.",
                    adx, ADX_TREND_MIN, ADX_TREND_MAX));
            return false;
        }
        if (scanState == ScanState.BREAKOUT_CONFIRMED && adx < ADX_TREND_MIN) {
            Logger.info(String.format("[WARN]  ADX=%.1f too weak for breakout entry. Skip.", adx));
            return false;
        }

        // ── Rec #6: EMA slope — secondary confirmation (FIX 02: graduated bypass) ──
        //
        // OLD: binary adxStrong (adx ≥ 23) fully bypassed the slope gate.
        //   This allowed slope=+0.0009 (noise) to pass at adx=23.74 on June 3 12:35,
        //   resulting in a late bullish entry into an already-completed move.
        //
        // NEW: three-tier graduated gate:
        //   adx < 23           → full gate: |slope| > 0.010 %/candle
        //   adx 23–27 (border) → relaxed:   |slope| > 0.004 %/candle  (was fully bypassed)
        //   adx ≥ 28  (strong) → bypass entirely — ADX alone confirms trend
        //
        // On 5-min NIFTY ~24000:
        //   0.004% ≈ ~1 pt EMA move per candle — filters noise but passes real momentum
        //   0.010% ≈ ~2.4 pt per candle — full-gate threshold for weak ADX
        double emaSlope    = computeEmaSlope(closes, 20);
        boolean slopeReady = closes.length >= 25; // period(20) + 5 lookahead

        // FIX 02: graduated threshold replaces binary adxStrong flag
        double slopeThreshold;
        boolean slopeBypassed;
        if (adx >= 28.0) {
            slopeBypassed  = true;
            slopeThreshold = 0.0;   // fully bypassed at strong ADX
        } else if (adx >= 23.0) {
            slopeBypassed  = false;
            slopeThreshold = 0.004; // relaxed gate in borderline ADX zone
        } else {
            slopeBypassed  = false;
            slopeThreshold = EMA_SLOPE_STRONG_THRESHOLD; // full gate at weak ADX
        }

        if (slopeReady && !slopeBypassed && Math.abs(emaSlope) < slopeThreshold) {
            Logger.info(String.format(
                    "[WARN]  EMA slope=%.4f%% below threshold %.4f%% (ADX=%.1f tier) — skip.",
                    emaSlope, slopeThreshold, adx));
            if (scanState == ScanState.BREAKOUT_CONFIRMED) {
                scanState = ScanState.SCANNING;
                java.util.Arrays.fill(breakoutWindow, 0);
                breakoutWinIdx = 0;
            }
            return false;
        }
        // Keep the adxStrong boolean for downstream slope-alignment check (same logic)
        boolean adxStrong = slopeBypassed;
        if (!slopeReady) {
            Logger.debug(String.format(
                    "[INFO]  EMA slope skipped (%d/25 candles) — ADX+%%B gate active.", closes.length));
        } else if (adxStrong) {
            Logger.debug(String.format(
                    "[INFO]  EMA slope=%.4f%% noted — slope gate bypassed (ADX=%.1f ≥ 28).", emaSlope, adx));
        } else {
            Logger.debug(String.format(
                    "[INFO]  EMA slope=%.4f%% passed threshold %.4f%% (ADX=%.1f).", emaSlope, slopeThreshold, adx));
        }

        // ── Direction detection ───────────────────────────────────────────────
        Bias dirBias;
        if (scanState == ScanState.BREAKOUT_CONFIRMED && pendingBias != null) {
            dirBias = pendingBias;  // use direction from compression phase
        } else {
            DirectionSignal dir = detectDirection(closes, highs, lows, percentB, adx);
            if (dir.confidence < MIN_DIRECTION_CONFIDENCE) {
                Logger.info(String.format("[WARN]  Confidence=%d%% < %d%% — skip.", dir.confidence, MIN_DIRECTION_CONFIDENCE));
                return false;
            }
            dirBias = dir.bias;
        }

        // Gate: EMA slope sign must agree with direction
        // Skip when: (a) not enough candles, or (b) ADX >= 23 (trend confirmed by ADX alone)
        if (slopeReady && !adxStrong) {
            boolean slopeAligned = (dirBias == Bias.BULLISH && emaSlope > 0)
                    || (dirBias == Bias.BEARISH && emaSlope < 0);
            if (!slopeAligned) {
                Logger.info(String.format("[NO-TRADE] EMA slope=%.3f%% conflicts with %s (ADX=%.1f borderline). Skip.",
                        emaSlope, dirBias, adx));
                scanState = ScanState.SCANNING;
                return false;
            }
        }

        // Gate: %B must align with direction AND persist for MIN_PB_PERSIST_TICKS ticks
        // SKIP on BREAKOUT_CONFIRMED path — %B near 0.50 at breakout time is normal.
        //
        // FIX 03: Added persistence requirement (≥ 3 consecutive ticks).
        // A single large candle can spike %B from 0.24 → 0.76 in one bar without
        // representing a real trend (June 3 12:25→12:30: +67pt candle caused this).
        // Require the threshold to hold for MIN_PB_PERSIST_TICKS consecutive ticks
        // before treating it as a valid directional signal.
        //
        // The counters (pbBullTicks / pbBearTicks) are updated every tick regardless of
        // other gate outcomes, so they reflect the real %B history. They reset when %B
        // exits the zone, ensuring the persistence window is always fresh.
        if (percentB >= 0.60) {
            pbBullTicks++;
            pbBearTicks = 0;
        } else if (percentB <= 0.40) {
            pbBearTicks++;
            pbBullTicks = 0;
        } else {
            pbBullTicks = 0;
            pbBearTicks = 0;
        }

        if (scanState != ScanState.BREAKOUT_CONFIRMED) {
            boolean pbAligned;
            if (dirBias == Bias.BULLISH) {
                pbAligned = (percentB >= 0.60) && (pbBullTicks >= MIN_PB_PERSIST_TICKS);
            } else {
                pbAligned = (percentB <= 0.40) && (pbBearTicks >= MIN_PB_PERSIST_TICKS);
            }
            if (!pbAligned) {
                int ticks = dirBias == Bias.BULLISH ? pbBullTicks : pbBearTicks;
                Logger.info(String.format(
                        "[NO-TRADE] %%B=%.2f misaligns or not yet persistent for %s " +
                                "(need ≥0.60 bull/≤0.40 bear × %d ticks, current=%d ticks). Skip.",
                        percentB, dirBias, MIN_PB_PERSIST_TICKS, ticks));
                scanState = ScanState.SCANNING;
                return false;
            }
        }

        Logger.info(String.format(
                "📐 ENTRY APPROVED | %s | VIX=%.2f(%s) | ADX=%.1f | BBW=%.2f%% | %%B=%.2f | slope=%.3f%%",
                dirBias, vix, reducedQty ? "REDUCED" : "NORMAL",
                adx, bbWidth, percentB, emaSlope));

        // ── FIX #2: Hard DI gate — DI lines must confirm direction ────────────
        // detectDirection() can return BULLISH with confidence=70 even when
        // -DI > +DI (bearish directional pressure dominant), because EMA cross (35)
        // + %B (20) + Price>EMA20 (15) = 70 without any DI contribution.
        // Entering a bull spread with active bearish DI pressure is the primary
        // cause of consistently negative bullish P&L — we block it here as a hard gate.
        double[] diGate = computeDiLines(closes, highs, lows, 14);
        if (dirBias == Bias.BULLISH && diGate[1] > diGate[0]) {
            Logger.info(String.format(
                    "[NO-TRADE] DI conflict: -DI(%.1f) > +DI(%.1f) despite BULLISH bias — skip.",
                    diGate[1], diGate[0]));
            if (scanState == ScanState.BREAKOUT_CONFIRMED) {
                scanState = ScanState.SCANNING;
                java.util.Arrays.fill(breakoutWindow, 0);
                breakoutWinIdx = 0;
            }
            return false;
        }
        if (dirBias == Bias.BEARISH && diGate[0] > diGate[1]) {
            Logger.info(String.format(
                    "[NO-TRADE] DI conflict: +DI(%.1f) > -DI(%.1f) despite BEARISH bias — skip.",
                    diGate[0], diGate[1]));
            if (scanState == ScanState.BREAKOUT_CONFIRMED) {
                scanState = ScanState.SCANNING;
                java.util.Arrays.fill(breakoutWindow, 0);
                breakoutWinIdx = 0;
            }
            return false;
        }
        Logger.debug(String.format("  [OK] DI gate passed: +DI=%.1f -DI=%.1f aligns with %s",
                diGate[0], diGate[1], dirBias));

        // ── Select strikes ────────────────────────────────────────────────────
        Optional<SpreadLegs> legs = dirBias == Bias.BULLISH
                ? selectBullCallSpread(snapshot)
                : selectBearPutSpread(snapshot);

        if (legs.isEmpty()) {
            Logger.info("No valid spread strikes found.");
            return false;
        }

        // ── Rec #7: Enter with VIX-tiered quantity ────────────────────────────
        DebitSpreadPosition.Direction direction = dirBias == Bias.BULLISH
                ? DebitSpreadPosition.Direction.BULL_CALL_SPREAD : DebitSpreadPosition.Direction.BEAR_PUT_SPREAD;

        enterSpread(legs.get(), direction, reducedQty);

        // Reset compression state after successful entry
        scanState = ScanState.SCANNING;
        java.util.Arrays.fill(breakoutWindow, 0);
        breakoutWinIdx = 0;
        pendingBias = null;
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Rec #5 — Breakout Confirmation (counts how many signals fire)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Counts how many of 5 breakout confirmation signals are present.
     * Entry requires ≥ MIN_BREAKOUT_CONFIRMATIONS (2) to proceed.
     *
     *   1. Large candle body (body/range ≥ 60%)
     *   2. EMA slope expansion (slope > threshold)
     *   3. Price broke above/below VWAP approximation (EMA20)
     *   4. %B in breakout zone (> 0.7 for bull, < 0.3 for bear)
     *   5. +DI/-DI divergence (directional pressure)
     */
    private int countBreakoutConfirmations(double[] closes, double[] highs, double[] lows,
                                           double percentB, double adx, Bias bias) {
        int count = 0;
        int n = closes.length;
        if (n < 3) return 0;

        // 1. Large candle body on last candle
        double range = highs[n-1] - lows[n-1];
        double body  = Math.abs(closes[n-1] - closes[n-2]);
        if (range > 0 && body / range >= LARGE_BODY_RATIO) {
            count++;
            Logger.debug(String.format("  [OK] Confirm #1: Large body %.0f%% of range", body/range*100));
        }

        // 2. EMA slope expansion (direction-aware: bullish needs +ve slope, bearish needs -ve)
        double slope = computeEmaSlope(closes, 20);
        boolean slopeOk = (bias == Bias.BULLISH && slope >  EMA_SLOPE_STRONG_THRESHOLD)
                || (bias == Bias.BEARISH && slope < -EMA_SLOPE_STRONG_THRESHOLD);
        if (slopeOk) {
            count++;
            Logger.debug(String.format("  [OK] Confirm #2: EMA slope=%.3f%% aligns with %s", slope, bias));
        }

        // 3. Price broke above/below EMA20 (VWAP proxy)
        double ema20 = computeEma(closes, 20);
        boolean vwapBreak = (bias == Bias.BULLISH && closes[n-1] > ema20)
                || (bias == Bias.BEARISH && closes[n-1] < ema20);
        if (vwapBreak) {
            count++;
            Logger.debug("  [OK] Confirm #3: VWAP break confirmed");
        }

        // 4. %B in breakout zone
        boolean pbBreakout = (bias == Bias.BULLISH && percentB > 0.7)
                || (bias == Bias.BEARISH && percentB < 0.3);
        if (pbBreakout) {
            count++;
            Logger.debug(String.format("  [OK] Confirm #4: %%B=%.2f in breakout zone", percentB));
        }

        // 5. DI divergence — directional pressure
        double[] di     = computeDiLines(highs, lows, closes, 14);
        double diSpread = Math.abs(di[0] - di[1]);
        boolean diConfirm = (bias == Bias.BULLISH && di[0] > di[1] && diSpread > 5)
                || (bias == Bias.BEARISH && di[1] > di[0] && diSpread > 5);
        if (diConfirm) {
            count++;
            Logger.debug(String.format("  [OK] Confirm #5: +DI=%.1f -DI=%.1f spread=%.1f", di[0], di[1], diSpread));
        }

        Logger.debug(String.format("  Breakout confirmations: %d / 5 (need %d)", count, MIN_BREAKOUT_CONFIRMATIONS));
        return count;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Direction Detection
    // ════════════════════════════════════════════════════════════════════════

    private enum Bias { BULLISH, BEARISH }

    private static class DirectionSignal {
        final Bias   bias;
        final int    confidence;  // 0–100
        final String reason;
        DirectionSignal(Bias bias, int confidence, String reason) {
            this.bias = bias; this.confidence = confidence; this.reason = reason;
        }
    }

    /**
     * Determines market direction using 4 indicators:
     *
     *   EMA cross available (n ≥ 21):
     *     1. EMA Cross (EMA9 vs EMA21)         weight 35
     *     2. ADX +DI vs -DI                    weight 30
     *     3. %B position (upper/lower half)    weight 20
     *     4. Price vs EMA20                    weight 15
     *
     *   EMA warmup (n < 21) — FIX 01:
     *     EMA21 is not yet meaningful when fewer than 21 candles exist.
     *     Using it produced a spurious BULLISH bias during the early session
     *     (EMA9 ≈ EMA21 ≈ last close → EMA9 > EMA21 by noise → +35 bull always).
     *     When n < 21, skip the EMA cross entirely and redistribute its 35-weight
     *     to DI lines (total DI weight becomes 65), keeping total = 100.
     */
    private DirectionSignal detectDirection(double[] closes, double[] highs,
                                            double[] lows, double percentB,
                                            double adx) {
        int n = closes.length;
        if (n < 9) return new DirectionSignal(Bias.BULLISH, 0, "Insufficient candles.");

        int bullScore = 0;
        int bearScore = 0;
        StringBuilder reason = new StringBuilder();

        // FIX 01: EMA cross only when EMA21 has enough data to be meaningful.
        // Before candle 21 the warmup bias locked direction=BULLISH for the entire
        // early bearish window (10:55–11:39 on June 3), blocking every valid entry.
        boolean emaReady = (n >= 21);

        if (emaReady) {
            // 1. EMA9 vs EMA21 — weight 35
            double ema9  = computeEma(closes, 9);
            double ema21 = computeEma(closes, 21);
            if (ema9 > ema21) {
                bullScore += 35;
                reason.append(String.format("EMA9>EMA21(%.0f>%.0f)=BULL ", ema9, ema21));
            } else {
                bearScore += 35;
                reason.append(String.format("EMA9<EMA21(%.0f<%.0f)=BEAR ", ema9, ema21));
            }
        } else {
            reason.append(String.format("EMA_WARMUP(n=%d<21)=SKIP ", n));
        }

        // 2. +DI vs -DI — weight 30 normally; 65 when EMA is in warmup (FIX 01)
        int diWeight = emaReady ? 30 : 65;
        double[] di    = computeDiLines(highs, lows, closes, 14);
        double plusDI  = di[0];
        double minusDI = di[1];
        if (plusDI > minusDI) {
            bullScore += diWeight;
            reason.append(String.format("+DI>-DI(%.1f>%.1f)=BULL(w%d) ", plusDI, minusDI, diWeight));
        } else {
            bearScore += diWeight;
            reason.append(String.format("-DI>+DI(%.1f>%.1f)=BEAR(w%d) ", minusDI, plusDI, diWeight));
        }

        // 3. %B — weight 20
        if (percentB >= 0.5) {
            bullScore += 20;
            reason.append(String.format("%%B=%.2f(upper)=BULL ", percentB));
        } else {
            bearScore += 20;
            reason.append(String.format("%%B=%.2f(lower)=BEAR ", percentB));
        }

        // 4. Price vs EMA20 — weight 15
        double ema20 = computeEma(closes, 20);
        double last  = closes[n - 1];
        if (last > ema20) {
            bullScore += 15;
            reason.append("Price>EMA20=BULL");
        } else {
            bearScore += 15;
            reason.append("Price<EMA20=BEAR");
        }

        Bias bias       = bullScore >= bearScore ? Bias.BULLISH : Bias.BEARISH;
        int  confidence = Math.max(bullScore, bearScore);  // out of 100

        return new DirectionSignal(bias, confidence, reason.toString().trim());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Strike Selection
    // ════════════════════════════════════════════════════════════════════════

    private static class SpreadLegs {
        final OptionContract longLeg;
        final OptionContract shortLeg;
        final double netDebit;
        final double strikeWidth;
        final double rrRatio;   // maxProfit / netDebit

        SpreadLegs(OptionContract longLeg, OptionContract shortLeg) {
            this.longLeg     = longLeg;
            this.shortLeg    = shortLeg;
            this.strikeWidth = Math.abs(shortLeg.getStrikePrice() - longLeg.getStrikePrice());
            this.netDebit    = longLeg.getLtp() - shortLeg.getLtp();
            double maxProfit = strikeWidth - netDebit;
            this.rrRatio     = netDebit > 0 ? maxProfit / netDebit : 0;
        }

        @Override
        public String toString() {
            return String.format(
                    "Long %s %.0f(Δ%.2f)@₹%.2f + Short %.0f(Δ%.2f)@₹%.2f | " +
                            "Debit=₹%.2f | Width=%.0f | R:R=%.2f",
                    longLeg.getOptionType(),
                    longLeg.getStrikePrice(),  longLeg.getDelta(),  longLeg.getLtp(),
                    shortLeg.getStrikePrice(), shortLeg.getDelta(), shortLeg.getLtp(),
                    netDebit, strikeWidth, rrRatio);
        }
    }

    /**
     * Bull Call Spread: BUY ATM call (delta ~0.50), SELL OTM call (delta ~0.25)
     *
     * FIX #1: Removed the strike >= atm floor filter.
     * When spot doesn't land exactly on a strike (e.g. spot=24480, ATM=24500),
     * the real ATM call (delta ~0.52, strike 24450) was being filtered out, forcing
     * entry into a slightly OTM call that needs more movement to profit.
     * The delta range filter (0.45–0.60) on the long leg already enforces ATM selection.
     */
    private Optional<SpreadLegs> selectBullCallSpread(MarketSnapshot snapshot) {
        List<OptionContract> calls = snapshot.getOptionChain().stream()
                .filter(o -> o.getOptionType() == OptionContract.OptionType.CALL)
                // ✅ No strike floor — delta range (0.45–0.60) selects ATM naturally
                .sorted(Comparator.comparingDouble(OptionContract::getStrikePrice))
                .collect(Collectors.toList());

        return findBestSpread(calls, true);
    }

    /**
     * Bear Put Spread: BUY ATM put (delta ~-0.50), SELL OTM put (delta ~-0.25)
     *
     * FIX #1 (mirror): Removed the strike <= atm ceiling filter for puts.
     * Same reasoning — delta range (0.45–0.60) on the long leg handles ATM selection.
     */
    private Optional<SpreadLegs> selectBearPutSpread(MarketSnapshot snapshot) {
        List<OptionContract> puts = snapshot.getOptionChain().stream()
                .filter(o -> o.getOptionType() == OptionContract.OptionType.PUT)
                // ✅ No strike ceiling — delta range (0.45–0.60) selects ATM naturally
                .sorted(Comparator.comparingDouble(OptionContract::getStrikePrice).reversed())
                .collect(Collectors.toList());

        return findBestSpread(puts, false);
    }

    // ── Fix 4: Minimum peak profit gate ──────────────────────────────────────
    // Skip entries where max theoretical profit for the full position is below
    // ₹350. Such spreads have too little headroom to clear brokerage + slippage
    // (≈ ₹200 round-trip) and still deliver a meaningful reward-to-risk outcome.
    // Evaluated using config.lots × config.lotSize so it respects VIX-reduced qty;
    // thin markets where strikeWidth is tiny or IV has collapsed are filtered here.
    private static final double MIN_PEAK_PROFIT = 350.0;

    private Optional<SpreadLegs> findBestSpread(List<OptionContract> options,
                                                boolean isCallSpread) {
        SpreadLegs best = null;
        double bestScore = 0;

        // Find long leg candidates
        List<OptionContract> longCandidates = options.stream()
                .filter(o -> o.getAbsDelta() >= LONG_DELTA_MIN && o.getAbsDelta() <= LONG_DELTA_MAX)
                .collect(Collectors.toList());

        for (OptionContract longLeg : longCandidates) {
            // Short leg must be further OTM (higher strike for calls, lower for puts)
            List<OptionContract> shortCandidates = options.stream()
                    .filter(o -> isCallSpread
                            ? o.getStrikePrice() > longLeg.getStrikePrice()
                            : o.getStrikePrice() < longLeg.getStrikePrice())
                    .filter(o -> o.getAbsDelta() >= SHORT_DELTA_MIN && o.getAbsDelta() <= SHORT_DELTA_MAX)
                    .collect(Collectors.toList());

            for (OptionContract shortLeg : shortCandidates) {
                SpreadLegs legs = new SpreadLegs(longLeg, shortLeg);

                // Validate R:R and debit ratio
                if (legs.netDebit <= 0) continue;
                if (legs.netDebit / legs.strikeWidth > MAX_DEBIT_RATIO) continue;
                if (legs.rrRatio < MIN_RR_RATIO) continue;

                // Fix 4: ADX peak-profit gate — skip spreads too thin to be worthwhile.
                // maxProfitPer × baseQty gives the ₹ ceiling for this specific spread;
                // if it cannot reach MIN_PEAK_PROFIT (₹350) there is no real R:R edge
                // once brokerage (≈₹200 round-trip) is deducted.
                int baseQty = config.lots * config.lotSize;
                double maxProfitAmount = (legs.strikeWidth - legs.netDebit) * baseQty;
                if (maxProfitAmount < MIN_PEAK_PROFIT) {
                    Logger.debug(String.format(
                            "  [SKIP] Peak profit ₹%.0f < ₹%.0f gate (width=%.0f debit=%.2f qty=%d).",
                            maxProfitAmount, MIN_PEAK_PROFIT,
                            legs.strikeWidth, legs.netDebit, baseQty));
                    continue;
                }

                // Score: prefer delta closest to ideal midpoints + best R:R
                double score = legs.rrRatio * 10
                        - Math.abs(longLeg.getAbsDelta()  - 0.50) * 20
                        - Math.abs(shortLeg.getAbsDelta() - 0.27) * 20;

                if (score > bestScore) {
                    bestScore = score;
                    best = legs;
                }
            }
        }

        if (best != null) {
            Logger.info("[OK] Spread selected: " + best);
        } else {
            Logger.info("❌ No spread met entry criteria (min R:R=" + MIN_RR_RATIO +
                    ", maxDebitRatio=" + MAX_DEBIT_RATIO +
                    ", minPeakProfit=₹" + (int) MIN_PEAK_PROFIT + ")");
        }

        return Optional.ofNullable(best);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Order Execution
    // ════════════════════════════════════════════════════════════════════════

    // State persisted across ticks for an active position
    private Bias   entryBias     = null;
    private int    reversalCount = 0;

    private void enterSpread(SpreadLegs legs, DebitSpreadPosition.Direction direction,
                             boolean reducedQty) throws IOException {
        // Rec #1: halve quantity when VIX in reduced zone
        int baseQty    = config.lots * config.lotSize;
        int effectiveQty = reducedQty ? Math.max(baseQty / REDUCED_QTY_DIVISOR, config.lotSize) : baseQty;

        Logger.info(String.format("▶ Entering %s | Qty=%d%s",
                direction, effectiveQty, reducedQty ? " (REDUCED — high VIX)" : ""));
        Logger.info("  BUY  long leg:  " + legs.longLeg.getSymbol()  + " @ ₹" + legs.longLeg.getLtp());
        Logger.info("  SELL short leg: " + legs.shortLeg.getSymbol() + " @ ₹" + legs.shortLeg.getLtp());

        TradeLeg longFill  = executor.buyOption(legs.longLeg);
        TradeLeg shortFill = executor.sellOption(legs.shortLeg);

        double actualDebit = longFill.getEntryPrice() - shortFill.getEntryPrice();

        peakPnl       = 0.0;
        trailFloor    = Double.NEGATIVE_INFINITY;
        holdTickCount = 0;
        reversalCount = 0;
        pbBullTicks   = 0;
        pbBearTicks   = 0;
        entryBias     = direction == DebitSpreadPosition.Direction.BULL_CALL_SPREAD ? Bias.BULLISH : Bias.BEARISH;

        spreadPosition = new DebitSpreadPosition(
                direction,
                legs.longLeg, legs.shortLeg,
                longFill.getEntryPrice(), shortFill.getEntryPrice(),
                effectiveQty,
                LocalDateTime.now()
        );

        marketData.startTickerFor(Arrays.asList(legs.longLeg, legs.shortLeg));

        Logger.info(String.format(
                "[OK] %s ENTERED | Long=%.0f@₹%.2f | Short=%.0f@₹%.2f | NetDebit=₹%.2f | " +
                        "MaxProfit=₹%.2f | MaxLoss=₹%.2f | Trail arms at %.0f%% of max (₹%.0f)",
                direction,
                legs.longLeg.getStrikePrice(),  longFill.getEntryPrice(),
                legs.shortLeg.getStrikePrice(), shortFill.getEntryPrice(),
                actualDebit * effectiveQty,
                spreadPosition.getMaxProfitAmount(),
                spreadPosition.getMaxLossAmount(),
                TRAIL_STEPS_PCT[0][0],
                spreadPosition.getMaxProfitAmount() * TRAIL_STEPS_PCT[0][0] / 100.0
        ));
    }

    private void logMonitorStatus(double pnl, double profitPct,
                                  double exitFloor, double longLtp, double shortLtp) {
        String trend     = pnl >= 0 ? "▲" : "▼";
        double armAt     = spreadPosition.getMaxProfitAmount() * TRAIL_STEPS_PCT[0][0] / 100.0;
        String trailInfo = trailFloor == Double.NEGATIVE_INFINITY
                ? String.format("Trail=ARMED@₹%.0f (need ₹%.0f more, hold=%d/%d)",
                armAt, Math.max(armAt - peakPnl, 0), holdTickCount, MIN_HOLD_TICKS)
                : String.format("Trail=LOCKED | Floor=₹%.0f | Peak=₹%.0f",
                trailFloor, peakPnl);

        Logger.info(String.format(
                "📊 %s %s | LTP Long=₹%.2f Short=₹%.2f | PnL=₹%.2f (%+.0f%%) | Floor=₹%.0f | %s | Rev=%d/2",
                trend, spreadPosition.direction,
                longLtp, shortLtp, pnl, profitPct,
                exitFloor, trailInfo, reversalCount));
    }

    // ── Trailing stop state — dynamic % of max-profit ladder (FIX 04) ───────
    //
    // OLD: fixed ₹ triggers (₹200, ₹500, ₹1000, ₹1200).
    //   For a 250-pt-wide bear put (max profit ₹12116), ₹200 trigger was only 1.6%
    //   of max profit — trail fired on noise, exiting Trade 1 at ₹0 instead of ₹908.
    //
    // NEW: triggers expressed as % of getMaxProfitAmount() (computed at entry).
    //   This scales correctly regardless of spread width, IV level, or lot size.
    //
    //   { trigger_pct, floor_pct }   (percentages of maxProfitAmount)
    //   e.g. for ₹12116 max:  15% = ₹1817 trigger | 0% = ₹0 floor (break-even)
    //                          35% = ₹4241 trigger | 20% = ₹2423 floor
    //                          60% = ₹7270 trigger | 50% = ₹6058 floor
    //                          75% = ₹9087 trigger | 65% = ₹7875 floor
    //
    // FIX 04b: MIN_HOLD_TICKS — trail cannot fire for the first 3 ticks after entry
    //   (~15 min on 5-min candles). Many spreads need settling time before the trend
    //   unfolds; the ₹200 trigger was firing during initial noise on June 3 Trade 1.
    private static final double[][] TRAIL_STEPS_PCT = {
            { 15.0,  0.0 },   // crossed 15% of max → floor = 0%  (break-even protected)
            { 35.0, 20.0 },   // crossed 35% of max → floor = 20%
            { 60.0, 50.0 },   // crossed 60% of max → floor = 50%
            { 75.0, 65.0 },   // crossed 75% of max → floor = 65%
    };
    private static final double MAX_PROFIT_CAP = 2000.0;  // exit immediately at ₹1500
    private static final int    MIN_HOLD_TICKS = 3;       // trail cannot fire before this many monitor ticks

    private double peakPnl      = 0.0;
    private double trailFloor   = Double.NEGATIVE_INFINITY;  // -∞ = trail not armed
    private int    holdTickCount = 0;  // incremented each monitorAndExit tick; trail blocked until MIN_HOLD_TICKS

    // ── IN_TRADE — Monitor and exit ───────────────────────────────────────────

    private boolean monitorAndExit(LocalTime now,
                                   double[] closes, double[] highs,
                                   double[] lows, double percentB,
                                   double adx) throws IOException, KiteException {
        // Fetch live LTPs
        double longLtp  = marketData.fetchLtp(spreadPosition.longLeg);
        double shortLtp = marketData.fetchLtp(spreadPosition.shortLeg);
        spreadPosition.updateLtps(longLtp, shortLtp);

        double pnl       = spreadPosition.getUnrealisedPnl();
        double profitPct = spreadPosition.getProfitPct();

        // ── Emergency exit: VIX spike while in trade ──────────────────────────
        // If VIX spikes to VIX_EMERGENCY_EXIT (28+) after entry, IV expansion
        // rapidly inflates both legs — hurting the long leg disproportionately
        // (vega × premium). Exit immediately to prevent catastrophic IV loss.
        double liveVix = marketData.fetchSnapshot().getIndiaVix();
        if (liveVix >= VIX_EMERGENCY_EXIT) {
            Logger.warn(String.format(
                    "🚨 VIX SPIKE EXIT | VIX=%.2f ≥ %.0f — exiting to cap IV loss. PnL=₹%.2f",
                    liveVix, VIX_EMERGENCY_EXIT, pnl));
            exitSpread(DebitSpreadPosition.ExitReason.STOP_LOSS, now);
            return true;
        }

        // ── FIX 04: MIN_HOLD_TICKS guard — trail cannot fire in first N ticks ──
        holdTickCount++;

        // ── Ratchet trail floor up when new PnL peak hit ──────────────────────
        // FIX 04: triggers are now % of maxProfitAmount (not fixed ₹ values).
        // Also retains the 40% dynamic floor from Fix 2, which now supplements the
        // %-based step table the same way it did the old ₹ table.
//        if (pnl > peakPnl) {
//            peakPnl = pnl;
//            double maxProfit     = spreadPosition.getMaxProfitAmount();
//            double dynamicFloor  = peakPnl * 0.40;   // 40% of peak always preserved
//            for (double[] step : TRAIL_STEPS_PCT) {
//                double triggerAmt = maxProfit * step[0] / 100.0;
//                double floorAmt   = maxProfit * step[1] / 100.0;
//                if (peakPnl >= triggerAmt) {
//                    double candidate = Math.max(floorAmt, dynamicFloor);
//                    if (candidate > trailFloor) {
//                        double prev = trailFloor == Double.NEGATIVE_INFINITY ? 0 : trailFloor;
//                        trailFloor  = candidate;
//                        Logger.info(String.format(
//                                "[TREND] Trail ratchet | Peak=₹%.0f crossed %.0f%%(₹%.0f) → step=₹%.0f | dyn40%%=₹%.0f → floor ₹%.0f → ₹%.0f",
//                                peakPnl, step[0], triggerAmt, floorAmt, dynamicFloor, prev, trailFloor));
//                    }
//                }
//            }
//            // Dynamic floor supplements the step table once trail is armed
//            if (trailFloor != Double.NEGATIVE_INFINITY && dynamicFloor > trailFloor) {
//                Logger.info(String.format(
//                        "[TREND] Trail dyn40%% raised floor | Peak=₹%.0f | dyn=₹%.0f > step floor=₹%.0f",
//                        peakPnl, dynamicFloor, trailFloor));
//                trailFloor = dynamicFloor;
//            }
//        }

        // Hard SL = max of: 50% of max loss OR fixed -₹600 cap (Fix 3: was -₹650)
        double halfMaxLoss = -(spreadPosition.getMaxLossAmount() * (STOP_LOSS_PCT / 100.0));
        double hardSl      = Math.max(halfMaxLoss, -600.0);

        // Effective exit floor = highest of hard SL and trail floor
        double exitFloor = trailFloor == Double.NEGATIVE_INFINITY
                ? hardSl
                : Math.max(hardSl, trailFloor);

        logMonitorStatus(pnl, profitPct, exitFloor, longLtp, shortLtp);

        // ── 1. Force exit ─────────────────────────────────────────────────────
        if (now.isAfter(FORCE_EXIT_TIME)) {
            exitSpread(DebitSpreadPosition.ExitReason.FORCE_EXIT, now);
            return true;
        }

        // ── 2. Max profit cap (₹1500) ─────────────────────────────────────────
        if (pnl >= MAX_PROFIT_CAP) {
            Logger.info(String.format("🎯 MAX CAP | PnL=₹%.2f ≥ ₹%.0f", pnl, MAX_PROFIT_CAP));
            exitSpread(DebitSpreadPosition.ExitReason.TARGET_PROFIT, now);
            return true;
        }

        // ── 3. Target profit (80% of theoretical max) ─────────────────────────
        if (profitPct >= PROFIT_TARGET_PCT) {
            Logger.info(String.format("🎯 SPREAD TARGET | PnL=₹%.2f | %.0f%% of max", pnl, profitPct));
            exitSpread(DebitSpreadPosition.ExitReason.TARGET_PROFIT, now);
            return true;
        }

        // ── 4. Trend reversal check ───────────────────────────────────────────
        if (closes.length >= 21) {
            DirectionSignal currentDir = detectDirection(closes, highs, lows, percentB, adx);
            boolean reversed = currentDir.bias != entryBias
                    && currentDir.confidence >= 75;

            if (reversed) {
                reversalCount++;
                Logger.warn(String.format(
                        "🔄 Reversal signal #%d/2 | Was=%s | Now=%s (conf=%d%%) | PnL=₹%.2f",
                        reversalCount, entryBias, currentDir.bias, currentDir.confidence, pnl));
                if (reversalCount >= 2) {
                    Logger.warn("🔄 TREND REVERSAL CONFIRMED — exiting.");
                    exitSpread(DebitSpreadPosition.ExitReason.TREND_REVERSAL, now);
                    return true;
                }
            } else if (reversalCount > 0) {
                Logger.debug("Reversal counter reset.");
                reversalCount = 0;
            }
        }

        // ── 5. Trailing stop / hard SL ────────────────────────────────────────
        // FIX 04b: trail cannot fire before MIN_HOLD_TICKS ticks have elapsed.
        // Hard SL (-₹600 / 50%) can still fire immediately to cap catastrophic losses.
        boolean trailCanFire = (holdTickCount >= MIN_HOLD_TICKS);
        boolean trailTripped = (trailFloor != Double.NEGATIVE_INFINITY) && pnl <= trailFloor;
        boolean hardSlTripped = pnl <= hardSl;

        if (hardSlTripped || (trailCanFire && trailTripped)) {
            if (!hardSlTripped && trailTripped) {
                Logger.warn(String.format(
                        "🛡 TRAIL STOP | PnL=₹%.2f ≤ floor=₹%.0f | Peak=₹%.0f | Brokerage protected.",
                        pnl, trailFloor, peakPnl));
            } else {
                Logger.warn(String.format(
                        "🛑 STOP-LOSS | PnL=₹%.2f ≤ floor=₹%.0f", pnl, exitFloor));
            }
            exitSpread(DebitSpreadPosition.ExitReason.STOP_LOSS, now);
            return true;
        }
        if (!trailCanFire && trailTripped) {
            Logger.debug(String.format(
                    "[HOLD] Trail floor=₹%.0f breached but MIN_HOLD_TICKS not met (%d/%d) — holding.",
                    trailFloor, holdTickCount, MIN_HOLD_TICKS));
        }

        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Order Execution + Session Reporting
    // ════════════════════════════════════════════════════════════════════════

    private void exitSpread(DebitSpreadPosition.ExitReason reason) throws IOException, KiteException {
        exitSpread(reason, null);
    }

    /**
     * @param tickTime  the market LocalTime of the current tick — used as the
     *                  cooldown anchor so replay (which calls LocalDateTime.now()
     *                  at wall-clock time, not market time) produces correct cooldowns.
     *                  Pass null to fall back to wall-clock (live mode only).
     */
    private void exitSpread(DebitSpreadPosition.ExitReason reason,
                            LocalTime tickTime) throws IOException, KiteException {
        Logger.info("◀ Exiting spread. Reason: " + reason);
        marketData.stopTicker();

        // FIX #5: Fetch live LTPs at exit time for accurate fill prices.
        // Previously, TradeLeg was constructed with spreadPosition.longEntryPrice /
        // shortEntryPrice — the ENTRY price — so executor.sellBack/buyBack handed
        // that same entry price back to markExited as the exit fill.
        // Result: realisedPnl = (entryPrice - entryPrice) × qty = ₹0, or the
        // position's internally tracked currentLtp was used giving wrong sign.
        // Fix: fetch fresh LTPs now so the exit TradeLeg carries the real exit price.
        double longExitLtp  = marketData.fetchLtp(spreadPosition.longLeg);
        double shortExitLtp = marketData.fetchLtp(spreadPosition.shortLeg);

        // SELL back the long leg (close BUY position) — uses sellBack to post a SELL order
        double longExitFill  = executor.sellBack(
                new TradeLeg(spreadPosition.longLeg, TradeLeg.Side.BUY,
                        spreadPosition.quantity, longExitLtp, LocalDateTime.now()));

        // BUY back the short leg (close SELL position) — uses buyBack to post a BUY order
        double shortExitFill = executor.buyBack(
                new TradeLeg(spreadPosition.shortLeg, TradeLeg.Side.SELL,
                        spreadPosition.quantity, shortExitLtp, LocalDateTime.now()));

        spreadPosition.markExited(longExitFill, shortExitFill, reason, LocalDateTime.now());

        // Archive completed position
        allPositions.add(spreadPosition);

        // Mark day ended only on FORCE_EXIT — all other exits allow re-entry
        if (reason == DebitSpreadPosition.ExitReason.FORCE_EXIT) {
            dayEnded = true;
        } else {
            // Fix (replay bug 1): use the tick's market time as the cooldown anchor.
            // In live mode tickTime == null → fall back to wall clock (same as before).
            // In replay mode LocalDateTime.now() is wall-clock (e.g. 22:02), so without
            // this fix the cooldown would arm at 22:02 and block every remaining tick
            // whose market LocalTime is between 09:00–15:30 (all of them → 0 re-entries).
            lastExitTime = (tickTime != null) ? tickTime : LocalDateTime.now().toLocalTime();
            Logger.info(String.format("[COOLDOWN] Exit recorded at %s — re-entry blocked until %s.",
                    lastExitTime, lastExitTime.plusMinutes(COOLDOWN_MINUTES)));
        }

        // Log trade result + running session total
        double tradePnl   = spreadPosition.getRealisedPnl();
        double sessionPnl = getTotalSessionPnl();
        int    tradeNum   = allPositions.size();

        Logger.info(String.format(
                "[SUMMARY] TRADE #%d EXIT | Reason=%-15s | Trade PnL=₹%.2f | Session Total=₹%.2f | Trades=%d",
                tradeNum, reason, tradePnl, sessionPnl, tradeNum));
        Logger.info(spreadPosition.toString());

        // Allow re-entry by resetting active position reference (not the archive)
        // Next onTick() call will enter tryScanAndEnter() for a new trade
        if (reason != DebitSpreadPosition.ExitReason.FORCE_EXIT) {
            spreadPosition = null;  // cleared → re-entry on next TRENDING tick
            Logger.info("🔄 Position cleared — ready for next spread entry.");
        }

        printSessionSummary();
    }

    /** Prints a running summary of all trades taken this session */
    public void printSessionSummary() {
        if (allPositions.isEmpty()) return;

        Logger.info("═══════════════════════════════════════════");
        Logger.info("     SPREAD SESSION SUMMARY");
        Logger.info("═══════════════════════════════════════════");

        int wins   = 0, losses = 0;
        double totalPnl = 0;

        for (int i = 0; i < allPositions.size(); i++) {
            DebitSpreadPosition p = allPositions.get(i);
            double pnl = p.isExited() ? p.getRealisedPnl() : p.getUnrealisedPnl();
            totalPnl += pnl;
            if (pnl > 0) wins++; else if (pnl < 0) losses++;

            Logger.info(String.format(
                    "  Trade #%d | %s | %-16s | %s@₹%.0f→%.0f | PnL=₹%.2f",
                    i + 1,
                    p.direction,
                    p.getExitReason(),
                    p.direction == DebitSpreadPosition.Direction.BULL_CALL_SPREAD ? "CE" : "PE",
                    p.longLeg.getStrikePrice(),
                    p.shortLeg.getStrikePrice(),
                    pnl));
        }

        Logger.info("───────────────────────────────────────────");
        Logger.info(String.format(
                "  Total : %d trades | %d wins | %d losses | Win rate=%.0f%%",
                allPositions.size(), wins, losses,
                allPositions.isEmpty() ? 0 : (wins * 100.0 / allPositions.size())));
        Logger.info(String.format(
                "  Session P&L: ₹%.2f", totalPnl));
        Logger.info("═══════════════════════════════════════════");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Technical Indicators
    // ════════════════════════════════════════════════════════════════════════

    private double computeEma(double[] closes, int period) {
        int n = closes.length;
        if (n < period) return closes[n - 1];
        double k   = 2.0 / (period + 1);
        double ema = closes[n - period];
        for (int i = n - period + 1; i < n; i++) ema = closes[i] * k + ema * (1 - k);
        return ema;
    }

    /**
     * Computes +DI and -DI using Wilder's smoothed ATR.
     *
     * FIX #4: Replaced the raw TR summation with Wilder's exponential smoothing.
     * The old method summed raw True Range values → inflated ATR on volatile NIFTY days
     * → pDM/ATR and mDM/ATR were both compressed → +DI and -DI sat at artificially
     * low values (8–15 instead of 20–35), making the DI spread check in
     * countBreakoutConfirmations (diSpread > 5) fire too easily with no real edge,
     * and the DI weight (30 pts) in detectDirection unreliable.
     * Wilder smoothing produces canonical DI values that match charting platforms.
     *
     * @return double[2]: { +DI, -DI }
     */
    private double[] computeDiLines(double[] highs, double[] lows,
                                    double[] closes, int period) {
        int n = closes.length;
        if (n < period + 1) return new double[]{50, 50};

        // Warm-up window: use 3× the period for Wilder smoothing convergence,
        // but never go before index 1 (we need i-1 for TR).
        int warmupStart = Math.max(1, n - period * 3);

        // Seed with first bar in the warm-up window
        double tr0   = highs[warmupStart] - lows[warmupStart];
        double atr   = tr0;
        double pDM   = Math.max(highs[warmupStart] - highs[warmupStart - 1], 0);
        double mDM   = Math.max(lows[warmupStart - 1] - lows[warmupStart], 0);

        for (int i = warmupStart + 1; i < n; i++) {
            double tr    = Math.max(highs[i] - lows[i],
                    Math.max(Math.abs(highs[i] - closes[i - 1]),
                            Math.abs(lows[i]  - closes[i - 1])));
            double hDiff = highs[i] - highs[i - 1];
            double lDiff = lows[i - 1] - lows[i];
            double pmDM  = (hDiff > lDiff && hDiff > 0) ? hDiff : 0;
            double mmDM  = (lDiff > hDiff && lDiff > 0) ? lDiff : 0;

            // Wilder's smoothing: current = prev - (prev / period) + current_raw
            atr = atr - (atr / period) + tr;
            pDM = pDM - (pDM / period) + pmDM;
            mDM = mDM - (mDM / period) + mmDM;
        }

        return new double[]{
                atr > 0 ? pDM / atr * 100 : 0,
                atr > 0 ? mDM / atr * 100 : 0
        };
    }

    /**
     * Bollinger Band Width as % of the middle band (SMA).
     * BBW = (upperBand - lowerBand) / middleBand × 100
     * Used to detect compression (squeeze) state.
     */
    private double computeBbWidth(double[] closes, int period) {
        int n = closes.length;
        if (n < period) return 2.0; // default non-squeeze width
        double sum = 0;
        for (int i = n - period; i < n; i++) sum += closes[i];
        double sma = sum / period;
        double variance = 0;
        for (int i = n - period; i < n; i++) variance += (closes[i] - sma) * (closes[i] - sma);
        double stdDev = Math.sqrt(variance / period);
        double upper  = sma + 2 * stdDev;
        double lower  = sma - 2 * stdDev;
        return sma > 0 ? (upper - lower) / sma * 100 : 2.0;
    }

    /**
     * EMA slope — rate of change of EMA per candle as % of price.
     * Measures directional acceleration of the trend.
     * Strong slope (> 0.08%/candle) = trend has real momentum.
     * Flat slope = drift, not a real trend — avoid spread entry.
     */
    private double computeEmaSlope(double[] closes, int period) {
        int n = closes.length;
        if (n < period + 5) return 0;
        double k = 2.0 / (period + 1);

        // Seed EMA from closes[0] and warm up over the full array for accuracy.
        // Using a mid-array cold seed causes EMA to converge too slowly → slope≈0.
        double ema = closes[0];
        for (int i = 1; i < n; i++) ema = closes[i] * k + ema * (1 - k);
        double emaNow = ema;

        // Recompute EMA stopping 5 candles before end to get emaPrev
        ema = closes[0];
        for (int i = 1; i < n - 5; i++) ema = closes[i] * k + ema * (1 - k);
        double emaPrev = ema;

        double last = closes[n - 1];
        return last > 0 ? (emaNow - emaPrev) / last * 100 / 5 : 0; // signed %/candle
    }
}