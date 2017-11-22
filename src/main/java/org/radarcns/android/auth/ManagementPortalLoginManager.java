package org.radarcns.android.auth;

import android.content.Intent;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.rest.RestClient;

import java.io.IOException;
import java.net.MalformedURLException;

import static org.radarcns.producer.rest.RestClient.responseBody;

public class ManagementPortalLoginManager implements LoginManager {

    private static final String MP_REFRESH_TOKEN = ManagementPortalLoginManager.class.getName() + ".refreshToken";
    private final LoginActivity activity;
    private final RestClient client;
    private final Callback callback;
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
        this.callback = new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                ManagementPortalLoginManager.this.activity.loginFailed(ManagementPortalLoginManager.this, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = responseBody(response);
                    if (body == null) {
                        throw new IOException("Response did not have a body");
                    }
                    JSONObject json = new JSONObject(body);
                    ManagementPortalLoginManager.this.authState = new AppAuthState.Builder()
                            .tokenType(AUTH_TYPE_BEARER)
                            .token(json.getString("accessToken"))
                            .userId(json.getString("sub"))
                            .property(MP_REFRESH_TOKEN, json.getString("refreshToken"))
                            .header("Authorization", "Bearer " + json.getString("accessToken"))
                            .build();
                    ManagementPortalLoginManager.this.activity.loginSucceeded(ManagementPortalLoginManager.this, ManagementPortalLoginManager.this.authState);
                } catch (NullPointerException ex) {
                    throw new IOException("Request failed", ex);
                } catch (JSONException ex) {
                    throw new IOException("Failed to parse JSON", ex);
                }
            }
        };
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
                HttpUrl url = client.getRelativeUrl("oauth/token").newBuilder()
                        .addQueryParameter("client_id", clientId)
                        .addQueryParameter("client_secret", clientSecret)
                        .addQueryParameter("grant_type", "refresh_token")
                        .addQueryParameter("refresh_token", refreshToken)
                        .build();

                Request request = new Request.Builder()
                        .get()
                        .url(url)
                        .build();

                client.request(request, callback);
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
}
