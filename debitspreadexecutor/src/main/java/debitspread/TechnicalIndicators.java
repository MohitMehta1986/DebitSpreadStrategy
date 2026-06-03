package debitspread;

/**
 * TechnicalIndicators
 *
 * Stateless helpers for computing ADX and Bollinger %B from a rolling
 * close/high/low array.  Used by DebitSpreadBacktest to feed indicator
 * values into DebitSpreadEngine.onTick() on each candle replay.
 *
 * These match the same computation the live SidewaysMarketDetector uses
 * so backtest and live behaviour are identical.
 */
public class TechnicalIndicators {

    private TechnicalIndicators() {}

    // ════════════════════════════════════════════════════════════════════════
    // ADX  (Wilder's Average Directional Index, period=14)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Computes ADX over the last {@code period} candles from {@code filled} entries.
     * Returns 0 if there isn't enough history.
     *
     * @param highs  rolling highs array (length >= filled)
     * @param lows   rolling lows array
     * @param closes rolling closes array
     * @param filled number of valid entries populated in the arrays
     * @param period ADX period (typically 14)
     */
    public static double adx(double[] highs, double[] lows, double[] closes,
                              int filled, int period) {
        int need = period * 2 + 1;
        if (filled < need) return 0;

        int start = filled - need;

        double smoothTR  = 0, smoothPDM = 0, smoothMDM = 0;

        // Wilder smoothing seed (first period)
        for (int i = start + 1; i < start + period + 1; i++) {
            smoothTR  += trueRange(highs[i], lows[i], closes[i - 1]);
            smoothPDM += plusDM(highs[i], highs[i-1], lows[i], lows[i-1]);
            smoothMDM += minusDM(highs[i], highs[i-1], lows[i], lows[i-1]);
        }

        double adxSum = 0;
        for (int i = start + period + 1; i < start + need; i++) {
            double tr  = trueRange(highs[i], lows[i], closes[i - 1]);
            double pdm = plusDM(highs[i], highs[i-1], lows[i], lows[i-1]);
            double mdm = minusDM(highs[i], highs[i-1], lows[i], lows[i-1]);

            smoothTR  = smoothTR  - (smoothTR  / period) + tr;
            smoothPDM = smoothPDM - (smoothPDM / period) + pdm;
            smoothMDM = smoothMDM - (smoothMDM / period) + mdm;

            double plusDI  = smoothTR > 0 ? 100.0 * smoothPDM / smoothTR  : 0;
            double minusDI = smoothTR > 0 ? 100.0 * smoothMDM / smoothTR  : 0;
            double diSum   = plusDI + minusDI;
            double dx      = diSum > 0 ? 100.0 * Math.abs(plusDI - minusDI) / diSum : 0;
            adxSum        += dx;
        }

        return adxSum / period;
    }

    private static double trueRange(double h, double l, double prevC) {
        return Math.max(h - l, Math.max(Math.abs(h - prevC), Math.abs(l - prevC)));
    }

    private static double plusDM(double h, double ph, double l, double pl) {
        double up   = h - ph;
        double down = pl - l;
        return (up > down && up > 0) ? up : 0;
    }

    private static double minusDM(double h, double ph, double l, double pl) {
        double up   = h - ph;
        double down = pl - l;
        return (down > up && down > 0) ? down : 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Bollinger %B  (period=20, stddev multiplier=2)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Computes Bollinger %B for the last candle in the filled window.
     *
     * %B = (close - lower) / (upper - lower)
     *   0 = at lower band, 0.5 = at midline, 1 = at upper band
     *   >1 or <0 = outside bands (breakout signal)
     *
     * Returns 0.5 (neutral) if there's insufficient history.
     */
    public static double percentB(double[] closes, int filled, int period) {
        if (filled < period) return 0.5;

        int    start = filled - period;
        double sum   = 0;
        for (int i = start; i < filled; i++) sum += closes[i];
        double mean = sum / period;

        double variance = 0;
        for (int i = start; i < filled; i++) {
            double diff = closes[i] - mean;
            variance += diff * diff;
        }
        double stddev = Math.sqrt(variance / period);

        if (stddev < 1e-9) return 0.5; // flat market / no width

        double upper = mean + 2 * stddev;
        double lower = mean - 2 * stddev;
        double last  = closes[filled - 1];

        return (last - lower) / (upper - lower);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Bollinger Band Width  (for compression detection)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * BBW = (upper - lower) / middle × 100  (as a percentage of price)
     * Low BBW (< 0.5%) = compression/squeeze.
     */
    public static double bbWidth(double[] closes, int filled, int period) {
        if (filled < period) return 100.0; // no compression if no data

        int    start = filled - period;
        double sum   = 0;
        for (int i = start; i < filled; i++) sum += closes[i];
        double mean = sum / period;

        double variance = 0;
        for (int i = start; i < filled; i++) {
            double diff = closes[i] - mean;
            variance += diff * diff;
        }
        double stddev = Math.sqrt(variance / period);
        if (mean < 1e-9) return 100.0;

        double upper = mean + 2 * stddev;
        double lower = mean - 2 * stddev;
        return (upper - lower) / mean * 100.0;
    }
}
