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

import android.content.Intent;
import android.support.annotation.NonNull;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

/**
 * Authenticates against the RADAR Management Portal.
 */

public class OAuth2LoginManager implements LoginManager, LoginListener {
    private final String userIdClaim;
    private final LoginActivity activity;
    private final AppAuthState authState;

    public OAuth2LoginManager(LoginActivity activity, String userIdClaim, AppAuthState authState) {
        this.authState = authState;
        this.activity = activity;
        this.userIdClaim = userIdClaim;
    }

    @Override
    public AppAuthState refresh() {
        if (authState.isValid()) {
            return authState;
        }
        if (authState.getTokenType() == AUTH_TYPE_BEARER && authState.getRefreshToken() != null) {
            OAuth2StateManager.getInstance(activity).refresh(activity);
        }
        return null;
    }

    @Override
    public void start() {
        OAuth2StateManager.getInstance(activity).login(activity, activity.getConfig());
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
            Jwt<Header, Claims> jwt = Jwts.parser().parseClaimsJwt(appAuthState.getToken());
            String userId = (String) jwt.getBody().get(userIdClaim);
            long expiration = jwt.getBody().getExpiration().getTime();

            appAuthState = new AppAuthState(userId, appAuthState.getToken(),
                    appAuthState.getRefreshToken(), AUTH_TYPE_BEARER, expiration);
            this.activity.loginSucceeded(this, appAuthState);
        } catch (JwtException ex) {
            this.activity.loginFailed(this, ex);
        }
    }

    @Override
    public void loginFailed(LoginManager manager, Exception ex) {
        this.activity.loginFailed(this, ex);
    }
}
