package debitspread;

import java.time.LocalDateTime;

/**
 * Represents one leg of the straddle (either the CALL or PUT side).
 * This algo SELLS both legs (short straddle / short strangle).
 */
public class TradeLeg {

    public enum Side { SELL, BUY }

    private final OptionContract contract;
    private final Side side;
    private final int quantity;           // total qty (lots × lotSize)
    private final double entryPrice;
    private final LocalDateTime entryTime;

    private double currentLtp;
    private double exitPrice;
    private LocalDateTime exitTime;
    private boolean exited;

    public TradeLeg(OptionContract contract, Side side, int quantity,
                    double entryPrice, LocalDateTime entryTime) {
        this.contract   = contract;
        this.side       = side;
        this.quantity   = quantity;
        this.entryPrice = entryPrice;
        this.entryTime  = entryTime;
        this.currentLtp = entryPrice;
        this.exited     = false;
    }

    /**
     * P&L for a SELL leg:
     *   Profit when premium decays → (entryPrice - currentLtp) × qty
     *   Loss   when premium rises  → negative value
     */
    public double getUnrealisedPnl() {
        if (side == Side.SELL) {
            return (entryPrice - currentLtp) * quantity;
        } else {
            return (currentLtp - entryPrice) * quantity;
        }
    }

    public double getRealisedPnl() {
        if (!exited) return 0;
        if (side == Side.SELL) {
            return (entryPrice - exitPrice) * quantity;
        } else {
            return (exitPrice - entryPrice) * quantity;
        }
    }

    public void updateLtp(double ltp) { this.currentLtp = ltp; }

    public void markExited(double exitPrice, LocalDateTime exitTime) {
        this.exitPrice = exitPrice;
        this.exitTime  = exitTime;
        this.exited    = true;
    }

    // ── Getters ────────────────────────────────────────────────────────────────
    public OptionContract  getContract()   { return contract; }
    public Side            getSide()       { return side; }
    public int             getQuantity()   { return quantity; }
    public double          getEntryPrice() { return entryPrice; }
    public LocalDateTime   getEntryTime()  { return entryTime; }
    public double          getCurrentLtp() { return currentLtp; }
    public double          getExitPrice()  { return exitPrice; }
    public LocalDateTime   getExitTime()   { return exitTime; }
    public boolean         isExited()      { return exited; }

    @Override
    public String toString() {
        return String.format("%s %s | Entry=₹%.2f | Current=₹%.2f | Qty=%d | PnL=₹%.2f",
            side, contract, entryPrice, currentLtp, quantity, getUnrealisedPnl());
    }
}
