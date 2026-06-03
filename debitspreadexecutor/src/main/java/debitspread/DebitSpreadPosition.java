package debitspread;

import java.time.LocalDateTime;

/**
 * DebitSpreadPosition
 *
 * Represents a live Directional Debit Spread on NIFTY FNO:
 *
 *   BULL CALL SPREAD (BULLISH):  BUY ATM/ITM Call  + SELL OTM Call (higher strike)
 *   BEAR PUT SPREAD  (BEARISH):  BUY ATM/ITM Put   + SELL OTM Put  (lower strike)
 *
 * ── Why debit spreads when market is TRENDING ─────────────────────────────
 *
 *   Short strangle profits from SIDEWAYS markets (theta decay).
 *   Debit spreads profit from DIRECTIONAL markets.
 *
 *   Debit spread advantages vs naked long option:
 *     • Net debit (cost) is lower — short leg offsets premium paid
 *     • Max loss is DEFINED = net debit paid
 *     • Theta decay from short leg partially offsets long leg decay
 *
 * ── Example (NIFTY @ 24043, BULLISH) ─────────────────────────────────────
 *   BUY  24000 CE @ ₹180  (long leg — ATM)
 *   SELL 24200 CE @ ₹90   (short leg — 200 pts OTM)
 *   Net debit  = ₹90  per unit × 75 = ₹6,750
 *   Max profit = ₹110 per unit × 75 = ₹8,250  (if NIFTY > 24200 at expiry)
 *   Max loss   = ₹90  per unit × 75 = ₹6,750  (if NIFTY < 24000 at expiry)
 *   R:R = 1.22 : 1
 *
 * ── Exit Conditions ───────────────────────────────────────────────────────
 *   • TARGET : Spread value reaches 80% of max profit → lock in gains
 *   • STOP   : Position loses 50% of net debit paid
 *   • FORCE  : 15 min before market close
 */
public class DebitSpreadPosition {

    public enum Direction  { BULL_CALL_SPREAD, BEAR_PUT_SPREAD }
    public enum ExitReason { TARGET_PROFIT, TRAILING_STOP, TREND_REVERSAL, STOP_LOSS, FORCE_EXIT, NONE }

    // ── Spread legs ───────────────────────────────────────────────────────────
    public final Direction      direction;
    public final OptionContract longLeg;       // BUY this
    public final OptionContract shortLeg;      // SELL this
    public final int            quantity;      // lots × lotSize

    // ── Entry prices ──────────────────────────────────────────────────────────
    public final double        longEntryPrice;   // paid for long leg
    public final double        shortEntryPrice;  // received for short leg
    public final double        netDebit;         // longEntry - shortEntry (cost per unit)
    public final LocalDateTime entryTime;

    // ── Risk / Reward ─────────────────────────────────────────────────────────
    public final double strikeWidth;   // |shortStrike - longStrike|
    public final double maxProfitPer;  // strikeWidth - netDebit  (per unit)
    public final double maxLossPer;    // netDebit               (per unit)

    // ── Live LTPs ─────────────────────────────────────────────────────────────
    private double currentLongLtp;
    private double currentShortLtp;

    // ── Exit ──────────────────────────────────────────────────────────────────
    private double        longExitPrice;
    private double        shortExitPrice;
    private ExitReason    exitReason = ExitReason.NONE;
    private LocalDateTime exitTime;
    private boolean       exited     = false;

    public DebitSpreadPosition(Direction direction,
                               OptionContract longLeg, OptionContract shortLeg,
                               double longEntryPrice, double shortEntryPrice,
                               int quantity, LocalDateTime entryTime) {
        this.direction        = direction;
        this.longLeg          = longLeg;
        this.shortLeg         = shortLeg;
        this.longEntryPrice   = longEntryPrice;
        this.shortEntryPrice  = shortEntryPrice;
        this.quantity         = quantity;
        this.entryTime        = entryTime;
        this.netDebit         = longEntryPrice - shortEntryPrice;
        this.strikeWidth      = Math.abs(shortLeg.getStrikePrice() - longLeg.getStrikePrice());
        // FIX A: Guard netDebit >= strikeWidth.
        // If fill slippage causes netDebit >= strikeWidth, maxProfitPer goes zero/negative.
        // getProfitPct() would then always return 0 → the 80% target exit NEVER fires →
        // position held until FORCE_EXIT at EOD, losing all theta edge in the meantime.
        // Clamp to 1 rupee minimum so exit logic stays live. The caller (findBestSpread)
        // already rejects entries where netDebit/strikeWidth > MAX_DEBIT_RATIO, so this
        // is a defensive catch for extreme broker fill slippage only.
        double rawMaxProfit   = strikeWidth - netDebit;
        if (rawMaxProfit <= 0) {
            System.err.printf("[WARN] DebitSpreadPosition: netDebit(%.2f) >= strikeWidth(%.2f) " +
                            "— entry fill had extreme slippage. maxProfitPer clamped to 1.%n",
                    netDebit, strikeWidth);
        }
        this.maxProfitPer     = Math.max(rawMaxProfit, 1.0);
        this.maxLossPer       = netDebit;
        this.currentLongLtp   = longEntryPrice;
        this.currentShortLtp  = shortEntryPrice;
    }

    // ════════════════════════════════════════════════════════════════════════
    // P&L
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Current unrealised P&L.
     * P&L = (currentSpreadValue - netDebit) × quantity
     * Positive = profit, negative = loss.
     */
    public double getUnrealisedPnl() {
        double spreadNow = currentLongLtp - currentShortLtp;
        return (spreadNow - netDebit) * quantity;
    }

    public double getRealisedPnl() {
        if (!exited) return 0;
        return (longExitPrice - shortExitPrice - netDebit) * quantity;
    }

    /** Max theoretical profit in ₹ */
    public double getMaxProfitAmount() { return maxProfitPer * quantity; }

    /** Max theoretical loss in ₹ */
    public double getMaxLossAmount()   { return maxLossPer  * quantity; }

    /**
     * Current profit as % of maximum possible profit (0–100+).
     * Used by DebitSpreadMonitor to trigger exit at 80%.
     */
    public double getProfitPct() {
        if (maxProfitPer <= 0) return 0;
        double spreadNow = currentLongLtp - currentShortLtp;
        return ((spreadNow - netDebit) / maxProfitPer) * 100.0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Updates
    // ════════════════════════════════════════════════════════════════════════

    public void updateLtps(double longLtp, double shortLtp) {
        this.currentLongLtp  = longLtp;
        this.currentShortLtp = shortLtp;
    }

    public void markExited(double longExit, double shortExit,
                           ExitReason reason, LocalDateTime time) {
        this.longExitPrice  = longExit;
        this.shortExitPrice = shortExit;
        this.exitReason     = reason;
        this.exitTime       = time;
        this.exited         = true;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Getters
    // ════════════════════════════════════════════════════════════════════════

    public boolean       isExited()          { return exited; }
    public ExitReason    getExitReason()     { return exitReason; }
    public LocalDateTime getExitTime()       { return exitTime; }
    public double        getCurrentLongLtp() { return currentLongLtp; }
    public double        getCurrentShortLtp(){ return currentShortLtp; }
    public double        getLongExitPrice()  { return longExitPrice; }
    public double        getShortExitPrice() { return shortExitPrice; }

    @Override
    public String toString() {
        // FIX B: Use correct option type label per direction.
        // Previously "CE" was hardcoded for both spreads — Bear Put spread logs showed
        // "Long 24000CE" instead of "Long 24000PE", making trade logs actively misleading.
        String label   = direction == Direction.BULL_CALL_SPREAD ? "BULL CALL" : "BEAR PUT";
        String optType = direction == Direction.BULL_CALL_SPREAD ? "CE" : "PE";
        return String.format(
                "%s SPREAD | Long %.0f%s@₹%.2f | Short %.0f%s@₹%.2f | Debit=₹%.2f | " +
                        "MaxProfit=₹%.2f | MaxLoss=₹%.2f | PnL=₹%.2f | %s",
                label,
                longLeg.getStrikePrice(),  optType, longEntryPrice,
                shortLeg.getStrikePrice(), optType, shortEntryPrice,
                netDebit * quantity,
                getMaxProfitAmount(), getMaxLossAmount(),
                exited ? getRealisedPnl() : getUnrealisedPnl(),
                exited ? "CLOSED (" + exitReason + ")" : "OPEN"
        );
    }
}