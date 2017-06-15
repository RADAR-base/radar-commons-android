/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.android.auth;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.support.annotation.AnyThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.RegistrationResponse;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenResponse;

import org.json.JSONException;
import org.radarcns.android.RadarConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;

import static org.radarcns.android.RadarConfiguration.OAUTH2_AUTHORIZE_URL;
import static org.radarcns.android.RadarConfiguration.OAUTH2_CLIENT_ID;
import static org.radarcns.android.RadarConfiguration.OAUTH2_TOKEN_URL;

public class OAuth2StateManager {
    private static final int OAUTH_INTENT_HANDLER_REQUEST_CODE = 8422341;

    private static WeakReference<OAuth2StateManager> INSTANCE_REF = new WeakReference<>(null);
    private static final Object INSTANCE_SYNC_OBJECT = new Object();
    private static final Logger logger = LoggerFactory.getLogger(OAuth2StateManager.class);

    private static final String STORE_NAME = "AuthState";
    private static final String KEY_STATE = "state";

    private final SharedPreferences mPrefs;
    private AuthState mCurrentAuthState;

    @AnyThread
    public static OAuth2StateManager getInstance(@NonNull Context context) {
        synchronized (INSTANCE_SYNC_OBJECT) {
            OAuth2StateManager manager = INSTANCE_REF.get();
            if (manager == null) {
                manager = new OAuth2StateManager(context.getApplicationContext());
                INSTANCE_REF = new WeakReference<>(manager);
            }
            return manager;
        }
    }

    private OAuth2StateManager(Context context) {
        mPrefs = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE);
        mCurrentAuthState = readState();
    }

    @AnyThread
    @NonNull
    public synchronized AuthState getAuthState() {
        return mCurrentAuthState;
    }

    @AnyThread
    public synchronized void login(@NonNull LoginActivity context, @NonNull RadarConfiguration config) {
        Uri authorizeUri = Uri.parse(config.getString(OAUTH2_AUTHORIZE_URL));
        Uri tokenUri = Uri.parse(config.getString(OAUTH2_TOKEN_URL));
        String clientId = config.getString(OAUTH2_CLIENT_ID);

        AuthorizationServiceConfiguration authConfig =
                new AuthorizationServiceConfiguration(authorizeUri, tokenUri, null);

        AuthorizationRequest.Builder authRequestBuilder =
                new AuthorizationRequest.Builder(
                        authConfig, // the authorization service configuration
                        clientId, // the client ID, typically pre-registered and static
                        ResponseTypeValues.CODE, // the response_type value: we want a code
                        Uri.parse("org.radarcns.android:/oauth2redirect/radar")); // the redirect URI to which the auth response is sent

        AuthorizationService service = new AuthorizationService(context);
        service.performAuthorizationRequest(
                authRequestBuilder.build(),
                PendingIntent.getActivity(context,
                        OAUTH_INTENT_HANDLER_REQUEST_CODE,
                        new Intent(context, context.getClass()),
                        PendingIntent.FLAG_ONE_SHOT));
    }

    @AnyThread
    public synchronized void updateAfterAuthorization(@NonNull final LoginActivity context,
                                                      @Nullable Intent intent) {
        if (intent == null) {
            return;
        }

        AuthorizationResponse resp = AuthorizationResponse.fromIntent(intent);
        AuthorizationException ex = AuthorizationException.fromIntent(intent);

        if (resp != null || ex != null) {
            mCurrentAuthState.update(resp, ex);
            writeState(mCurrentAuthState);
        }

        if (resp != null) {
            AuthorizationService service = new AuthorizationService(context);
            // authorization succeeded
            service.performTokenRequest(
                    resp.createTokenExchangeRequest(),
                    new AuthorizationService.TokenResponseCallback() {
                        @Override public void onTokenRequestCompleted(
                                TokenResponse resp, AuthorizationException ex) {
                            if (resp != null) {
                                updateAfterTokenResponse(resp, ex);
                                context.oauthSucceeded();
                            } else {
                                context.oauthFailed(ex);
                            }
                        }
                    });
        } else if (ex != null) {
            context.oauthFailed(ex);
        }
    }

    @AnyThread
    public synchronized void updateAfterTokenResponse(
            @Nullable TokenResponse response,
            @Nullable AuthorizationException ex) {
        mCurrentAuthState.update(response, ex);
        writeState(mCurrentAuthState);
    }

    @AnyThread
    public synchronized void updateAfterRegistration(
            RegistrationResponse response,
            AuthorizationException ex) {
        if (ex == null) {
            mCurrentAuthState.update(response);
            writeState(mCurrentAuthState);
        }
    }

    @AnyThread
    @NonNull
    private synchronized AuthState readState() {
        String currentState = mPrefs.getString(KEY_STATE, null);
        if (currentState == null) {
            return new AuthState();
        }

        try {
            return AuthState.jsonDeserialize(currentState);
        } catch (JSONException ex) {
            logger.warn("Failed to deserialize stored auth state - discarding", ex);
            writeState(null);
            return new AuthState();
        }
    }

    @AnyThread
    private synchronized void writeState(@Nullable AuthState state) {
        if (state != null) {
            mPrefs.edit().putString(KEY_STATE, state.jsonSerializeString()).apply();
        } else {
            mPrefs.edit().remove(KEY_STATE).apply();
        }
    }
}
