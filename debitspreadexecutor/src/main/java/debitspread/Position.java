package debitspread;

import java.time.LocalDateTime;

/**
 * Aggregates the CALL leg and PUT leg into a single straddle position.
 */
public class Position {

    public enum ExitReason { STOP_LOSS, TARGET_PROFIT, FORCE_EXIT, NONE, TRAILING_STOP_LOSS }

    private final TradeLeg callLeg;
    private final TradeLeg putLeg;
    private final LocalDateTime entryTime;

    private boolean exited;
    private ExitReason exitReason = ExitReason.NONE;
    private LocalDateTime exitTime;

    public Position(TradeLeg callLeg, TradeLeg putLeg) {
        this.callLeg   = callLeg;
        this.putLeg    = putLeg;
        this.entryTime = LocalDateTime.now();
        this.exited    = false;
    }

    /** Combined unrealised P&L across both legs */
    public double getTotalUnrealisedPnl() {
        return callLeg.getUnrealisedPnl() + putLeg.getUnrealisedPnl();
    }

    /** Combined realised P&L (after exit) */
    public double getTotalRealisedPnl() {
        return callLeg.getRealisedPnl() + putLeg.getRealisedPnl();
    }

    /** Max credit received at entry */
    public double getTotalPremiumCollected() {
        return (callLeg.getEntryPrice() + putLeg.getEntryPrice()) * callLeg.getQuantity();
    }

    public void markExited(double callExitPrice, double putExitPrice,
                           ExitReason reason, LocalDateTime time) {
        callLeg.markExited(callExitPrice, time);
        putLeg.markExited(putExitPrice, time);
        this.exited     = true;
        this.exitReason = reason;
        this.exitTime   = time;
    }

    public void updateLtps(double callLtp, double putLtp) {
        callLeg.updateLtp(callLtp);
        putLeg.updateLtp(putLtp);
    }

    // ── Getters ────────────────────────────────────────────────────────────────
    public TradeLeg      getCallLeg()    { return callLeg; }
    public TradeLeg      getPutLeg()     { return putLeg; }
    public LocalDateTime getEntryTime()  { return entryTime; }
    public boolean       isExited()      { return exited; }
    public ExitReason    getExitReason() { return exitReason; }
    public LocalDateTime getExitTime()   { return exitTime; }

    @Override
    public String toString() {
        return String.format(
            "StraddlePosition [%s]\n  CALL: %s\n  PUT : %s\n  Total PnL: ₹%.2f",
            exited ? "CLOSED - " + exitReason : "OPEN",
            callLeg, putLeg, exited ? getTotalRealisedPnl() : getTotalUnrealisedPnl()
        );
    }
}
