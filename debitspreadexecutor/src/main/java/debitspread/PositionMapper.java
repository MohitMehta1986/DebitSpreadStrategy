package debitspread;

import com.zerodhatech.models.Position;

public class PositionMapper {

    public static PositionView map(Position pos) {

        // 1. Basic validation
        if (pos == null || pos.netQuantity == 0) {
            return null;
        }

        PositionView view = new PositionView();

        boolean isLong = pos.netQuantity > 0;
        view.setLong(isLong);

        view.setSymbol(pos.tradingSymbol);
        view.setQuantity(pos.netQuantity);

        // 2. Resolve Entry Price (CORE LOGIC)
        double entryPrice = resolveEntryPrice(pos, isLong);
        view.setEntryPrice(entryPrice);

        // 3. Intraday Entry Price (optional but useful)
        double dayEntryPrice = resolveDayEntryPrice(pos, isLong);
        view.setDayEntryPrice(dayEntryPrice);

        // 4. PnL fields
        view.setPnl(safe(pos.pnl));
        view.setUnrealised(safe(pos.unrealised));
        view.setRealised(safe(pos.realised));

        // 5. Exposure
        view.setExposure(Math.abs(pos.netQuantity) * entryPrice);

        return view;
    }

    // =========================
    // 🔥 CORE PRICE RESOLUTION
    // =========================
    private static double resolveEntryPrice(Position pos, boolean isLong) {

        // 1. Direct price (fastest but unreliable sometimes)
        Double direct = isLong ? pos.buyPrice : pos.sellPrice;
        if (direct != null && direct > 0) {
            return direct;
        }

        // 2. Compute from value/quantity (MOST RELIABLE)
        if (isLong) {
            if (pos.buyQuantity > 0 && pos.buyValue != null) {
                return pos.buyValue / pos.buyQuantity;
            }
        } else {
            if (pos.sellQuantity > 0 && pos.sellValue != null) {
                return pos.sellValue / pos.sellQuantity;
            }
        }

        // 3. Last fallback (not ideal but better than 0)
        if (pos.lastPrice != null && pos.lastPrice > 0) {
            return pos.lastPrice;
        }

        return 0;
    }

    // =========================
    // 📊 INTRADAY PRICE (OPTIONAL)
    // =========================
    private static double resolveDayEntryPrice(Position pos, boolean isLong) {

        if (isLong) {
            if (pos.dayBuyQuantity > 0 && pos.dayBuyValue > 0) {
                return pos.dayBuyValue / pos.dayBuyQuantity;
            }
        } else {
            if (pos.daySellQuantity > 0 && pos.daySellValue > 0) {
                return pos.daySellValue / pos.daySellQuantity;
            }
        }

        return 0;
    }

    // =========================
    // 🛡️ NULL SAFETY
    // =========================
    private static double safe(Double val) {
        return val != null ? val : 0;
    }
}