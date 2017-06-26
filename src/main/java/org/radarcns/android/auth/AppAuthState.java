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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

/** Authentication state of the application. */
public final class AppAuthState {
    public static final String LOGIN_USER_ID = "org.radarcns.android.auth.AppAuthState.userId";
    public static final String LOGIN_TOKEN = "org.radarcns.android.auth.AppAuthState.token";
    public static final String LOGIN_TOKEN_TYPE = "org.radarcns.android.auth.AppAuthState.tokenType";
    public static final String LOGIN_EXPIRATION = "org.radarcns.android.auth.AppAuthState.expiration";
    public static final String LOGIN_REFRESH_TOKEN = "org.radarcns.android.auth.AppAuthState.refreshToken";
    private static final String AUTH_PREFS = "org.radarcns.auth";

    private final String userId;
    private final String token;
    private final String refreshToken;
    private final int tokenType;
    private long expiration;

    public AppAuthState(@Nullable String userId, @Nullable String token,
                        @Nullable String refreshToken, int tokenType, long expiration) {
        this.userId = userId;
        this.token = token;
        this.refreshToken = refreshToken;
        this.tokenType = tokenType;
        this.expiration = expiration;
    }

    public AppAuthState(@NonNull Bundle bundle) {
        this(bundle.getString(LOGIN_USER_ID),
                bundle.getString(LOGIN_TOKEN), bundle.getString(LOGIN_REFRESH_TOKEN),
                bundle.getInt(LOGIN_TOKEN_TYPE, 0), bundle.getLong(LOGIN_EXPIRATION, 0L));
    }

    @Nullable
    public String getUserId() {
        return userId;
    }

    @Nullable
    public String getToken() {
        return token;
    }

    public int getTokenType() {
        return tokenType;
    }

    public boolean isValid() {
        return expiration > System.currentTimeMillis();
    }

    /** Convert the state into the extras of a new Intent. */
    @NonNull
    public Intent toIntent() {
        Intent intent = new Intent();
        intent.putExtras(addToBundle(new Bundle()));
        return intent;
    }

    /**
     * Add the authentication state to a bundle.
     * @param bundle bundle to add state to
     * @return bundle that the state was added to
     */
    @NonNull
    public Bundle addToBundle(@NonNull Bundle bundle) {
        bundle.putString(LOGIN_USER_ID, userId);
        bundle.putString(LOGIN_TOKEN, token);
        bundle.putString(LOGIN_REFRESH_TOKEN, refreshToken);
        bundle.putInt(LOGIN_TOKEN_TYPE, tokenType);
        bundle.putLong(LOGIN_EXPIRATION, expiration);
        return bundle;
    }

    @NonNull
    public static AppAuthState read(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        return new AppAuthState(prefs.getString(LOGIN_USER_ID, null),
                prefs.getString(LOGIN_TOKEN, null), prefs.getString(LOGIN_REFRESH_TOKEN, null),
                prefs.getInt(LOGIN_TOKEN_TYPE, 0), prefs.getLong(LOGIN_EXPIRATION, 0L));
    }

    public void store(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(LOGIN_USER_ID, userId)
                .putString(LOGIN_TOKEN, token)
                .putString(LOGIN_REFRESH_TOKEN, refreshToken)
                .putInt(LOGIN_TOKEN_TYPE, tokenType)
                .putLong(LOGIN_EXPIRATION, expiration)
                .apply();
    }

    public void invalidate(@NonNull Context context) {
        expiration = 0L;
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(LOGIN_EXPIRATION)
                .apply();
    }

    @Nullable
    public String getRefreshToken() {
        return refreshToken;
    }
}
