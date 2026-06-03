package debitspread;

/**
 * Black-Scholes option pricing model.
 * Used to compute theoretical price and delta for strike selection.
 *
 * NOTE: In production, greeks come directly from the broker's option chain feed.
 *       This class is used in simulation mode.
 */
public class BlackScholes {

    public static class Greeks {
        public final double price;
        public final double delta;
        public final double gamma;
        public final double vega;
        public final double theta;

        public Greeks(double price, double delta, double gamma, double vega, double theta) {
            this.price = price;
            this.delta = delta;
            this.gamma = gamma;
            this.vega  = vega;
            this.theta = theta;
        }
    }

    /**
     * Compute BS price and greeks.
     *
     * @param S     Spot price
     * @param K     Strike price
     * @param r     Risk-free rate (annual, e.g. 0.065)
     * @param sigma Implied volatility (annual, e.g. 0.15)
     * @param T     Time to expiry in years (e.g. 7/365.0)
     * @param isCall true for CALL, false for PUT
     */
    public static Greeks compute(double S, double K, double r,
                                  double sigma, double T, boolean isCall) {
        if (T <= 0) {
            double intrinsic = isCall ? Math.max(S - K, 0) : Math.max(K - S, 0);
            double delta     = isCall ? (S > K ? 1.0 : 0.0) : (S < K ? -1.0 : 0.0);
            return new Greeks(intrinsic, delta, 0, 0, 0);
        }

        double sqrtT = Math.sqrt(T);
        double d1    = (Math.log(S / K) + (r + 0.5 * sigma * sigma) * T) / (sigma * sqrtT);
        double d2    = d1 - sigma * sqrtT;

        double Nd1   = normalCDF(d1);
        double Nd2   = normalCDF(d2);
        double nd1   = normalPDF(d1);

        double price, delta, gamma, vega, theta;

        gamma = nd1 / (S * sigma * sqrtT);
        vega  = S * nd1 * sqrtT / 100.0;  // per 1% IV move

        if (isCall) {
            price = S * Nd1 - K * Math.exp(-r * T) * Nd2;
            delta = Nd1;
            theta = (-S * nd1 * sigma / (2 * sqrtT)
                     - r * K * Math.exp(-r * T) * Nd2) / 365.0;
        } else {
            price = K * Math.exp(-r * T) * (1 - Nd2) - S * (1 - Nd1);
            delta = Nd1 - 1.0;   // negative for puts
            theta = (-S * nd1 * sigma / (2 * sqrtT)
                     + r * K * Math.exp(-r * T) * (1 - Nd2)) / 365.0;
        }

        return new Greeks(Math.max(price, 0.01), delta, gamma, vega, theta);
    }

    // ── Standard Normal Functions ─────────────────────────────────────────────

    /** CDF of the standard normal distribution (Hart approximation) */
    public static double normalCDF(double x) {
        if (x < -8.0) return 0.0;
        if (x >  8.0) return 1.0;
        double t   = 1.0 / (1.0 + 0.2316419 * Math.abs(x));
        double poly = t * (0.319381530
                   + t * (-0.356563782
                   + t * (1.781477937
                   + t * (-1.821255978
                   + t *  1.330274429))));
        double approx = 1.0 - normalPDF(x) * poly;
        return x >= 0 ? approx : 1.0 - approx;
    }

    /** PDF of the standard normal distribution */
    public static double normalPDF(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }
}
