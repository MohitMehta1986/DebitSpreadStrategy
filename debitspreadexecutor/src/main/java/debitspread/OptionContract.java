package debitspread;

// ════════════════════════════════════════════════════════════════════════════
// Option contract snapshot
// ════════════════════════════════════════════════════════════════════════════
public class OptionContract {

    public enum OptionType { CALL, PUT }

    private final String symbol;
    private final double strikePrice;
    private final OptionType optionType;
    private double ltp;           // Last Traded Price (premium)
    private double delta;
    private double iv;
    private double theta;
    private double vega;
    private double gamma;
    private final String expiry;

    public OptionContract(String symbol, double strikePrice, OptionType optionType,
                          String expiry, double ltp, double delta, double iv) {
        this.symbol      = symbol;
        this.strikePrice = strikePrice;
        this.optionType  = optionType;
        this.expiry      = expiry;
        this.ltp         = ltp;
        this.delta       = delta;
        this.iv          = iv;
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    public String     getSymbol()      { return symbol; }
    public double     getStrikePrice() { return strikePrice; }
    public OptionType getOptionType()  { return optionType; }
    public double     getLtp()         { return ltp; }
    public double     getDelta()       { return delta; }
    public double     getIv()         { return iv; }
    public double     getTheta()       { return theta; }
    public double     getVega()        { return vega; }
    public double     getGamma()       { return gamma; }
    public String     getExpiry()      { return expiry; }

    // ── Setters (live feed updates) ───────────────────────────────────────────
    public void setLtp(double ltp)       { this.ltp   = ltp; }
    public void setDelta(double delta)   { this.delta  = delta; }
    public void setIv(double iv)         { this.iv    = iv; }
    public void setTheta(double theta)   { this.theta  = theta; }
    public void setVega(double vega)     { this.vega   = vega; }
    public void setGamma(double gamma)   { this.gamma  = gamma; }

    /** Absolute delta (PUT delta is negative by convention; we compare magnitude) */
    public double getAbsDelta() { return Math.abs(delta); }

    @Override
    public String toString() {
        return String.format("%-6s Strike=%.0f | LTP=₹%.2f | Delta=%.4f | IV=%.1f%%",
            optionType, strikePrice, ltp, delta, iv * 100);
    }
}
