package org.radarcns.android.auth.portal;

import android.support.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AuthStringParser;

import java.io.IOException;

import static org.radarcns.android.auth.portal.ManagementPortalClient.BASE_URL_PROPERTY;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;
import static org.radarcns.android.auth.portal.ManagementPortalClient.PRIVACY_POLICY_URL_PROPERTY;

/**
 * Reads refreshToken and meta-data of token from json string and sets it as property in
 * {@link AppAuthState}.
 */
public class MetaTokenParser implements AuthStringParser {

    private AppAuthState currentState;

    public MetaTokenParser(AppAuthState state) {
        this.currentState = state;
    }

    @Override
    public AppAuthState parse(@NonNull String authString) throws IOException {
        String refreshToken = (String) currentState.getProperty(MP_REFRESH_TOKEN_PROPERTY);

        try {
            JSONObject json = new JSONObject(authString);
            refreshToken = json.optString("refreshToken", refreshToken);
            return currentState.newBuilder()
                    .property(MP_REFRESH_TOKEN_PROPERTY, refreshToken)
                    .property(PRIVACY_POLICY_URL_PROPERTY, json.getString("privacyPolicyUrl"))
                    .property(BASE_URL_PROPERTY, json.getString("baseUrl"))
                    .build();
        } catch (JSONException ex) {
            throw new IOException("Failed to parse json string " + authString, ex);
        }
    }
}
