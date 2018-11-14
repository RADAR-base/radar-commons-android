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

    private final AppAuthState currentState;

    public MetaTokenParser(AppAuthState state) {
        this.currentState = state;
    }

    @Override
    public AppAuthState parse(@NonNull String authString) throws IOException {

        try {
            JSONObject json = new JSONObject(authString);
            return currentState.newBuilder()
                    .attribute(MP_REFRESH_TOKEN_PROPERTY, json.getString("refreshToken"))
                    .attribute(PRIVACY_POLICY_URL_PROPERTY, json.getString("privacyPolicyUrl"))
                    .attribute(BASE_URL_PROPERTY, json.getString("baseUrl"))
                    .build();
        } catch (JSONException ex) {
            throw new IOException("Failed to parse json string " + authString, ex);
        }
    }
}
