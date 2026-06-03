package debitspread;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.OnError;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KiteTickerService
 *
 * Wraps Zerodha KiteTicker (WebSocket) for real-time streaming LTP updates.
 *
 * During active position monitoring, this provides sub-second price updates
 * vs the 5-second REST polling interval — critical for tight P&L tracking.
 *
 * Tick modes:
 *   MODE_LTP   → only last traded price (lowest bandwidth)
 *   MODE_QUOTE → LTP + depth
 *   MODE_FULL  → LTP + OI + Greeks (recommended for options)
 *
 * Usage:
 *   KiteTickerService ticker = new KiteTickerService(kite, tokens, ltpCache);
 *   ticker.connect();
 *   // ... ltpCache is now auto-updated on every tick
 *   ticker.disconnect();
 */
public class KiteTickerService {

    private final KiteConnect                        kite;
    private final List<Long>                         tokens;
    private final ConcurrentHashMap<String, Double>  ltpCache;
    private KiteTicker                               ticker;

    /** token → tradingsymbol reverse map (built from KiteConnect instruments) */
    private final ConcurrentHashMap<Long, String> tokenToSymbol = new ConcurrentHashMap<>();

    public KiteTickerService(KiteConnect kite,
                              List<Long> instrumentTokens,
                              ConcurrentHashMap<String, Double> ltpCache) {
        this.kite     = kite;
        this.tokens   = instrumentTokens;
        this.ltpCache = ltpCache;
    }

    /**
     * Register a token → symbol mapping so tick updates hit the right cache key.
     * Call this before connect() for each instrument you want to track.
     */
    public void registerSymbol(long token, String tradingsymbol) {
        tokenToSymbol.put(token, tradingsymbol);
    }

    /**
     * Connect to Kite WebSocket and subscribe to instrument tokens.
     */
    public void connect() {
        try {
            ticker = new KiteTicker(kite.getAccessToken(), kite.getApiKey());

            // ── On Connect: subscribe ─────────────────────────────────────────
            ticker.setOnConnectedListener(() -> {
                Logger.info("🟢 KiteTicker connected. Subscribing " + tokens.size() + " tokens.");
                ticker.subscribe(new ArrayList<>(tokens));
                ticker.setMode(new ArrayList<>(tokens), KiteTicker.modeFull);
            });

            // ── On Disconnect ─────────────────────────────────────────────────
            ticker.setOnDisconnectedListener(() ->
                Logger.warn("🔴 KiteTicker disconnected."));

             // ── On Error ──────────────────────────────────────────────────────
            ticker.setOnErrorListener(new OnError() {
                @Override
                public void onError(Exception exception) {
                    Logger.error("Exception error" + exception);
                }

                @Override
                public void onError(KiteException exception) {
                    Logger.error("KiteException error" + exception);
                }

                @Override
                public void onError(String s) {

                }

            });

            // ── On Tick: update LTP cache ─────────────────────────────────────
            ticker.setOnTickerArrivalListener(ticks -> {
                for (Tick tick : ticks) {
                    long   token  = tick.getInstrumentToken();
                    double ltp    = tick.getLastTradedPrice();
                    String symbol = tokenToSymbol.get(token);

                    if (symbol != null && ltp > 0) {
                        ltpCache.put(symbol, ltp);
                        Logger.debug(String.format("  📶 Tick | %s = ₹%.2f", symbol, ltp));
                    }
                }
            });

            // ── On Order Update (optional) ────────────────────────────────────
            ticker.setOnOrderUpdateListener(order ->
                Logger.info("📋 Order update: " + order.orderId + " | " + order.status));

            ticker.setTryReconnection(true);
            ticker.setMaximumRetries(10);
            ticker.setMaximumRetryInterval(30);  // seconds

            ticker.connect();
            Logger.info("KiteTicker connecting...");

        } catch (Exception | KiteException e) {
            Logger.error("Failed to start KiteTicker: " + e.getMessage());
        }
    }

    /**
     * Gracefully disconnect from WebSocket.
     */
    public void disconnect() {
        if (ticker != null && ticker.isConnectionOpen()) {
            ticker.disconnect();
            Logger.info("KiteTicker disconnected.");
        }
    }

    public boolean isConnected() {
        return ticker != null && ticker.isConnectionOpen();
    }
}
