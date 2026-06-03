package debitspread;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.zerodhatech.models.Position;
import okhttp3.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ZerodhaOrderExecutor
 *
 * Two-step execution:
 *   1. POST /api/orders       → places the order (fire-and-forget, response not used for fill)
 *   2. POST /clientPosition   → polls until Position.sellPrice / buyPrice is populated
 *                               for every userId → that IS the fill price
 *
 * /clientPosition is the single source of truth for fills.
 */
public class ZerodhaOrderExecutor {

    private static final String    ORDERS_ENDPOINT       = "/api/trade/createorder";
    private static final MediaType JSON                  = MediaType.parse("application/json; charset=utf-8");
    private static final long      FILL_POLL_INTERVAL_MS = 500;
    private static final long      FILL_POLL_TIMEOUT_MS  = 30_000;
    private static final String    EXCHANGE              = "NFO";
    private static final String    ORDER_TYPE            = "MARKET";
    private static final String    PRODUCT               = "MIS";

    private final TradeConfig    config;
    private final PositionClient positionClient;
    private final OkHttpClient   httpClient;
    private final Gson           gson;
    private final String         baseUrl;

    public ZerodhaOrderExecutor(TradeConfig config) {
        this.config         = config;
        this.positionClient = new PositionClient(config.tradeUrl);
        this.httpClient     = new OkHttpClient.Builder()
                .connectTimeout(5,  TimeUnit.SECONDS)
                .readTimeout(30,    TimeUnit.SECONDS)
                .writeTimeout(10,   TimeUnit.SECONDS)
                .build();
        this.gson    = new GsonBuilder().create();
        this.baseUrl = resolveBaseUrl();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    public TradeLeg sellCall(OptionContract call) throws IOException {
        return placeAndFill(call, TradeLeg.Side.SELL, "SELL");
    }

    public TradeLeg sellPut(OptionContract put) throws IOException {
        return placeAndFill(put, TradeLeg.Side.SELL, "SELL");
    }

    /** BUY an option (long leg of a debit spread). */
    public TradeLeg buyOption(OptionContract option) throws IOException {
        return placeAndFill(option, TradeLeg.Side.BUY, "BUY");
    }

    /** SELL an option (short leg of a debit spread or strangle). */
    public TradeLeg sellOption(OptionContract option) throws IOException {
        return placeAndFill(option, TradeLeg.Side.SELL, "SELL");
    }

    /**
     * Square off the SHORT leg: BUY back the sold contract.
     * Places BUY order; exit fill read from Position.buyPrice.
     * Use for: closing the short (sold) leg of a debit spread.
     */
    public double buyBack(TradeLeg leg) throws IOException {
        String symbol = leg.getContract().getSymbol();
        int    qty    = leg.getQuantity();

        Logger.info(String.format("[EXIT] EXIT BUY (close short leg) | %s | Qty=%d | Users=%s",
                symbol, qty, config.userIds));

        //TODO: Uncomment while doing actual trade
        //postOrder(symbol, "BUY", qty);

        //TODO: Uncomment while doing actual trade
        double fill = leg.getExitPrice(); //pollFillPrice(symbol, true);

        Logger.info(String.format("   Exit fill: ₹%.2f (entry was ₹%.2f)",
                fill, leg.getEntryPrice()));

        return fill;
    }

    /**
     * Square off the LONG leg: SELL back the bought contract.
     * Places SELL order; exit fill read from Position.sellPrice.
     * Use for: closing the long (bought) leg of a debit spread.
     */
    public double sellBack(TradeLeg leg) throws IOException {
        String symbol = leg.getContract().getSymbol();
        int    qty    = leg.getQuantity();

        Logger.info(String.format("[EXIT] EXIT SELL (close long leg) | %s | Qty=%d | Users=%s",
                symbol, qty, config.userIds));

        //TODO: Uncomment while doing actual trade
        //postOrder(symbol, "SELL", qty);

        //TODO: Uncomment while doing actual trade
        double fill = leg.getExitPrice(); //pollFillPrice(symbol, false);

        Logger.info(String.format("   Exit fill: ₹%.2f (entry was ₹%.2f)",
                fill, leg.getEntryPrice()));

        return fill;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 1 — POST /api/orders
    // ════════════════════════════════════════════════════════════════════════

    private TradeLeg placeAndFill(OptionContract contract,
                                  TradeLeg.Side side, String txType) throws IOException {
        int    qty    = config.lots * config.lotSize;
        String symbol = contract.getSymbol();

        Logger.info(String.format("[ENTRY] ENTRY %s | %s | Qty=%d | Users=%s",
                txType, symbol, qty, config.userIds));
        //TODO: Uncomment
        //postOrder(symbol, txType, qty);

        //TODO: Uncomment
        double fill = contract.getLtp(); //pollFillPrice(symbol, false);

        Logger.info(String.format("   Entry fill: ₹%.2f", fill));

        return new TradeLeg(contract, side, qty, fill, LocalDateTime.now());
    }

    /**
     * POSTs order to Trade REST service.
     * Response body is only logged — fill price is NOT read from here.
     */
    private void postOrder(String symbol, String txType, int qty) throws IOException {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setUserIds(config.userIds);
        req.setSymbol(symbol);
        req.setExchange(EXCHANGE);
        req.setOrderType(ORDER_TYPE);
        req.setTransactionType(txType);
        req.setQuantity(qty);
        req.setProduct(PRODUCT);
        req.setPrice(null);
        req.setTriggerPrice(null);

        Request httpReq = new Request.Builder()
                .url(baseUrl + ORDERS_ENDPOINT)
                .post(RequestBody.create(JSON, gson.toJson(req)))
                .addHeader("Accept", "application/json")
                .build();

        try (Response resp = httpClient.newCall(httpReq).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException(String.format(
                        "POST /api/orders failed | HTTP %d | symbol=%s | %s",
                        resp.code(), symbol, body));
            }
            Logger.debug("Order accepted: " + body);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Step 2 — Poll /clientPosition for fill price
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Polls /clientPosition for every userId every 500ms.
     *
     * Entry (isExit=false): reads Position.sellPrice — set by exchange once
     *   the SELL order is matched. Falls back to averagePrice if not populated.
     *
     * Exit (isExit=true): reads Position.buyPrice — set by exchange once
     *   the BUY order is matched.
     *
     * Returns only when ALL userIds have a confirmed fill price > 0.
     * Averages the per-user prices into a single fill price.
     */
    private double pollFillPrice(String symbol, boolean isExit) throws IOException {
        long deadline = System.currentTimeMillis() + FILL_POLL_TIMEOUT_MS;
        int  attempt  = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            double[] fills = collectFills(symbol, isExit);

            Logger.debug(String.format("  Poll #%d | %s | filled=%d/%d",
                    attempt, symbol, fills.length, config.userIds.size()));

            if (fills.length == config.userIds.size()) {
                double avg = average(fills);
                Logger.info(String.format("  [OK] Fill confirmed | %s | per-user=%s | avg=₹%.2f",
                        symbol, formatPrices(fills), avg));
                return avg;
            }

            sleep(FILL_POLL_INTERVAL_MS);
        }

        throw new IOException(String.format(
                "Timeout: fill price not available for %s after %ds. Check broker dashboard.",
                symbol, FILL_POLL_TIMEOUT_MS / 1000));
    }

    /**
     * Calls /clientPosition for each userId via PositionClient.
     * Reads the fill price field matching the trade direction:
     *   SELL entry → Position.sellPrice  (fallback: averagePrice)
     *   BUY  exit  → Position.buyPrice
     *
     * Only includes a user's price if > 0 (order confirmed filled).
     * Returns array of confirmed prices; caller retries until length == userIds.size().
     */
    private double[] collectFills(String symbol, boolean isExit) {
        double[] buffer = new double[config.userIds.size()];
        int count = 0;

        for (String userId : config.userIds) {
            try {
                Position pos = positionClient.getPositionForSymbol(userId, symbol);

// 1. Null check FIRST
                if (pos == null) {
                    Logger.debug(userId + " → no position");
                    continue;
                }

// 2. Map DTO → Domain
                PositionView posView = PositionMapper.map(pos);

                if (posView == null) {
                    Logger.debug(userId + " → no active position");
                    continue;
                }

// 3. Get resolved entry price (already computed safely in mapper)
                double price = posView.getEntryPrice();

// 4. Final safeguard (optional fallback)
                if (price <= 0 && pos.lastPrice != null && pos.lastPrice > 0) {
                    price = pos.lastPrice;
                }

// 5. Logging (use correct fields)
                Logger.debug(String.format(
                        "%s | qty=%d | sellPrice=₹%.2f | buyPrice=₹%.2f | entryPrice=₹%.2f",
                        userId,
                        pos.netQuantity,
                        safe(pos.sellPrice),
                        safe(pos.buyPrice),
                        price
                ));

// 6. Add to buffer safely
                if (price > 0) {
                    if (count < buffer.length) {
                        buffer[count++] = price;
                    } else {
                        Logger.warn("Buffer full, skipping user " + userId);
                    }
                }

            } catch (IOException e) {
                Logger.warn(String.format("    %s | /clientPosition error: %s",
                        userId, e.getMessage()));
                // skip this user this round; retry on next poll
            }
        }

        double[] result = new double[count];
        System.arraycopy(buffer, 0, result, 0, count);
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Utilities
    // ════════════════════════════════════════════════════════════════════════

    private double average(double[] arr) {
        double sum = 0;
        for (double v : arr) sum += v;
        return arr.length > 0 ? sum / arr.length : 0.0;
    }

    private String formatPrices(double[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("₹%.2f", arr[i]));
        }
        return sb.append("]").toString();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String resolveBaseUrl() {
        String url = config.tradeUrl;
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "TRADE_SERVICE_URL not set. Example: export TRADE_SERVICE_URL=http://localhost:8080");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static double safe(Double val) {
        return val != null ? val : 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // DTO — mirrors your existing CreateOrderRequest
    // ════════════════════════════════════════════════════════════════════════

    public static class CreateOrderRequest {
        private List<String> userIds;
        private String symbol;
        private String exchange;
        private String orderType;
        private String transactionType;
        private int    quantity;
        private Double price;
        private Double triggerPrice;
        private String product;

        public void setUserIds(List<String> v)   { this.userIds = v; }
        public void setSymbol(String v)          { this.symbol = v; }
        public void setExchange(String v)        { this.exchange = v; }
        public void setOrderType(String v)       { this.orderType = v; }
        public void setTransactionType(String v) { this.transactionType = v; }
        public void setQuantity(int v)           { this.quantity = v; }
        public void setPrice(Double v)           { this.price = v; }
        public void setTriggerPrice(Double v)    { this.triggerPrice = v; }
        public void setProduct(String v)         { this.product = v; }

        public List<String> getUserIds()         { return userIds; }
        public String       getSymbol()          { return symbol; }
        public String       getExchange()        { return exchange; }
        public String       getOrderType()       { return orderType; }
        public String       getTransactionType() { return transactionType; }
        public int          getQuantity()        { return quantity; }
        public Double       getPrice()           { return price; }
        public Double       getTriggerPrice()    { return triggerPrice; }
        public String       getProduct()         { return product; }
    }
}