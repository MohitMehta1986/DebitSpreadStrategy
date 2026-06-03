package debitspread;

import java.time.LocalDateTime;
import java.util.List;

/**
 * BacktestCandle
 *
 * One 3-minute candle of NIFTY spot data, enriched with:
 *   - India VIX at that timestamp
 *   - Option chain snapshot (ATM ± 5 strikes) for the nearest weekly expiry
 *
 * Produced by BacktestDataFetcher; consumed by DebitSpreadBacktest.
 */
public class BacktestCandle {

    // ── OHLCV ─────────────────────────────────────────────────────────────────
    public final LocalDateTime time;
    public final double        niftyOpen;
    public final double        niftyHigh;
    public final double        niftyLow;
    public final double        niftyClose;
    public final long          volume;
    public final double        vix;

    // ── Option chain (populated by BacktestDataFetcher.attachOptionChains) ───
    public List<OptionContract> optionChain;
    public double               atm;
    public String               expiry;

    public BacktestCandle(LocalDateTime time,
                          double open, double high, double low, double close, long volume,
                          double vix) {
        this.time       = time;
        this.niftyOpen  = open;
        this.niftyHigh  = high;
        this.niftyLow   = low;
        this.niftyClose = close;
        this.volume     = volume;
        this.vix        = vix;
    }

    @Override
    public String toString() {
        return String.format("[%s] O=%.2f H=%.2f L=%.2f C=%.2f VIX=%.2f ATM=%.0f Chain=%d",
            time, niftyOpen, niftyHigh, niftyLow, niftyClose, vix, atm,
            optionChain != null ? optionChain.size() : 0);
    }
}
