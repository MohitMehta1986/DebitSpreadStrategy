package debitspread;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;

import java.io.IOException;

/**
 * KiteSessionManager
 *
 * Handles Zerodha Kite Connect authentication lifecycle.
 *
 * ── OAuth2 Flow (Kite Connect) ────────────────────────────────────────────
 *
 *   1. Generate login URL → user opens in browser
 *   2. After login, Kite redirects to your callback URL with ?request_token=XXXX
 *   3. Exchange request_token + api_secret → access_token  (valid 1 trading day)
 *   4. Use access_token for all API calls
 *
 * ── Daily Setup ───────────────────────────────────────────────────────────
 *
 *   Because the access_token expires each day, you have two options:
 *
 *   OPTION A (Manual — simplest):
 *     • Run KiteSessionManager.printLoginUrl() at 09:00 each day
 *     • Complete login in browser, copy request_token from redirect URL
 *     • Set KITE_REQUEST_TOKEN env variable and re-run
 *
 *   OPTION B (Automated — recommended):
 *     • Use Selenium/Playwright to automate the Kite login form
 *     • Extract request_token from redirect URL automatically
 *     • Wire into a cron job or startup script at 09:10 IST
 *
 * ── Environment Variables ─────────────────────────────────────────────────
 *
 *   KITE_API_KEY        → From Kite Connect developer console
 *   KITE_API_SECRET     → From Kite Connect developer console
 *   KITE_REQUEST_TOKEN  → Generated fresh each day from OAuth redirect
 *   KITE_ACCESS_TOKEN   → (Optional) if already obtained, skip exchange step
 *
 * ── Kite Connect Docs ─────────────────────────────────────────────────────
 *   https://kite.trade/docs/connect/v3/user/
 */
public class KiteSessionManager {

    private final String kiteApiKey;
    private final String kiteApiSecret;
    private final String kiteRequestToken;
    private final String kiteAccessToken;

    private KiteConnect kite;
    private User user;



    public KiteSessionManager(String kiteApiKey, String kiteApiSecret, String kiteRequestToken, String kiteAccessToken)
    {
        this.kiteApiKey = kiteApiKey;
        this.kiteApiSecret = kiteApiSecret;
        this.kiteRequestToken = kiteRequestToken;
        this.kiteAccessToken = kiteAccessToken;
    }
    /**
     * Initialise and authenticate.
     * Returns a ready-to-use KiteConnect instance.
     */
    public KiteConnect authenticate() throws KiteException, IOException {
        validateEnvVars();

        kite = new KiteConnect(kiteApiKey);
        //kite.setLogging(false);  // set true to enable SDK debug logging

        // ── If access_token already provided, use it directly ────────────────
        if (kiteAccessToken != null && !kiteAccessToken.isBlank()) {
            kite.setAccessToken(kiteAccessToken);
            Logger.info("✅ Kite session resumed from KITE_ACCESS_TOKEN.");
            verifySession();
            return kite;
        }

        // ── Otherwise, exchange request_token for access_token ───────────────
        if (kiteRequestToken == null || kiteRequestToken.isBlank()) {
            throw new IllegalStateException(
                "Set KITE_REQUEST_TOKEN or KITE_ACCESS_TOKEN.\n" +
                "Login URL: " + getLoginUrl()
            );
        }

        user = kite.generateSession(kiteRequestToken, kiteApiSecret);
        kite.setAccessToken(user.accessToken);

        Logger.info("✅ Kite session established.");
        Logger.info("   User    : " + user.userName + " (" + user.userId + ")");
        Logger.info("   Broker  : " + user.broker);
        Logger.info("   Token   : " + maskToken(user.accessToken));

        return kite;
    }

    /** Returns the Kite OAuth login URL (open in browser to get request_token). */
    public String getLoginUrl() {
        KiteConnect k = new KiteConnect(kiteApiKey);
        return k.getLoginURL();
    }

    /** Prints the login URL to console — useful for daily manual token refresh. */
    public static void printLoginUrl() {
        String key = System.getenv("KITE_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("KITE_API_KEY not set.");
            return;
        }
        KiteConnect k = new KiteConnect(key);
        System.out.println("Open this URL to login and get request_token:");
        System.out.println(k.getLoginURL());
    }

    public KiteConnect getKite()   { return kite; }
    public User        getUser()   { return user; }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Calls getProfile() to verify the session is still valid. */
    private void verifySession() throws KiteException, IOException {
        try {
            var profile = kite.getProfile();
            Logger.info("Session valid for user: " + profile.userName);
        } catch (KiteException e) {
            Logger.error("Session invalid: " + e.getMessage());
            throw e;
        }
    }

    private void validateEnvVars() {
        if (kiteApiKey == null || kiteApiKey.isBlank()) {
            throw new IllegalStateException(
                "KITE_API_KEY environment variable not set.\n" +
                "Get your API key from: https://developers.kite.trade/");
        }
        if (kiteApiSecret == null || kiteApiSecret.isBlank()) {
            throw new IllegalStateException("KITE_API_SECRET environment variable not set.");
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() < 8) return "****";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
