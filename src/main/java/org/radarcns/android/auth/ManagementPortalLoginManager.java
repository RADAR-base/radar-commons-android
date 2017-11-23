package org.radarcns.android.auth;

import android.content.Intent;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.rest.RestClient;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.concurrent.TimeUnit;

import static org.radarcns.producer.rest.RestClient.responseBody;

public class ManagementPortalLoginManager implements LoginManager, Callback {

    private static final String MP_REFRESH_TOKEN = ManagementPortalLoginManager.class.getName() + ".refreshToken";
    private final LoginActivity activity;
    private final RestClient client;
    private String refreshToken;
    private AppAuthState authState;
    private final String clientId;
    private final String clientSecret;

    public ManagementPortalLoginManager(LoginActivity activity, AppAuthState authState, ServerConfig managementPortal, String clientId, String clientSecret) {
        this.activity = activity;
        this.authState = authState;
        this.client = new RestClient(managementPortal);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
        authState = this.authState.newBuilder()
                .property(MP_REFRESH_TOKEN, refreshToken)
                .build();
    }

    @Override
    public AppAuthState refresh() {
        if (this.refreshToken == null) {
            return null;
        }
        if (authState.isValid()) {
            return authState;
        } else {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken)
                        .build();

                Request request = client.requestBuilder("oauth/token")
                        .post(body)
                        .addHeader("Authorization", Credentials.basic(clientId, clientSecret))
                        .build();

                client.request(request, this);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
            return null;
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
    public void onFailure(Call call, IOException ex) {
        activity.loginFailed(this, ex);
    }

    @Override
    public void onResponse(Call call, Response response) throws IOException {
        String body = responseBody(response);
        try {
            if (body == null) {
                throw new IOException("Response did not have a body");
            }
            JSONObject json = new JSONObject(body);
            String accessToken = json.getString("access_token");
            refreshToken = json.optString("refresh_token", refreshToken);
            authState = new AppAuthState.Builder()
                    .token(accessToken)
                    .tokenType(AUTH_TYPE_BEARER)
                    .userId(json.getString("sub"))
                    .property(MP_REFRESH_TOKEN, refreshToken)
                    .header("Authorization", "Bearer " + accessToken)
                    .expiration(TimeUnit.SECONDS.toMillis(json.getLong("expires_in")
                            + System.currentTimeMillis()))
                    .build();
            activity.loginSucceeded(this, authState);
        } catch (IOException | NullPointerException | JSONException ex) {
            activity.loginFailed(this, new IOException("Failed to process response " + body, ex));
        }
    }
}
