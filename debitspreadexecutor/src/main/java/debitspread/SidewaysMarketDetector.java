package debitspread;


import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import org.json.JSONException;

import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * SidewaysMarketDetector
 *
 * Determines in real-time whether NIFTY is in a sideways (range-bound)
 * regime suitable for running the short strangle algo.
 *
 * ── Why sideways detection matters ────────────────────────────────────────
 *
 *   Short strangles profit from TIME DECAY (theta).
 *   They get hurt when the market TRENDS strongly in one direction —
 *   the sold strike gets breached and the losing leg grows faster
 *   than the winning leg decays.
 *
 *   A sideways market = low directional velocity + contained range
 *   = ideal conditions for short premium strategies.
 *
 * ── Indicators Used (all computed from live tick data) ────────────────────
 *
 *   1. ADX (Average Directional Index)
 *      The gold standard for measuring TREND STRENGTH (not direction).
 *      ADX < 20  → no trend / sideways    → ✅ trade
 *      ADX 20–25 → weak trend forming     → ⚠️  caution
 *      ADX > 25  → strong trend           → ❌ avoid
 *
 *   2. Bollinger Band Width (BBW)
 *      Measures how much price is ranging vs expanding.
 *      Narrow bands = low volatility / consolidation = sideways.
 *      BBW < 1.5% of price → very tight range     → ✅ ideal
 *      BBW 1.5–2.5%        → moderate range        → ✅ acceptable
 *      BBW > 2.5%          → wide / trending       → ❌ avoid
 *
 *   3. Price Position within Bollinger Bands (%B)
 *      Tells where price is within the band (0 = lower, 1 = upper).
 *      %B near 0.5 (middle) = price oscillating around mean = sideways.
 *      0.3–0.7 → hugging mean, sideways              → ✅
 *      < 0.2 or > 0.8 → pressing a band boundary     → ⚠️
 *
 *   4. Directional Bias (EMA slope)
 *      Rate of change of 20-period EMA.
 *      Flat EMA slope = no directional bias.
 *      Slope < 0.05% per candle → flat               → ✅
 *      Slope 0.05–0.15%        → mild trend           → ⚠️
 *      Slope > 0.15%           → strong trend         → ❌
 *
 *   5. High-Low Range Consistency
 *      Checks whether NIFTY's candle highs and lows are contained
 *      within a consistent band (not making higher highs / lower lows).
 *      Consistent H-L = range-bound = sideways.
 *
 * ── How ticks are used ────────────────────────────────────────────────────
 *
 *   The detector is fed 5-minute historical candles from Kite on init,
 *   then updated on every live tick via addTick().
 *   The last 20 completed 5-min candles are used for all calculations.
 *
 *   Tick → aggregated into current 5-min candle (OHLC) →
 *   when candle closes → appended to the rolling window →
 *   all indicators recomputed.
 *
 * ── Usage ─────────────────────────────────────────────────────────────────
 *
 *   SidewaysMarketDetector detector = new SidewaysMarketDetector(kite);
 *   detector.initialize();                        // load seed candles
 *   detector.addTick(lastTradedPrice, timestamp); // called per tick
 *
 *   SidewaysResult result = detector.analyse();
 *   if (result.isSideways()) → run the algo
 */
public class SidewaysMarketDetector {

    private static final long   NIFTY_TOKEN    = 256265L;
    private static final ZoneId IST            = ZoneId.of("Asia/Kolkata");
    private static final int    CANDLE_PERIOD  = 5;          // 5-minute candles
    private static final int    WINDOW_SIZE    = 20;         // rolling window for indicators
    private static final int    ADX_PERIOD     = 14;         // standard ADX period

    private final KiteConnect kite;

    // ── Rolling candle window ─────────────────────────────────────────────────
    // Stores completed 5-min OHLC candles (open, high, low, close)
    private final Deque<double[]> candles = new ArrayDeque<>(); // [open, high, low, close]

    // ── Current (live, incomplete) 5-min candle being built from ticks ────────
    private double  currentOpen      = 0;
    private double  currentHigh      = 0;
    private double  currentLow       = Double.MAX_VALUE;
    private double  currentClose     = 0;
    private int     currentMinute    = -1;  // minute-of-day bucket for current candle
    private boolean currentCandleOpen = false;

    public SidewaysMarketDetector(KiteConnect kite) {
        this.kite = kite;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Initialisation — seed with today's historical 5-min candles
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Loads today's 5-minute NIFTY candles from Kite as seed data.
     * Call once after market open (after 09:30 to get a few candles).
     */
    public void initialize() throws KiteException, IOException, JSONException {
        LocalDate today = LocalDate.now(IST);
        Date from = Date.from(today.atTime(9, 15).atZone(IST).toInstant());
        Date to   = Date.from(LocalDateTime.now(IST).atZone(IST).toInstant());

        HistoricalData data = kite.getHistoricalData(
                from, to, String.valueOf(NIFTY_TOKEN), "5minute", false, false
        );

        if (data != null && data.dataArrayList != null) {
            for (HistoricalData c : data.dataArrayList) {
                addCandle(c.open, c.high, c.low, c.close);
            }
            Logger.info(String.format(
                    "SidewaysDetector seeded with %d candles. Window=%d",
                    candles.size(), WINDOW_SIZE));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Live tick ingestion
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Feed every live NIFTY tick into the detector.
     * Aggregates ticks into 5-min OHLC candles.
     * When a candle closes (5-min boundary crossed), adds it to the window
     * and recomputes indicators on the next analyse() call.
     *
     * @param ltp       last traded price from the tick
     * @param timestamp tick timestamp (from KiteTicker)
     */
    public void addTick(double ltp, LocalDateTime timestamp) {
        // Compute which 5-min bucket this tick belongs to
        int minuteOfDay  = timestamp.getHour() * 60 + timestamp.getMinute();
        int candleBucket = (minuteOfDay / CANDLE_PERIOD) * CANDLE_PERIOD;

        if (!currentCandleOpen) {
            // First tick ever — open first candle
            openCandle(ltp, candleBucket);
        } else if (candleBucket != currentMinute) {
            // New 5-min bucket → close current candle, open new one
            commitCurrentCandle();
            openCandle(ltp, candleBucket);
        } else {
            // Same bucket — update current candle OHLC
            currentHigh  = Math.max(currentHigh, ltp);
            currentLow   = Math.min(currentLow,  ltp);
            currentClose = ltp;
        }
    }

    private void openCandle(double ltp, int bucket) {
        currentOpen       = ltp;
        currentHigh       = ltp;
        currentLow        = ltp;
        currentClose      = ltp;
        currentMinute     = bucket;
        currentCandleOpen = true;
    }

    private void commitCurrentCandle() {
        addCandle(currentOpen, currentHigh, currentLow, currentClose);
        Logger.debug(String.format("Candle closed | O=%.2f H=%.2f L=%.2f C=%.2f",
                currentOpen, currentHigh, currentLow, currentClose));
    }

    private void addCandle(double open, double high, double low, double close) {
        candles.addLast(new double[]{open, high, low, close});
        // Keep only the last WINDOW_SIZE + ADX_PERIOD candles (ADX needs more history)
        while (candles.size() > WINDOW_SIZE + ADX_PERIOD + 5) {
            candles.pollFirst();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Main analysis — call before deciding to enter a trade
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Computes all sideways indicators on the current candle window.
     * Returns a SidewaysResult with per-indicator readings and final verdict.
     *
     * Requires at least 15 completed candles for reliable results.
     */
    public SidewaysResult analyse() {
        List<double[]> window = new ArrayList<>(candles);

        if (window.size() < 15) {
            Logger.info("SidewaysDetector: insufficient candles (" + window.size() + "/15). Waiting...");
            return SidewaysResult.insufficient(window.size());
        }

        double[] closes = window.stream().mapToDouble(c -> c[3]).toArray();
        double[] highs  = window.stream().mapToDouble(c -> c[1]).toArray();
        double[] lows   = window.stream().mapToDouble(c -> c[2]).toArray();
        double   last   = closes[closes.length - 1];

        // ── Compute each indicator ────────────────────────────────────────────
        double adx                 = computeAdx(highs, lows, closes);
        double[] bb                = computeBollingerBands(closes, 20, 2.0);
        double bbWidth             = (bb[2] - bb[0]) / bb[1] * 100; // % of middle band
        double percentB            = bb[1] > 0 ? (last - bb[0]) / (bb[2] - bb[0]) : 0.5;
        double emaSlope            = computeEmaSlope(closes, 20);
        double rangeConsistency    = computeRangeConsistency(highs, lows, closes);

        // ── Hard pre-score guards — override everything ───────────────────────
        // These conditions are too dangerous for short strangles regardless of
        // what ADX or other indicators say.

        // Guard 1: %B outside [0, 1] = price outside Bollinger Bands = active breakout
        if (percentB < 0 || percentB > 1) {
            Logger.warn(String.format(
                    "🚫 HARD BLOCK: %%B=%.2f — price OUTSIDE Bollinger Bands. Breakdown/breakout active.",
                    percentB));
            return new SidewaysResult(SidewaysVerdict.TRENDING, 0,
                    adx, bbWidth, percentB, emaSlope, rangeConsistency, last, window.size(),
                    List.of(IndicatorResult.trending("%B", 100, true,
                            String.format("%%B=%.2f — price outside bands. HARD BLOCK.", percentB))));
        }

        // Guard 2: BB squeeze (BBW < 0.4%) + price pressing an edge = imminent breakout
        if (bbWidth < 0.4 && (percentB < 0.3 || percentB > 0.7)) {
            Logger.warn(String.format(
                    "🚫 HARD BLOCK: BBW=%.2f%% squeeze + %%B=%.2f pressing edge. Breakout imminent.",
                    bbWidth, percentB));
            return new SidewaysResult(SidewaysVerdict.TRENDING, 0,
                    adx, bbWidth, percentB, emaSlope, rangeConsistency, last, window.size(),
                    List.of(IndicatorResult.trending("BB Squeeze", 100, true,
                            String.format("BBW=%.2f%% squeeze pressing edge. HARD BLOCK.", bbWidth))));
        }

        // ── Score each indicator ──────────────────────────────────────────────
        IndicatorResult adxResult   = scoreAdx(adx);
        IndicatorResult bbwResult   = scoreBbWidth(bbWidth);
        IndicatorResult pbResult    = scorePercentB(percentB);
        IndicatorResult slopeResult = scoreEmaSlope(emaSlope);
        IndicatorResult rangeResult = scoreRangeConsistency(rangeConsistency);

        List<IndicatorResult> indicators = List.of(
                adxResult, bbwResult, pbResult, slopeResult, rangeResult
        );

        // ── Composite score ───────────────────────────────────────────────────
        int totalWeight  = indicators.stream().mapToInt(r -> r.weight).sum();
        int earnedWeight = indicators.stream()
                .mapToInt(r -> r.sideways ? r.weight : (r.caution ? r.weight / 2 : 0))
                .sum();
        int score = totalWeight > 0 ? earnedWeight * 100 / totalWeight : 0;

        // Any CRITICAL non-sideways indicator forces avoid
        boolean blocked = indicators.stream().anyMatch(r -> !r.sideways && !r.caution && r.critical);

        SidewaysVerdict verdict;
        if (blocked) {
            verdict = SidewaysVerdict.TRENDING;
        } else if (score >= 70) {
            verdict = SidewaysVerdict.SIDEWAYS;
        } else if (score >= 45) {
            verdict = SidewaysVerdict.BORDERLINE;
        } else {
            verdict = SidewaysVerdict.TRENDING;
        }

        SidewaysResult result = new SidewaysResult(
                verdict, score, adx, bbWidth, percentB, emaSlope, rangeConsistency,
                last, window.size(), indicators
        );

        result.print();
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Indicator computations
    // ════════════════════════════════════════════════════════════════════════

    /**
     * ADX — Average Directional Index (Wilder, 14-period).
     * Measures trend STRENGTH regardless of direction.
     * < 20 = no trend, > 25 = strong trend.
     */
    private double computeAdx(double[] highs, double[] lows, double[] closes) {
        int n = closes.length;
        if (n < ADX_PERIOD + 1) return 50; // default high (trending) if insufficient data

        double[] trueRange = new double[n];
        double[] plusDM    = new double[n];
        double[] minusDM   = new double[n];

        for (int i = 1; i < n; i++) {
            double highDiff  = highs[i]  - highs[i - 1];
            double lowDiff   = lows[i - 1] - lows[i];
            trueRange[i]  = Math.max(highs[i] - lows[i],
                    Math.max(Math.abs(highs[i] - closes[i - 1]),
                            Math.abs(lows[i]  - closes[i - 1])));
            plusDM[i]  = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
            minusDM[i] = (lowDiff > highDiff && lowDiff > 0)  ? lowDiff  : 0;
        }

        // Wilder smoothing
        double atr = 0, pDI = 0, mDI = 0;
        for (int i = 1; i <= ADX_PERIOD; i++) {
            atr += trueRange[i];
            pDI += plusDM[i];
            mDI += minusDM[i];
        }

        double adxSum = 0;
        for (int i = ADX_PERIOD + 1; i < n; i++) {
            atr = atr - atr / ADX_PERIOD + trueRange[i];
            pDI = pDI - pDI / ADX_PERIOD + plusDM[i];
            mDI = mDI - mDI / ADX_PERIOD + minusDM[i];

            double pDIpct = atr > 0 ? (pDI / atr) * 100 : 0;
            double mDIpct = atr > 0 ? (mDI / atr) * 100 : 0;
            double sum    = pDIpct + mDIpct;
            double dx     = sum > 0 ? Math.abs(pDIpct - mDIpct) / sum * 100 : 0;
            adxSum       += dx;
        }

        int periods = n - ADX_PERIOD - 1;
        return periods > 0 ? adxSum / periods : 50;
    }

    /**
     * Bollinger Bands — [lower, middle, upper].
     * Middle = SMA(period), Upper/Lower = Middle ± stdDev * multiplier.
     */
    private double[] computeBollingerBands(double[] closes, int period, double mult) {
        int n = closes.length;
        if (n < period) return new double[]{closes[n-1], closes[n-1], closes[n-1]};

        double sum = 0;
        for (int i = n - period; i < n; i++) sum += closes[i];
        double sma = sum / period;

        double variance = 0;
        for (int i = n - period; i < n; i++) {
            variance += (closes[i] - sma) * (closes[i] - sma);
        }
        double stdDev = Math.sqrt(variance / period);

        return new double[]{sma - mult * stdDev, sma, sma + mult * stdDev};
    }

    /**
     * EMA slope — rate of change of the 20-period EMA per candle, as % of price.
     * Flat EMA = no directional bias = sideways.
     */
    private double computeEmaSlope(double[] closes, int period) {
        int n = closes.length;
        if (n < period + 5) return 0;

        // Seed from closes[0] and warm up the FULL array for accurate EMA convergence.
        // Cold-seeding from a mid-array point makes emaNow ≈ emaPrev → slope ≈ 0 always.
        double k = 2.0 / (period + 1);

        double emaNow = closes[0];
        for (int i = 1; i < n; i++) emaNow = closes[i] * k + emaNow * (1 - k);

        double emaPrev = closes[0];
        for (int i = 1; i < n - 5; i++) emaPrev = closes[i] * k + emaPrev * (1 - k);

        double last = closes[n - 1];
        // Return SIGNED slope — positive = bullish EMA, negative = bearish EMA
        // scoreEmaSlope uses Math.abs so the sideways/trending classification is unaffected
        return last > 0 ? (emaNow - emaPrev) / last * 100 / 5 : 0; // signed %/candle
    }

    /**
     * Range Consistency — checks if recent highs and lows are contained
     * within a stable band (not making higher highs / lower lows).
     *
     * Returns a score 0–100: 100 = perfectly flat range, 0 = strongly trending.
     * Computed as 1 - (range expansion / average range).
     */
    private double computeRangeConsistency(double[] highs, double[] lows, double[] closes) {
        int n = Math.min(highs.length, WINDOW_SIZE);
        if (n < 5) return 50;

        int start = highs.length - n;

        // Average candle range
        double avgRange = 0;
        for (int i = start; i < highs.length; i++) {
            avgRange += highs[i] - lows[i];
        }
        avgRange /= n;

        // Range of the high/low band over the window
        double windowHigh = Arrays.stream(highs, start, highs.length).max().orElse(0);
        double windowLow  = Arrays.stream(lows,  start, lows.length).min().orElse(0);
        double windowSpan = windowHigh - windowLow;
        double lastClose  = closes[closes.length - 1];

        // Normalise: if window span < 2x average candle range, it's contained (sideways)
        double ratio = avgRange > 0 ? windowSpan / (avgRange * n) : 1;
        return Math.max(0, Math.min(100, (1 - ratio) * 100 + 50));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Scoring — each indicator → IndicatorResult
    // ════════════════════════════════════════════════════════════════════════

    private IndicatorResult scoreAdx(double adx) {
        // ADX is the most important — highest weight, critical flag
        if (adx < 20) return IndicatorResult.sideways("ADX", 35, true,
                String.format("ADX=%.1f < 20 — no trend, ideal for short strangle.", adx));
        if (adx < 25) return IndicatorResult.caution("ADX", 35, true,
                String.format("ADX=%.1f in 20–25 — weak trend forming. Caution.", adx));
        return IndicatorResult.trending("ADX", 35, true,
                String.format("ADX=%.1f > 25 — STRONG TREND. Do not trade.", adx));
    }

    private IndicatorResult scoreBbWidth(double bbw) {
        // Extremely tight bands (< 0.4%) = Bollinger Squeeze — coiled spring,
        // breakout imminent in either direction. NOT safe to sell premium.
        if (bbw < 0.4) return IndicatorResult.caution("BB Width", 20, false,
                String.format("BBW=%.2f%% — SQUEEZE warning. Breakout likely soon.", bbw));

        // Ideal zone: 0.4–2.0% = healthy range-bound consolidation
        if (bbw <= 2.0) return IndicatorResult.sideways("BB Width", 20, false,
                String.format("BBW=%.2f%% — healthy range. Premium selling ideal.", bbw));

        // Widening: 2.0–3.0% = bands expanding, possible trend developing
        if (bbw <= 3.0) return IndicatorResult.caution("BB Width", 20, false,
                String.format("BBW=%.2f%% — bands widening. Market may be trending.", bbw));

        // > 3% = wide bands = trending / volatile = avoid
        return IndicatorResult.trending("BB Width", 20, false,
                String.format("BBW=%.2f%% — wide bands. Trending / volatile.", bbw));
    }

    private IndicatorResult scorePercentB(double pB) {
        // %B < 0 or > 1 = price OUTSIDE bands = active breakdown/breakout → CRITICAL
        if (pB < 0 || pB > 1) return IndicatorResult.trending("%B", 15, true,
                String.format("%%B=%.2f — price OUTSIDE bands. Breakout/breakdown. BLOCK.", pB));

        // %B in [0, 0.3] = price near lower band = breakdown risk → CRITICAL BLOCK
        // %B in [0.7, 1] = price near upper band = breakout risk  → CRITICAL BLOCK
        // critical=true → forces TRENDING verdict regardless of composite score
        if (pB < 0.3 || pB > 0.7) return IndicatorResult.trending("%B", 15, true,
                String.format("%%B=%.2f — price pressing %s band. %s risk. BLOCK.",
                        pB, pB < 0.3 ? "lower" : "upper", pB < 0.3 ? "Breakdown" : "Breakout"));

        // %B in [0.3, 0.4] or [0.6, 0.7] = drifting toward edge = caution
        if (pB < 0.4 || pB > 0.6) return IndicatorResult.caution("%B", 15, false,
                String.format("%%B=%.2f — price drifting toward %s band.",
                        pB, pB < 0.4 ? "lower" : "upper"));

        // %B in [0.4, 0.6] = price oscillating tightly around midline = ideal
        return IndicatorResult.sideways("%B", 15, false,
                String.format("%%B=%.2f — price at BB midline. Ideal sideways.", pB));
    }

    private IndicatorResult scoreEmaSlope(double slope) {
        double abSlope = Math.abs(slope); // signed slope — use magnitude for sideways scoring
        if (abSlope < 0.05) return IndicatorResult.sideways("EMA Slope", 20, false,
                String.format("Slope=%.3f%%/candle — EMA flat. No directional bias.", slope));
        if (abSlope < 0.15) return IndicatorResult.caution("EMA Slope", 20, false,
                String.format("Slope=%.3f%%/candle — mild trend. Watch carefully.", slope));
        return IndicatorResult.trending("EMA Slope", 20, false,
                String.format("Slope=%.3f%%/candle — EMA trending strongly.", slope));
    }

    private IndicatorResult scoreRangeConsistency(double score) {
        if (score >= 65) return IndicatorResult.sideways("Range Consistency", 10, false,
                String.format("Score=%.0f — highs/lows contained. Stable range.", score));
        if (score >= 45) return IndicatorResult.caution("Range Consistency", 10, false,
                String.format("Score=%.0f — range expanding slightly.", score));
        return IndicatorResult.trending("Range Consistency", 10, false,
                String.format("Score=%.0f — range expanding. Market directional.", score));
    }

    /**
     * Returns the current candle window as three arrays [closes, highs, lows]
     * for use by DirectionDetector. Most recent candle is last in each array.
     */
    public double[][] getCandleArrays() {
        List<double[]> window = new ArrayList<>(candles);
        double[] closes = window.stream().mapToDouble(c -> c[3]).toArray();
        double[] highs  = window.stream().mapToDouble(c -> c[1]).toArray();
        double[] lows   = window.stream().mapToDouble(c -> c[2]).toArray();
        return new double[][]{closes, highs, lows};
    }

    // ════════════════════════════════════════════════════════════════════════
    // Result Models
    // ════════════════════════════════════════════════════════════════════════

    public enum SidewaysVerdict { SIDEWAYS, BORDERLINE, TRENDING }

    public static class IndicatorResult {
        public final String  name;
        public final int     weight;
        public final boolean sideways;   // fully green
        public final boolean caution;    // amber — partial score
        public final boolean critical;   // if trending, blocks entry
        public final String  reason;

        private IndicatorResult(String name, int weight, boolean sideways,
                                boolean caution, boolean critical, String reason) {
            this.name     = name;
            this.weight   = weight;
            this.sideways = sideways;
            this.caution  = caution;
            this.critical = critical;
            this.reason   = reason;
        }

        static IndicatorResult sideways(String n, int w, boolean c, String r) {
            return new IndicatorResult(n, w, true,  false, c, r);
        }
        static IndicatorResult caution(String n, int w, boolean c, String r) {
            return new IndicatorResult(n, w, false, true,  c, r);
        }
        static IndicatorResult trending(String n, int w, boolean c, String r) {
            return new IndicatorResult(n, w, false, false, c, r);
        }

        public String statusIcon() {
            if (sideways) return "✅";
            if (caution)  return "⚠️ ";
            return critical ? "🚫" : "❌";
        }
    }

    public static class SidewaysResult {
        public final SidewaysVerdict      verdict;
        public final int                  score;
        public final double               adx;
        public final double               bbWidth;
        public final double               percentB;
        public final double               emaSlope;
        public final double               rangeConsistency;
        public final double               lastPrice;
        public final int                  candleCount;
        public final List<IndicatorResult> indicators;
        public final boolean              sufficient;

        // Full result constructor
        SidewaysResult(SidewaysVerdict verdict, int score, double adx, double bbWidth,
                       double percentB, double emaSlope, double rangeConsistency,
                       double lastPrice, int candleCount, List<IndicatorResult> indicators) {
            this.verdict          = verdict;
            this.score            = score;
            this.adx              = adx;
            this.bbWidth          = bbWidth;
            this.percentB         = percentB;
            this.emaSlope         = emaSlope;
            this.rangeConsistency = rangeConsistency;
            this.lastPrice        = lastPrice;
            this.candleCount      = candleCount;
            this.indicators       = indicators;
            this.sufficient       = true;
        }

        // Insufficient data constructor
        static SidewaysResult insufficient(int count) {
            return new SidewaysResult(SidewaysVerdict.BORDERLINE, 0, 0, 0, 0.5, 0, 50,
                    0, count, List.of()) {
                @Override public boolean isSideways() { return false; }
            };
        }

        public boolean isSideways()    { return verdict == SidewaysVerdict.SIDEWAYS; }
        public boolean isBorderline()  { return verdict == SidewaysVerdict.BORDERLINE; }
        public boolean isTrending()    { return verdict == SidewaysVerdict.TRENDING; }

        /**
         * True when ADX and %B indicate a REAL directional trend suitable for debit spreads.
         * Intentionally stricter than isTrending() which can fire on %B alone (breakout risk).
         *
         * DebitSpreadEngine has its own internal gates (ADX range, slope, %B alignment)
         * so this just ensures we don't completely suppress spread engine calls in
         * sideways markets while still gating on the minimum trend signal.
         *
         * Use this in ZerodhaTradeEngine.handleScanning() instead of isTrending().
         */
        public boolean isDirectionalForSpread() {
            // ADX >= 18 = developing trend (spread engine will further validate)
            // EMA slope magnitude >= 0.01 = some directional momentum
            return adx >= 18.0 || Math.abs(emaSlope) >= 0.01;
        }

        public void print() {
            System.out.println();
            System.out.printf("┌─────────────────────────────────────────────────────┐%n");
            System.out.printf("│  SIDEWAYS DETECTOR │ NIFTY=%-8.2f │ Score=%d/100  │%n",
                    lastPrice, score);
            System.out.printf("│  Candles=%-3d       │ Verdict: %-22s │%n",
                    candleCount, verdict);
            System.out.printf("├──────────────────────┬──────┬───────────────────────┤%n");
            System.out.printf("│ Indicator            │  Wt  │ Reading               │%n");
            System.out.printf("├──────────────────────┼──────┼───────────────────────┤%n");
            for (IndicatorResult r : indicators) {
                String reading = r.reason.length() > 21 ? r.reason.substring(0, 18) + "..." : r.reason;
                System.out.printf("│ %s %-18s │ %3d  │ %-21s │%n",
                        r.statusIcon(), r.name, r.weight, reading);
            }
            System.out.printf("└──────────────────────┴──────┴───────────────────────┘%n");
            System.out.printf("  ADX=%.1f | BBW=%.2f%% | %%B=%.2f | EMASlope=%.3f%%%n",
                    adx, bbWidth, percentB, emaSlope);
            System.out.printf("  → %s%n%n", verdictMessage());
        }

        private String verdictMessage() {
            switch (verdict) {
                case SIDEWAYS:
                    return "✅ Market is SIDEWAYS. Conditions suitable for short strangle.";
                case BORDERLINE :
                    return "⚠️  BORDERLINE. Wait for cleaner sideways confirmation.";
                case TRENDING :
                    return "❌ Market is TRENDING. Do NOT run short strangle.";
                default:
                    return "No Verdict";
            }
        }
    }
}