package debitspread;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;

import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * TrendConfirmationDetector
 *
 * Replaces SidewaysMarketDetector for the debit-spread-only engine.
 *
 * Purpose is the OPPOSITE of the sideways detector:
 *   SidewaysMarketDetector → blocks entry when trending  (strangle logic)
 *   TrendConfirmationDetector → confirms entry when trending (spread logic)
 *
 * ── What it measures ──────────────────────────────────────────────────────
 *
 *   1. ADX  — trend strength. ADX >= 18 = developing trend.
 *   2. EMA slope — directional momentum. Signed: +ve = bullish, -ve = bearish.
 *   3. %B   — price position in Bollinger Band. > 0.6 = bullish bias, < 0.4 = bearish.
 *   4. BB Width — squeeze detection. BBW < 0.55% = compression, watch for breakout.
 *
 * ── Verdict ───────────────────────────────────────────────────────────────
 *
 *   TRENDING   → pass to DebitSpreadEngine for entry evaluation
 *   COMPRESSING → squeeze detected, wait for breakout (engine handles breakout logic)
 *   FLAT       → no signal, wait
 *
 * ── Usage ─────────────────────────────────────────────────────────────────
 *
 *   TrendConfirmationDetector detector = new TrendConfirmationDetector(kite);
 *   detector.initialize();                        // seed with today's 5-min candles
 *   detector.addTick(ltp, timestamp);             // called per polling tick
 *   TrendResult result = detector.analyse();
 *   if (result.isTrending() || result.isCompressing()) {
 *       spreadEngine.onTick(snapshot, result.closes, result.highs, result.lows,
 *                           result.percentB, result.adx, now);
 *   }
 */
public class TrendConfirmationDetector {

    private static final long   NIFTY_TOKEN   = 256265L;
    private static final ZoneId IST           = ZoneId.of("Asia/Kolkata");
    private static final int    CANDLE_PERIOD = 5;   // 5-minute candles
    private static final int    WINDOW_SIZE   = 40;  // rolling window (ADX needs 29+)
    private static final int    ADX_PERIOD    = 14;

    // ── Thresholds ────────────────────────────────────────────────────────────
    private static final double ADX_TREND_MIN      = 18.0;  // developing trend
    private static final double EMA_SLOPE_MIN      = 0.01;  // % per candle
    private static final double BB_COMPRESS_WIDTH  = 0.55;  // % — squeeze zone

    private final KiteConnect      kite;
    private final Deque<double[]>  candles = new ArrayDeque<>(); // [open,high,low,close]

    // ── Current live candle ───────────────────────────────────────────────────
    private double  curOpen   = 0, curHigh = 0, curLow = Double.MAX_VALUE, curClose = 0;
    private int     curBucket = -1;
    private boolean candleOpen = false;

    public TrendConfirmationDetector(KiteConnect kite) {
        this.kite = kite;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Init — seed with today's 5-min candles from Kite
    // ════════════════════════════════════════════════════════════════════════

    public void initialize() throws KiteException, IOException {
        LocalDate today = LocalDate.now(IST);
        Date from = Date.from(today.atTime(9, 15).atZone(IST).toInstant());
        Date to   = Date.from(LocalDateTime.now(IST).atZone(IST).toInstant());

        HistoricalData data = kite.getHistoricalData(
                from, to, String.valueOf(NIFTY_TOKEN), "5minute", false, false);

        if (data != null && data.dataArrayList != null) {
            for (HistoricalData c : data.dataArrayList) {
                addCandle(c.open, c.high, c.low, c.close);
            }
        }
        Logger.info(String.format(
                "[TrendDetector] Seeded with %d candles (need %d for reliable ADX).",
                candles.size(), ADX_PERIOD + 6));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tick ingestion — builds 5-min OHLC candles
    // ════════════════════════════════════════════════════════════════════════

    public void addTick(double ltp, LocalDateTime timestamp) {
        int minuteOfDay  = timestamp.getHour() * 60 + timestamp.getMinute();
        int bucket       = (minuteOfDay / CANDLE_PERIOD) * CANDLE_PERIOD;

        if (!candleOpen) {
            openCandle(ltp, bucket);
        } else if (bucket != curBucket) {
            addCandle(curOpen, curHigh, curLow, curClose);
            openCandle(ltp, bucket);
        } else {
            curHigh  = Math.max(curHigh, ltp);
            curLow   = Math.min(curLow,  ltp);
            curClose = ltp;
        }
    }

    private void openCandle(double ltp, int bucket) {
        curOpen = curHigh = curClose = ltp;
        curLow  = ltp;
        curBucket  = bucket;
        candleOpen = true;
    }

    private void addCandle(double o, double h, double l, double c) {
        candles.addLast(new double[]{o, h, l, c});
        while (candles.size() > WINDOW_SIZE) candles.pollFirst();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Analysis
    // ════════════════════════════════════════════════════════════════════════

    public TrendResult analyse() {
        List<double[]> window = new ArrayList<>(candles);

        if (window.size() < 10) {
            Logger.info(String.format("[TrendDetector] Warming up (%d/10 candles).", window.size()));
            return TrendResult.insufficient(window.size());
        }

        double[] closes = window.stream().mapToDouble(c -> c[3]).toArray();
        double[] highs  = window.stream().mapToDouble(c -> c[1]).toArray();
        double[] lows   = window.stream().mapToDouble(c -> c[2]).toArray();
        double   last   = closes[closes.length - 1];

        // ── Compute indicators ────────────────────────────────────────────────
        double adx      = computeAdx(highs, lows, closes);
        double[] bb     = computeBollingerBands(closes, 20, 2.0);
        double bbWidth  = bb[1] > 0 ? (bb[2] - bb[0]) / bb[1] * 100 : 2.0;
        double percentB = (bb[2] - bb[0]) > 0 ? (last - bb[0]) / (bb[2] - bb[0]) : 0.5;
        double emaSlope = computeEmaSlope(closes, 20);

        // ── Verdict ───────────────────────────────────────────────────────────
        // ADX(14) needs at least 15 candles for the first value, and ~20 for
        // Wilder smoothing to converge. 20 candles = 100 min after market open
        // (~11:00 AM) which is a safe warmup window without being too conservative.
        boolean adxReady    = window.size() >= ADX_PERIOD + 6; // 20 candles
        boolean adxTrend    = adxReady && adx >= ADX_TREND_MIN;

        // Slope needs period(20) + 5 lookahead = 25 candles minimum.
        boolean slopeReady  = closes.length >= 25;
        boolean slopeTrend  = slopeReady && Math.abs(emaSlope) >= EMA_SLOPE_MIN;

        boolean pbBias      = percentB > 0.60 || percentB < 0.40;

        // Compression is only meaningful once ADX is reliable.
        // BBW near 0 with <29 candles = all prices similar at open, not a real squeeze.
        boolean compressing = adxReady && bbWidth < BB_COMPRESS_WIDTH && bbWidth > 0.01;

        Verdict verdict;
        String  reason;

        if (!adxReady) {
            // Still in warmup — return FLAT silently, don't spam log
            verdict = Verdict.FLAT;
            reason  = String.format("Warmup (%d/%d candles for ADX)", window.size(), ADX_PERIOD + 6);
        } else if (adxTrend && (slopeTrend || pbBias)) {
            verdict = Verdict.TRENDING;
            reason  = String.format("ADX=%.1f + %s", adx,
                    slopeTrend ? String.format("slope=%.3f%%", emaSlope)
                            : String.format("%%B=%.2f", percentB));
        } else if (compressing) {
            verdict = Verdict.COMPRESSING;
            reason  = String.format("BBW=%.2f%% squeeze | ADX=%.1f", bbWidth, adx);
        } else if (slopeTrend || pbBias) {
            // Partial signal — pass to engine, let its internal gates decide
            verdict = Verdict.TRENDING;
            reason  = String.format("%s (ADX=%.1f weak)",
                    slopeTrend ? String.format("slope=%.3f%%", emaSlope)
                            : String.format("%%B=%.2f", percentB), adx);
        } else {
            verdict = Verdict.FLAT;
            reason  = String.format("ADX=%.1f | slope=%.3f%% | %%B=%.2f — no signal",
                    adx, emaSlope, percentB);
        }

        TrendResult result = new TrendResult(
                verdict, adx, bbWidth, percentB, emaSlope,
                closes, highs, lows, last, window.size(), reason);

        result.print();
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Indicators
    // ════════════════════════════════════════════════════════════════════════

    private double computeAdx(double[] highs, double[] lows, double[] closes) {
        int n = closes.length;
        if (n < ADX_PERIOD + 1) return 0;

        double[] tr = new double[n], pDM = new double[n], mDM = new double[n];
        for (int i = 1; i < n; i++) {
            double up   = highs[i]  - highs[i - 1];
            double down = lows[i - 1] - lows[i];
            tr[i]  = Math.max(highs[i] - lows[i],
                    Math.max(Math.abs(highs[i] - closes[i - 1]),
                            Math.abs(lows[i]  - closes[i - 1])));
            pDM[i] = (up > down && up > 0)     ? up   : 0;
            mDM[i] = (down > up && down > 0)   ? down : 0;
        }

        double atr = 0, pDI = 0, mDI = 0;
        for (int i = 1; i <= ADX_PERIOD; i++) { atr += tr[i]; pDI += pDM[i]; mDI += mDM[i]; }

        double adxSum = 0;
        for (int i = ADX_PERIOD + 1; i < n; i++) {
            atr = atr - atr / ADX_PERIOD + tr[i];
            pDI = pDI - pDI / ADX_PERIOD + pDM[i];
            mDI = mDI - mDI / ADX_PERIOD + mDM[i];
            double pPct = atr > 0 ? pDI / atr * 100 : 0;
            double mPct = atr > 0 ? mDI / atr * 100 : 0;
            double sum  = pPct + mPct;
            adxSum += sum > 0 ? Math.abs(pPct - mPct) / sum * 100 : 0;
        }
        int periods = n - ADX_PERIOD - 1;
        return periods > 0 ? adxSum / periods : 0;
    }

    private double[] computeBollingerBands(double[] closes, int period, double mult) {
        int n = closes.length;
        if (n < period) return new double[]{closes[n-1], closes[n-1], closes[n-1]};
        double sum = 0;
        for (int i = n - period; i < n; i++) sum += closes[i];
        double sma = sum / period;
        double var = 0;
        for (int i = n - period; i < n; i++) var += (closes[i] - sma) * (closes[i] - sma);
        double sd = Math.sqrt(var / period);
        return new double[]{sma - mult * sd, sma, sma + mult * sd};
    }

    private double computeEmaSlope(double[] closes, int period) {
        int n = closes.length;
        if (n < period + 5) return 0;
        double k = 2.0 / (period + 1);
        // Full-array warmup from closes[0] — avoids cold-seed slope=0 bug
        double emaNow = closes[0];
        for (int i = 1; i < n; i++)     emaNow = closes[i] * k + emaNow * (1 - k);
        double emaPrev = closes[0];
        for (int i = 1; i < n - 5; i++) emaPrev = closes[i] * k + emaPrev * (1 - k);
        double last = closes[n - 1];
        return last > 0 ? (emaNow - emaPrev) / last * 100 / 5 : 0; // signed %/candle
    }

    // ════════════════════════════════════════════════════════════════════════
    // Result
    // ════════════════════════════════════════════════════════════════════════

    public enum Verdict { TRENDING, COMPRESSING, FLAT }

    public static class TrendResult {
        public final Verdict  verdict;
        public final double   adx;
        public final double   bbWidth;
        public final double   percentB;
        public final double   emaSlope;
        public final double[] closes;
        public final double[] highs;
        public final double[] lows;
        public final double   lastPrice;
        public final int      candleCount;
        public final String   reason;

        TrendResult(Verdict verdict, double adx, double bbWidth, double percentB,
                    double emaSlope, double[] closes, double[] highs, double[] lows,
                    double lastPrice, int candleCount, String reason) {
            this.verdict     = verdict;
            this.adx         = adx;
            this.bbWidth     = bbWidth;
            this.percentB    = percentB;
            this.emaSlope    = emaSlope;
            this.closes      = closes;
            this.highs       = highs;
            this.lows        = lows;
            this.lastPrice   = lastPrice;
            this.candleCount = candleCount;
            this.reason      = reason;
        }

        static TrendResult insufficient(int count) {
            return new TrendResult(Verdict.FLAT, 0, 2.0, 0.5, 0,
                    new double[0], new double[0], new double[0], 0, count,
                    "Insufficient candles (" + count + "/15)");
        }

        public boolean isTrending()    { return verdict == Verdict.TRENDING; }
        public boolean isCompressing() { return verdict == Verdict.COMPRESSING; }
        public boolean isFlat()        { return verdict == Verdict.FLAT; }

        public void print() {
            // Warmup: suppress per-tick noise, log only every 5 candles
            if (reason.startsWith("Warmup")) {
                if (candleCount % 5 == 0) {
                    Logger.info(String.format("[DETECTOR] Warmup: %d/20 candles loaded.", candleCount));
                }
                return;
            }
            // Verdict + reason only — ZerodhaTradeEngine logs full indicator detail
            String icon = verdict == Verdict.TRENDING    ? "[TREND]"
                    : verdict == Verdict.COMPRESSING ? "[COMPRESS]"
                    : "[FLAT]";
            Logger.debug(String.format("%s %s", icon, reason));
        }
    }
}