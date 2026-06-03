package debitspread;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PortfolioMonitor
 *
 * Evaluates the open straddle position against all exit conditions on
 * every poll cycle. P&L is sourced from the Trade REST Service
 * (POST /clientPosition → Zerodha Position.unrealised).
 * Falls back to internal LTP tracking if the REST call fails.
 *
 * ── Exit Conditions (evaluated in priority order) ─────────────────────────
 *
 *   1. STOP_LOSS          : Total P&L ≤ -₹650
 *                           Hard floor — always active from entry.
 *
 *   2. MAX_PROFIT_CAP     : Total P&L ≥ ₹1500
 *                           Hard ceiling — exit immediately.
 *
 *   3. TRAILING_STOP_LOSS : Stepped trailing — arms only after ₹1000 profit.
 *
 *   4. FORCE_EXIT         : Market closing soon (time-based).
 *
 * ── Stepped Trailing Stop ────────────────────────────────────────────────
 *
 *   Trail is NOT active below ₹1000 — only hard SL applies.
 *   Once ₹1000 is crossed, a floor is locked in and ratchets up
 *   every ₹200 of additional profit. Floor never moves down.
 *
 *   ┌─────────────────────┬──────────────┬──────────────────────────────┐
 *   │ P&L milestone       │ Exit floor   │ Notes                        │
 *   ├─────────────────────┼──────────────┼──────────────────────────────┤
 *   │ < ₹1000             │ No trail     │ Only hard SL (₹650) active   │
 *   │ Crosses ₹1000       │ ₹800         │ Trail armed                  │
 *   │ Crosses ₹1200       │ ₹1000        │ Floor raised                 │
 *   │ Crosses ₹1400       │ ₹1200        │ Floor raised                 │
 *   │ Reaches  ₹1500      │ EXIT NOW     │ Max profit cap               │
 *   └─────────────────────┴──────────────┴──────────────────────────────┘
 */
public class PortfolioMonitor {

    // ── Stepped trail milestones: { profit_milestone, exit_floor } ───────────
    // Each milestone crossed locks in the corresponding floor.
    // Floor only ever ratchets UP — never moves down.
    private static final double[][] TRAIL_STEPS = {
            { 1000, 800  },   // crosses ₹1000 → exit if P&L drops to ₹800
            { 1200, 1000 },   // crosses ₹1200 → exit if P&L drops to ₹1000
            { 1400, 1200 },   // crosses ₹1400 → exit if P&L drops to ₹1200
    };
    private static final double MAX_PROFIT_CAP = 1500;  // exit immediately

    private final TradeConfig    config;
    private final PositionClient positionClient;

    /**
     * Locked-in exit floor for the current session.
     * NEGATIVE_INFINITY = trail not yet armed (P&L hasn't crossed ₹1000).
     * Ratchets UP each time a milestone is crossed. Never moves down.
     */
    private double trailFloor      = Double.NEGATIVE_INFINITY;
    private double lastMilestoneHit = 0;

    public PortfolioMonitor(TradeConfig config) {
        this.config         = config;
        this.positionClient = new PositionClient(config.tradeUrl);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Primary evaluation — called every poll cycle by TradeEngine
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Evaluates all exit conditions in priority order.
     *
     * @param position the active straddle position
     * @return ExitReason.NONE to hold, or the triggered exit reason
     */
    public Position.ExitReason evaluate(Position position) {
        if (position == null || position.isExited()) {
            return Position.ExitReason.NONE;
        }

        double pnl = fetchRealPnl(position);

        // Ratchet trail floor up if a new milestone has been crossed
        updateTrailFloor(pnl);

        logStatus(position, pnl);

        // ── 1. Hard Stop-Loss ─────────────────────────────────────────────────
        if (pnl <= -config.stopLossAmount) {
            Logger.warn(String.format(
                    "🛑 STOP-LOSS | PnL=₹%.2f | Limit=-₹%.0f",
                    pnl, config.stopLossAmount));
            return Position.ExitReason.STOP_LOSS;
        }

        // ── 2. Max Profit Cap ─────────────────────────────────────────────────
        if (pnl >= MAX_PROFIT_CAP) {
            Logger.info(String.format(
                    "🏆 MAX PROFIT CAP | PnL=₹%.2f | Cap=₹%.0f — locking in.",
                    pnl, MAX_PROFIT_CAP));
            return Position.ExitReason.TARGET_PROFIT;
        }

        // ── 3. Stepped Trailing Stop ──────────────────────────────────────────
        //    Only fires once trailFloor has been set (P&L crossed ₹1000).
        //    Exits when current P&L drops at or below the locked floor.
        if (trailFloor != Double.NEGATIVE_INFINITY && pnl <= trailFloor) {
            Logger.warn(String.format(
                    "📉 TRAILING STOP | PnL=₹%.2f | Floor=₹%.0f | Milestone=₹%.0f",
                    pnl, trailFloor, lastMilestoneHit));
            return Position.ExitReason.TRAILING_STOP_LOSS;
        }

        return Position.ExitReason.NONE;
    }

    /**
     * Force-exit check — market closing soon.
     */
    public Position.ExitReason evaluateForceExit(Position position,
                                                 boolean isMarketClosing) {
        if (isMarketClosing && position != null && !position.isExited()) {
            Logger.warn("⏰ FORCE EXIT: Market closing soon. Squaring off all positions.");
            return Position.ExitReason.FORCE_EXIT;
        }
        return Position.ExitReason.NONE;
    }

    /**
     * Resets trail state for a fresh position.
     * Must be called by TradeEngine when a new position is entered.
     */
    public void resetForNewPosition() {
        trailFloor       = Double.NEGATIVE_INFINITY;
        lastMilestoneHit = 0;
        Logger.info("PortfolioMonitor reset. Trail arms when P&L crosses ₹1000.");
    }

    public double getTrailFloor() {
        return trailFloor == Double.NEGATIVE_INFINITY ? 0 : trailFloor;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Trail floor ratchet
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Checks each milestone in order. If P&L has crossed a milestone and the
     * corresponding floor is higher than the current floor, locks it in.
     *
     * Example:
     *   pnl=1050 → crosses ₹1000 → floor = ₹800  (trail ARMED)
     *   pnl=1250 → crosses ₹1200 → floor = ₹1000 (trail RAISED)
     *   pnl=1450 → crosses ₹1400 → floor = ₹1200 (trail RAISED)
     *   pnl=1500 → MAX_PROFIT_CAP hit → exits before reaching here
     */
    private void updateTrailFloor(double pnl) {
        for (double[] step : TRAIL_STEPS) {
            double milestone = step[0];
            double floor     = step[1];

            if (pnl >= milestone && floor > trailFloor) {
                double prev  = trailFloor;
                trailFloor       = floor;
                lastMilestoneHit = milestone;

                if (prev == Double.NEGATIVE_INFINITY) {
                    Logger.info(String.format(
                            "🎯 Trail ARMED   | P&L=₹%.2f crossed ₹%.0f → floor set at ₹%.0f",
                            pnl, milestone, floor));
                } else {
                    Logger.info(String.format(
                            "📈 Trail RAISED  | P&L=₹%.2f crossed ₹%.0f → floor ₹%.0f → ₹%.0f",
                            pnl, milestone, prev, floor));
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // P&L — fetched from Trade REST Service, fallback to internal LTP
    // ════════════════════════════════════════════════════════════════════════

    private double fetchRealPnl(Position position) {
        List<String> symbols = List.of(
                position.getCallLeg().getContract().getSymbol(),
                position.getPutLeg().getContract().getSymbol()
        );

        try {
            double totalPnl = 0.0;
            int    count    = 0;

            for (String userId : config.userIds) {
                List<com.zerodhatech.models.Position> userPositions = positionClient.getPositions(userId);

                List<com.zerodhatech.models.Position> relevant = userPositions.stream()
                        .filter(p -> symbols.stream()
                                .anyMatch(s -> s.equalsIgnoreCase(p.tradingSymbol)))
                        .collect(Collectors.toList());

                if (relevant.isEmpty()) {
                    Logger.debug("No matching positions for userId=" + userId);
                    continue;
                }

                double userPnl = relevant.stream().mapToDouble(p -> p.unrealised).sum();

                Logger.debug(String.format("  %s | positions=%d | unrealised=₹%.2f",
                        userId, relevant.size(), userPnl));

                for (com.zerodhatech.models.Position p : relevant) {
                    Logger.debug(String.format(
                            "    %-30s | qty=%-4d | avg=₹%.2f | LTP=₹%.2f | unrealised=₹%.2f",
                            p.tradingSymbol, p.netQuantity, p.averagePrice, p.lastPrice, p.unrealised));
                }

                totalPnl += userPnl;
                count++;
            }

            if (count == 0) {
                Logger.warn("No positions from Trade Service — falling back to internal P&L.");
                return position.getTotalUnrealisedPnl();
            }

            return totalPnl;

        } catch (IOException e) {
            Logger.error("Position fetch failed: " + e.getMessage() + " — using internal P&L.");
            return position.getTotalUnrealisedPnl();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Logging
    // ════════════════════════════════════════════════════════════════════════

    private void logStatus(Position position, double pnl) {
        double callLtp = position.getCallLeg().getCurrentLtp();
        double putLtp  = position.getPutLeg().getCurrentLtp();
        String trend   = pnl >= 0 ? "▲" : "▼";

        String trailStatus;
        if (trailFloor == Double.NEGATIVE_INFINITY) {
            double toArm = 1000 - pnl;
            trailStatus  = toArm > 0
                    ? String.format(" | Trail=NOT ARMED (₹%.0f to ₹1000)", toArm)
                    : " | Trail=ARMING...";
        } else {
            trailStatus = String.format(" | Floor=₹%.0f | Milestone=₹%.0f",
                    trailFloor, lastMilestoneHit);
        }

        Logger.info(String.format(
                "📊 %s PnL=₹%.2f | CALL=₹%.2f | PUT=₹%.2f | SL=-₹%.0f | Cap=₹%.0f%s",
                trend, pnl, callLtp, putLtp,
                config.stopLossAmount, MAX_PROFIT_CAP, trailStatus));
    }
}