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

package org.radarcns.android.auth.oauth2;

import android.content.Intent;
import android.support.annotation.NonNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginActivity;
import org.radarcns.android.auth.LoginListener;
import org.radarcns.android.auth.LoginManager;

/**
 * Authenticates against the RADAR Management Portal.
 */
public class OAuth2LoginManager implements LoginManager, LoginListener {
    public static final String LOGIN_REFRESH_TOKEN = "org.radarcns.auth.OAuth2LoginManager.refreshToken";
    private final String projectIdClaim;
    private final String userIdClaim;
    private final LoginActivity activity;
    private AppAuthState authState;

    public OAuth2LoginManager(LoginActivity activity, String projectIdClaim, String userIdClaim,
            AppAuthState authState) {
        this.activity = activity;
        this.projectIdClaim = projectIdClaim;
        this.userIdClaim = userIdClaim;
        this.authState = authState;
    }

    public void update(AppAuthState state) {
        this.authState = state;
    }

    @Override
    public AppAuthState refresh() {
        if (authState.isValid()) {
            return authState;
        }
        if (authState.getTokenType() == LoginManager.AUTH_TYPE_BEARER
                && authState.getProperty(LOGIN_REFRESH_TOKEN) != null) {
            OAuth2StateManager.getInstance(activity).refresh(activity,
                    (String) authState.getProperties().get(LOGIN_REFRESH_TOKEN));
        }
        return null;
    }

    @Override
    public void start() {
        OAuth2StateManager.getInstance(activity).login(activity, RadarConfiguration.getInstance());
    }

    @Override
    public void onActivityCreate() {
        OAuth2StateManager.getInstance(activity).updateAfterAuthorization(activity);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // noop
    }

    @Override
    public void loginSucceeded(LoginManager manager, @NonNull AppAuthState appAuthState) {
        try {
            AppAuthState.Builder authStateBuilder = processJwt(
                    appAuthState.newBuilder(),
                    Jwt.parse(appAuthState.getToken()));

            this.activity.loginSucceeded(this, authStateBuilder.build());
        } catch (JSONException ex) {
            this.activity.loginFailed(this, ex);
        }
    }

    protected AppAuthState.Builder processJwt(AppAuthState.Builder builder, Jwt jwt) throws JSONException {
        JSONObject body = jwt.getBody();

        return builder
                .projectId(body.optString(projectIdClaim))
                .userId(body.optString(userIdClaim))
                .expiration(body.optLong("exp", Long.MAX_VALUE / 1000L) * 1000L);
    }

    @Override
    public void loginFailed(LoginManager manager, Exception ex) {
        this.activity.loginFailed(this, ex);
    }
}
