package org.radarcns.android.auth.portal;

import android.support.annotation.NonNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AuthStringParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.radarcns.android.auth.LoginManager.AUTH_TYPE_BEARER;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;

public class AccessTokenParser implements AuthStringParser {
    private final AppAuthState state;

    public AccessTokenParser(AppAuthState originalState) {
        state = originalState;
    }

    @Override
    public AppAuthState parse(@NonNull String body) throws IOException {
        String refreshToken = (String) state.getProperty(MP_REFRESH_TOKEN_PROPERTY);
        try {
            JSONObject json = new JSONObject(body);
            String accessToken = json.getString("access_token");
            refreshToken = json.optString("refresh_token", refreshToken);
            return state.newBuilder()
                    .token(accessToken)
                    .tokenType(AUTH_TYPE_BEARER)
                    .userId(json.getString("sub"))
                    .property(MP_REFRESH_TOKEN_PROPERTY, refreshToken)
                    .setHeader("Authorization", "Bearer " + accessToken)
                    .expiration(TimeUnit.SECONDS.toMillis(json.getLong("expires_in"))
                            + System.currentTimeMillis())
                    .build();
        } catch (JSONException ex) {
            throw new IOException("Failed to parse json string " + body, ex);
        }
    }
}
