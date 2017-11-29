package org.radarcns.android.auth;

import android.content.Intent;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.radarcns.android.auth.ManagementPortalClient.parseAccessToken;

public class ManagementPortalLoginManager implements LoginManager, Callback {
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalLoginManager.class);
    public static final String MP_REFRESH_TOKEN = ManagementPortalLoginManager.class.getName() + ".refreshToken";
    private ManagementPortalClient client;
    private String refreshToken;
    private final LoginListener listener;
    private AppAuthState authState;
    private String clientId;
    private String clientSecret;

    public ManagementPortalLoginManager(LoginListener listener, AppAuthState authState, ManagementPortalClient client, String clientId, String clientSecret) {
        this.listener = listener;
        this.authState = authState;
        this.refreshToken = (String) authState.getProperty(MP_REFRESH_TOKEN);
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
        authState = this.authState.newBuilder()
                .property(MP_REFRESH_TOKEN, refreshToken)
                .build();
        refresh();
    }

    public void setManagementPortal(ManagementPortalClient client, String clientId, String clientSecret) {
        this.client = client;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refresh();
    }

    @Override
    public AppAuthState refresh() {
        if (this.refreshToken == null || client == null) {
            logger.info("Cannot refresh MP without token or client");
            return null;
        }
        if (authState.isValid()) {
            return authState;
        } else {
            try {
                logger.info("Refreshing token");
                client.refreshToken(authState, clientId, clientSecret, this);
                return null;
            } catch (IOException ex) {
                logger.error("Failed to refresh ManagementPortal token", ex);
                return null;
            } catch (JSONException ex) {
                logger.error("Failed to deserialize ManagementPortal token", ex);
                return null;
            }
        }
    }

    @Override
    public void start() {
        refresh();
    }

    @Override
    public void onActivityCreate() {
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    @Override
    public void onFailure(Call call, IOException e) {
        listener.loginFailed(this, new IOException("Cannot reach management portal", e));
    }

    @Override
    public void onResponse(Call call, Response response) {
        try {
            listener.loginSucceeded(this, parseAccessToken(authState, response));
        } catch (IOException ex) {
            onFailure(call, ex);
        }
    }
}
