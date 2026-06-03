package debitspread;

import java.util.*;
import java.util.stream.Collectors;

/**
 * StrikeSelector
 *
 * Finds the optimal OTM Call and Put strikes satisfying all entry conditions:
 *   1. Premium (LTP) < maxPremium (₹100)
 *   2. |Delta| ∈ [targetDelta - deltaRange, targetDelta + deltaRange]  (~0.20–0.23)
 *   3. |callDelta| - |putDelta| ≤ maxDeltaDifference  (≤ 0.03)
 *
 * Strategy note: This algo uses a SHORT STRANGLE (not ATM straddle) —
 * selling OTM options with delta ~0.20–0.23 for lower risk than pure ATM.
 */
public class StrikeSelector {

    private final TradeConfig config;

    public StrikeSelector(TradeConfig config) {
        this.config = config;
    }

    /**
     * Result wrapper for the selected call/put pair.
     */
    public static class SelectedStrikes {
        public final OptionContract call;
        public final OptionContract put;
        public final double deltaDifference;

        public SelectedStrikes(OptionContract call, OptionContract put) {
            this.call           = call;
            this.put            = put;
            this.deltaDifference = Math.abs(call.getAbsDelta() - put.getAbsDelta());
        }

        @Override
        public String toString() {
            return String.format(
                "Selected Strikes:\n  CALL → %s\n  PUT  → %s\n  Delta diff = %.4f",
                call, put, deltaDifference
            );
        }
    }

    /**
     * Main selection method.
     * Returns Optional.empty() if no suitable pair found.
     */
    public Optional<SelectedStrikes> select(MarketSnapshot snapshot) {
        List<OptionContract> chain = snapshot.getOptionChain();

        // Step 1: Filter CALLs that are OTM with premium < 100 and delta in range
        List<OptionContract> validCalls = chain.stream()
            .filter(o -> o.getOptionType() == OptionContract.OptionType.CALL)
            .filter(o -> o.getStrikePrice() > snapshot.getAtmStrike())  // OTM calls
            .filter(this::meetsEntryFilters)
            .sorted(Comparator.comparingDouble(o -> Math.abs(o.getAbsDelta() - config.targetDelta)))
            .collect(Collectors.toList());

        // Step 2: Filter PUTs that are OTM with premium < 100 and delta in range
        List<OptionContract> validPuts = chain.stream()
            .filter(o -> o.getOptionType() == OptionContract.OptionType.PUT)
            .filter(o -> o.getStrikePrice() < snapshot.getAtmStrike())  // OTM puts
            .filter(this::meetsEntryFilters)
            .sorted(Comparator.comparingDouble(o -> Math.abs(o.getAbsDelta() - config.targetDelta)))
            .collect(Collectors.toList());

        Logger.debug(String.format("Valid CALLs: %d, Valid PUTs: %d", validCalls.size(), validPuts.size()));

        if (validCalls.isEmpty() || validPuts.isEmpty()) {
            Logger.info("No options meet premium/delta criteria. Skipping.");
            return Optional.empty();
        }

        // Step 3: Find the best pair minimising delta difference
        SelectedStrikes best = null;
        double bestScore     = Double.MAX_VALUE;

        for (OptionContract call : validCalls) {
            for (OptionContract put : validPuts) {
                double deltaDiff = Math.abs(call.getAbsDelta() - put.getAbsDelta());

                if (deltaDiff <= config.maxDeltaDifference) {
                    // Score = combined deviation from target delta
                    double score = Math.abs(call.getAbsDelta() - config.targetDelta)
                                 + Math.abs(put.getAbsDelta()  - config.targetDelta);
                    if (score < bestScore) {
                        bestScore = score;
                        best      = new SelectedStrikes(call, put);
                    }
                }
            }
        }

        if (best == null) {
            Logger.info(String.format(
                "No pair meets delta difference constraint (max %.2f). Skipping.",
                config.maxDeltaDifference));
            return Optional.empty();
        }

        Logger.info("✅ " + best);
        return Optional.of(best);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private boolean meetsEntryFilters(OptionContract o) {
        boolean premiumOk = o.getLtp() < config.maxPremium;
        boolean deltaOk   = o.getAbsDelta() >= (config.targetDelta - config.deltaRange)
                         && o.getAbsDelta() <= (config.targetDelta + config.deltaRange);

        if (!premiumOk) {
            Logger.debug(String.format("  ✗ %s | Premium ₹%.2f ≥ ₹%.0f",
                o, o.getLtp(), config.maxPremium));
        }
        if (!deltaOk) {
            Logger.debug(String.format("  ✗ %s | Delta %.4f out of [%.2f, %.2f]",
                o, o.getAbsDelta(),
                config.targetDelta - config.deltaRange,
                config.targetDelta + config.deltaRange));
        }
        return premiumOk && deltaOk;
    }
}
