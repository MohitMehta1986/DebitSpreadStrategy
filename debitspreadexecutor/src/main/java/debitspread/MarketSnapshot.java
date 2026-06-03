package debitspread;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Point-in-time snapshot of all relevant market data.
 */
public class MarketSnapshot {

    private final LocalDateTime timestamp;
    private final double niftySpotPrice;
    private final double indiaVix;
    private final double atmStrike;
    private final List<OptionContract> optionChain;

    public MarketSnapshot(LocalDateTime timestamp, double niftySpotPrice,
                          double indiaVix, double atmStrike,
                          List<OptionContract> optionChain) {
        this.timestamp      = timestamp;
        this.niftySpotPrice = niftySpotPrice;
        this.indiaVix       = indiaVix;
        this.atmStrike      = atmStrike;
        this.optionChain    = optionChain;
    }

    public LocalDateTime        getTimestamp()      { return timestamp; }
    public double               getNiftySpotPrice() { return niftySpotPrice; }
    public double               getIndiaVix()       { return indiaVix; }
    public double               getAtmStrike()      { return atmStrike; }
    public List<OptionContract> getOptionChain()    { return optionChain; }

    @Override
    public String toString() {
        return String.format("[%s] NIFTY=%.2f | VIX=%.2f | ATM=%.0f | Chain=%d strikes",
            timestamp, niftySpotPrice, indiaVix, atmStrike, optionChain.size());
    }
}
