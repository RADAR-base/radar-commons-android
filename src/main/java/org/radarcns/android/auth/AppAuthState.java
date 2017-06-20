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
import android.content.SharedPreferences;

/**
 * Created by joris on 20/06/2017.
 */

public final class AppAuthState {
    public static final String LOGIN_USER_ID = "org.radarcns.android.auth.AppAuthState.userId";
    public static final String LOGIN_TOKEN = "org.radarcns.android.auth.AppAuthState.token";
    public static final String LOGIN_TOKEN_TYPE = "org.radarcns.android.auth.AppAuthState.tokenType";
    public static final String LOGIN_EXPIRATION = "org.radarcns.android.auth.AppAuthState.expiration";

    private final String userId;
    private final String token;
    private final int tokenType;
    private final long expiration;

    public AppAuthState(String userId, String token, int tokenType, long expiration) {
        this.userId = userId;
        this.token = token;
        this.tokenType = tokenType;
        this.expiration = expiration;
    }

    public AppAuthState(Intent intent) {
        this(intent.getStringExtra(LOGIN_USER_ID),
                intent.getStringExtra(LOGIN_TOKEN),
                intent.getIntExtra(LOGIN_TOKEN_TYPE, 0),
                intent.getLongExtra(LOGIN_EXPIRATION, 0L));
    }

    public AppAuthState(SharedPreferences prefs) {
        this(prefs.getString(LOGIN_USER_ID, null),
                prefs.getString(LOGIN_TOKEN, null),
                prefs.getInt(LOGIN_TOKEN_TYPE, 0),
                prefs.getLong(LOGIN_EXPIRATION, 0L));
    }

    public String getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public int getTokenType() {
        return tokenType;
    }

    public boolean isExpired() {
        return expiration > System.currentTimeMillis();
    }

    public Intent toIntent() {
        Intent intent = new Intent();
        intent.putExtra(LOGIN_USER_ID, userId);
        intent.putExtra(LOGIN_TOKEN, token);
        intent.putExtra(LOGIN_TOKEN_TYPE, tokenType);
        return intent;
    }

    public void store(SharedPreferences prefs) {
        prefs.edit()
                .putString(LOGIN_USER_ID, userId)
                .putString(LOGIN_TOKEN, token)
                .putInt(LOGIN_TOKEN_TYPE, tokenType)
                .putLong(LOGIN_EXPIRATION, expiration)
                .apply();
    }
}
