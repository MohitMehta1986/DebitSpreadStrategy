package debitspread;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.zerodhatech.models.Position;
import okhttp3.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PositionClient
 *
 * Calls your existing Trade REST Service to fetch live positions per client:
 *
 *   POST {TRADE_SERVICE_URL}/clientPosition?clientId={clientId}
 *   Response: Zerodha Position model (or list of them)
 *
 * Used by PortfolioMonitor to compute real P&L from the broker's own
 * position records rather than relying solely on internal LTP tracking.
 *
 * ── Why fetch from the service? ────────────────────────────────────────────
 *   • Zerodha's Position object contains `unrealised` and `realised` P&L
 *     computed server-side — more accurate than our local tracking.
 *   • Catches any fills/rejections that may have diverged from our records.
 *   • Supports multiple userIds: fetches position for each and aggregates.
 */
public class PositionClient {


    private static final String POSITION_ENDPOINT  = "/clientPosition";
    private static final MediaType JSON            = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson         gson;
    private final String       baseUrl;

    public PositionClient(String tradeServiceUrl) {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(5,  TimeUnit.SECONDS)
            .readTimeout(10,    TimeUnit.SECONDS)
            .writeTimeout(5,    TimeUnit.SECONDS)
            .build();
        this.gson    = new GsonBuilder().create();
        this.baseUrl = resolveBaseUrl(tradeServiceUrl);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetch all open positions for a single clientId.
     *
     * Maps the response to Zerodha's {@link Position} model.
     * Returns an empty list if the call fails or no positions exist.
     *
     * @param clientId the user/client ID (matches userIds in CreateOrderRequest)
     */
    public List<Position> getPositions(String clientId) throws IOException {
        String url = baseUrl + POSITION_ENDPOINT + "?clientId=" + clientId;
        Logger.debug("GET positions: " + url);

        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, ""))   // POST with no body per your mapping
            .addHeader("Accept", "application/json")
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                Logger.error(String.format(
                    "getPositions failed for clientId=%s | HTTP %d | %s",
                    clientId, response.code(), body));
                return Collections.emptyList();
            }

            Logger.debug("Position response for " + clientId + ": " + body);
            return parsePositions(body);
        }
    }

    /**
     * Fetch and aggregate positions across multiple clients.
     * Returns a flat list of all positions from all clients.
     *
     * @param clientIds list of user/client IDs
     */
    public List<Position> getPositionsForAll(List<String> clientIds) throws IOException {
        List<Position> all = new java.util.ArrayList<>();
        for (String clientId : clientIds) {
            try {
                List<Position> positions = getPositions(clientId);
                Logger.info(String.format(
                    "  Client %s → %d position(s)", clientId, positions.size()));
                all.addAll(positions);
            } catch (IOException e) {
                Logger.error("Failed to fetch positions for clientId=" + clientId
                    + " : " + e.getMessage());
                // Continue fetching for remaining clients
            }
        }
        return all;
    }

    /**
     * Find the position matching a specific trading symbol for a client.
     * Returns null if not found.
     *
     * @param clientId       the client to query
     * @param tradingSymbol  e.g. "NIFTY24APR22500CE"
     */
    public Position getPositionForSymbol(String clientId,
                                          String tradingSymbol) throws IOException {
        return getPositions(clientId).stream()
            .filter(p -> tradingSymbol.equalsIgnoreCase(p.tradingSymbol))
            .findFirst()
            .orElse(null);
    }

    /**
     * Compute total unrealised P&L across all positions for all clients.
     * Uses Zerodha's own `unrealised` field from the Position model.
     *
     * @param clientIds list of user/client IDs
     * @param symbols   only sum P&L for these symbols (e.g. the two straddle legs)
     */
    public double getTotalUnrealisedPnl(List<String> clientIds,
                                         List<String> symbols) throws IOException {
        List<Position> all = getPositionsForAll(clientIds);
        return all.stream()
            .filter(p -> symbols.stream()
                .anyMatch(s -> s.equalsIgnoreCase(p.tradingSymbol)))
            .mapToDouble(p -> p.unrealised)
            .sum();
    }

    /**
     * Compute total realised P&L across all positions for all clients.
     * Uses Zerodha's own `realised` field from the Position model.
     */
    public double getTotalRealisedPnl(List<String> clientIds,
                                       List<String> symbols) throws IOException {
        List<Position> all = getPositionsForAll(clientIds);
        return all.stream()
            .filter(p -> symbols.stream()
                .anyMatch(s -> s.equalsIgnoreCase(p.tradingSymbol)))
            .mapToDouble(p -> p.realised)
            .sum();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Parsing
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Parses the REST response into a list of Zerodha Position objects.
     *
     * Your endpoint returns Object which is the Zerodha Position model.
     * Three common response shapes are handled:
     *
     *   Shape A: Single Position object  → { "tradingSymbol": ..., "unrealised": ... }
     *   Shape B: List of Position objects → [ { ... }, { ... } ]
     *   Shape C: Wrapped response         → { "data": { "net": [...] } }  (Kite API style)
     */
    private List<Position> parsePositions(String json) {
        if (json == null || json.isBlank() || json.equals("null")) {
            return Collections.emptyList();
        }

        String trimmed = json.trim();

        // ── Shape B: JSON array ───────────────────────────────────────────────
        if (trimmed.startsWith("[")) {
            Type listType = new TypeToken<List<Position>>() {}.getType();
            List<Position> list = gson.fromJson(trimmed, listType);
            return list != null ? list : Collections.emptyList();
        }

        // ── Shape C: Kite-style wrapped response ──────────────────────────────
        //   { "net": [...], "day": [...] }
        if (trimmed.contains("\"net\"")) {
            PositionWrapper wrapper = gson.fromJson(trimmed, PositionWrapper.class);
            if (wrapper != null && wrapper.net != null) {
                return wrapper.net;
            }
        }

        // ── Shape A: Single Position object ───────────────────────────────────
        try {
            Position single = gson.fromJson(trimmed, Position.class);
            if (single != null && single.tradingSymbol != null) {
                return Collections.singletonList(single);
            }
        } catch (Exception ignored) {}

        Logger.warn("Could not parse position response: " + trimmed);
        return Collections.emptyList();
    }

    /** Kite-style position wrapper: { "net": [...], "day": [...] } */
    private static class PositionWrapper {
        List<Position> net;
        List<Position> day;
    }

    private String resolveBaseUrl(String baseUrl) {
        String url = baseUrl;
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                "TRADE_SERVICE_URL environment variable not set.");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
