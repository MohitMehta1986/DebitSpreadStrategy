package debitspread;

import java.util.List;

/**
 * Centralized configuration for all strategy parameters.
 * Modify these values to tune the strategy without touching logic.
 */
public class TradeConfig {

    // ── Users ──────────────────────────────────────────────────────────────────
    /** List of user IDs to place orders for (forwarded to Trade REST Service) */
    public final List<String> userIds;

    // ── VIX Gates ─────────────────────────────────────────────────────────────
    /** VIX at or above this → NO trade */
    public final double vixNoTradeThreshold;
    /** VIX around this value → eligible for entry */
    public final double vixEntryTarget;
    /** Tolerance band around vixEntryTarget (±band) */
    public final double vixEntryBand;

    // ── Option Entry Filters ───────────────────────────────────────────────────
    /** Maximum option premium (both legs must be < this) */
    public final double maxPremium;
    /** Target delta for both strikes */
    public final double targetDelta;
    /** Allowable delta range: targetDelta ± deltaRange */
    public final double deltaRange;
    /** Maximum absolute difference between call delta and put delta */
    public final double maxDeltaDifference;

    // ── Exit Conditions ────────────────────────────────────────────────────────
    /** Exit if total portfolio loss reaches this value (₹) */
    public final double stopLossAmount;
    /** Exit if total portfolio profit reaches this value (₹) */
    public final double targetProfitAmount;

    // ── Execution ──────────────────────────────────────────────────────────────
    /** Lot size for NIFTY50 options */
    public final int lotSize;
    /** Number of lots to trade per leg */
    public final int lots;
    /** Order type for entry/exit */
    public final String orderType;
    /** Polling interval in milliseconds */
    public final long pollingIntervalMs;
    /** Market open time (HH:mm) */
    public final String marketOpenTime;
    /** Market close time (HH:mm) - force exit before this */
    public final String marketCloseTime;
    /** Minutes before market close to force-exit all positions */
    public final int forceExitMinutesBeforeClose;

    public final String tradeUrl;

    private TradeConfig(Builder b) {
        this.userIds              = b.userIds;
        this.vixNoTradeThreshold = b.vixNoTradeThreshold;
        this.vixEntryTarget = b.vixEntryTarget;
        this.vixEntryBand = b.vixEntryBand;
        this.maxPremium = b.maxPremium;
        this.targetDelta = b.targetDelta;
        this.deltaRange = b.deltaRange;
        this.maxDeltaDifference = b.maxDeltaDifference;
        this.stopLossAmount = b.stopLossAmount;
        this.targetProfitAmount = b.targetProfitAmount;
        this.lotSize = b.lotSize;
        this.lots = b.lots;
        this.orderType = b.orderType;
        this.pollingIntervalMs = b.pollingIntervalMs;
        this.marketOpenTime = b.marketOpenTime;
        this.marketCloseTime = b.marketCloseTime;
        this.forceExitMinutesBeforeClose = b.forceExitMinutesBeforeClose;
        this.tradeUrl = b.tradeUrl;
    }

    public static TradeConfig defaultConfig() {
        return new Builder().build();
    }

    @Override
    public String toString() {
        return String.format(
            "[VIX: noTrade>=%.0f, entry~%.0f±%.1f | Premium<%.0f | Delta=%.2f±%.2f, maxDiff=%.2f" +
            " | SL=₹%.0f, Target=₹%.0f | Lots=%d x %d]",
            vixNoTradeThreshold, vixEntryTarget, vixEntryBand,
            maxPremium, targetDelta, deltaRange, maxDeltaDifference,
            stopLossAmount, targetProfitAmount, lots, lotSize
        );
    }

    // ── Builder ────────────────────────────────────────────────────────────────
    public static class Builder {
        List<String> userIds            = List.of();   // set via .userIds(...)
        double vixNoTradeThreshold = 20.0;
        double vixEntryTarget      = 15.0;
        double vixEntryBand        = 1.0;
        double maxPremium          = 100.0;
        double targetDelta         = 0.225;   // midpoint of 0.20–0.25
        double deltaRange          = 0.015;   // ±0.015 → [0.20, 0.23]
        double maxDeltaDifference  = 0.03;
        double stopLossAmount      = 650.0;
        double targetProfitAmount  = 1200.0;
        int    lotSize             = 65;       // NIFTY lot size (verify current)
        int    lots                = 4;
        String orderType           = "MARKET";
        long   pollingIntervalMs   = 5_000L;
        String marketOpenTime      = "09:15";
        String marketCloseTime     = "15:15";
        int    forceExitMinutesBeforeClose = 15;
        String tradeUrl = "http://localhost:9002/";

        public Builder userIds(List<String> v)           { this.userIds = v; return this; }
        public Builder vixNoTradeThreshold(double v) { this.vixNoTradeThreshold = v; return this; }
        public Builder vixEntryTarget(double v)      { this.vixEntryTarget = v; return this; }
        public Builder maxPremium(double v)          { this.maxPremium = v; return this; }
        public Builder targetDelta(double v)         { this.targetDelta = v; return this; }
        public Builder deltaRange(double v)          { this.deltaRange = v; return this; }
        public Builder maxDeltaDifference(double v)  { this.maxDeltaDifference = v; return this; }
        public Builder stopLossAmount(double v)      { this.stopLossAmount = v; return this; }
        public Builder targetProfitAmount(double v)  { this.targetProfitAmount = v; return this; }
        public Builder lots(int v)                   { this.lots = v; return this; }
        public Builder pollingIntervalMs(long v)     { this.pollingIntervalMs = v; return this; }
        public Builder tradeUrl(String v)     { this.tradeUrl = v; return this; }

        public TradeConfig build() { return new TradeConfig(this); }
    }
}
