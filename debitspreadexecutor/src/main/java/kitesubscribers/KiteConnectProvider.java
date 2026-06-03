package kitesubscribers;

import awesome.code.base.properties.IPropertiesProvider;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.SessionExpiryHook;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;

import java.io.IOException;

public class KiteConnectProvider {

    private final String userId;
    private final String apiSecret;
    private final String apiKey;
    private final String accessToken;
    private final String publicToken;
    public KiteConnectProvider(IPropertiesProvider propertiesProvider)
    {
        this.userId = propertiesProvider.getStringProperty("option.trading.user.id", null);
        this.apiSecret = propertiesProvider.getStringProperty("option.trading.user.api.secret", null);
        this.apiKey = propertiesProvider.getStringProperty("option.trading.user.api.key", null);
        this.accessToken = propertiesProvider.getStringProperty("option.trading.user.access.token", null);
        this.publicToken = propertiesProvider.getStringProperty("option.trading.user.public.token", null);
    }

    public KiteConnect getKiteSDKForUserID() throws IOException, KiteException {
        KiteConnect kiteSdk = new KiteConnect(this.apiKey);
        kiteSdk.setUserId(this.userId);
        kiteSdk.setAccessToken(this.accessToken);
        kiteSdk.setPublicToken(this.publicToken);
        kiteSdk.setSessionExpiryHook(new SessionExpiryHook() {
            @Override
            public void sessionExpired() {
                System.out.println("session expired");
            }
        });

        return kiteSdk;
    }

}
