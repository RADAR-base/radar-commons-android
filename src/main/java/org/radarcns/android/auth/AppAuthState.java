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
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Authentication state of the application. */
public final class AppAuthState {
    public static final String LOGIN_USER_ID = "org.radarcns.android.auth.AppAuthState.userId";
    public static final String LOGIN_TOKEN = "org.radarcns.android.auth.AppAuthState.token";
    public static final String LOGIN_TOKEN_TYPE = "org.radarcns.android.auth.AppAuthState.tokenType";
    public static final String LOGIN_EXPIRATION = "org.radarcns.android.auth.AppAuthState.expiration";
    public static final String LOGIN_UPDATE = "org.radarcns.android.auth.AppAuthState.lastUpdate";
    private static final String AUTH_PREFS = "org.radarcns.auth";
    private static final String LOGIN_PROPERTIES = "org.radarcns.android.auth.AppAuthState.properties";
    private static final String LOGIN_HEADERS = "org.radarcns.android.auth.AppAuthState.headers";
    private static final Logger logger = LoggerFactory.getLogger(AppAuthState.class);

    private final String userId;
    private final String token;
    private final int tokenType;
    private long expiration;
    private long lastUpdate;
    private final HashMap<String, String> properties;
    private final ArrayList<Map.Entry<String, String>> headers;

    public AppAuthState(@Nullable String userId, @Nullable String token,
                        @Nullable HashMap<String, String> properties, int tokenType, long expiration,
                        @Nullable ArrayList<Map.Entry<String, String>> headers) {
        this(userId, token, properties, tokenType, expiration, headers, SystemClock.elapsedRealtime());
    }

    private AppAuthState(@Nullable String userId, @Nullable String token,
                        @Nullable HashMap<String, String> properties, int tokenType, long expiration,
                        @Nullable ArrayList<Map.Entry<String, String>> headers, long lastUpdate) {
        this.userId = userId;
        this.token = token;
        this.tokenType = tokenType;
        this.expiration = expiration;
        this.properties = properties == null ? new HashMap<String, String>() : properties;
        this.headers = headers == null ? new ArrayList<Map.Entry<String, String>>() : headers;
        this.lastUpdate = lastUpdate;
    }

    @SuppressWarnings("unchecked")
    public AppAuthState(@NonNull Bundle bundle) {
        this(bundle.getString(LOGIN_USER_ID),
                bundle.getString(LOGIN_TOKEN),
                (HashMap<String, String>)bundle.getSerializable(LOGIN_PROPERTIES),
                bundle.getInt(LOGIN_TOKEN_TYPE, 0), bundle.getLong(LOGIN_EXPIRATION, 0L),
                (ArrayList<Map.Entry<String, String>>)bundle.getSerializable(LOGIN_HEADERS),
                bundle.getLong(LOGIN_UPDATE));
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

    @Nullable
    public String getProperty(@NonNull String key) {
        if (properties == null) {
            return null;
        }
        return properties.get(key);
    }

    @Nullable
    public HashMap<String, String> getProperties() {
        return properties;
    }

    @Nullable
    public ArrayList<Map.Entry<String, String>> getHeaders() {
        return headers;
    }

    public boolean isValid() {
        return expiration > System.currentTimeMillis();
    }

    public boolean isInvalidated() { return expiration == 0L; }

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
        bundle.putSerializable(LOGIN_PROPERTIES, properties);
        bundle.putSerializable(LOGIN_HEADERS, headers);
        bundle.putString(LOGIN_TOKEN, token);
        bundle.putInt(LOGIN_TOKEN_TYPE, tokenType);
        bundle.putLong(LOGIN_EXPIRATION, expiration);
        bundle.putLong(LOGIN_UPDATE, lastUpdate);
        return bundle;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    public static AppAuthState read(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);

        HashMap<String, String> props = (HashMap<String, String>)readSerializable(prefs, LOGIN_PROPERTIES);
        ArrayList<Map.Entry<String, String>> headers = (ArrayList<Map.Entry<String, String>>)
                readSerializable(prefs, LOGIN_HEADERS);

        return new AppAuthState(prefs.getString(LOGIN_USER_ID, null),
                prefs.getString(LOGIN_TOKEN, null), props,
                prefs.getInt(LOGIN_TOKEN_TYPE, 0), prefs.getLong(LOGIN_EXPIRATION, 0L), headers,
                prefs.getLong(LOGIN_UPDATE, 0L));
    }

    public void store(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(LOGIN_USER_ID, userId)
                .putString(LOGIN_TOKEN, token)
                .putString(LOGIN_HEADERS, getSerialization(LOGIN_HEADERS, headers))
                .putString(LOGIN_PROPERTIES, getSerialization(LOGIN_PROPERTIES, properties))
                .putInt(LOGIN_TOKEN_TYPE, tokenType)
                .putLong(LOGIN_EXPIRATION, expiration)
                .putLong(LOGIN_UPDATE, lastUpdate)
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
    private static String getSerialization(@NonNull String key, @Nullable Serializable value) {
        if (value != null) {
            try (ByteArrayOutputStream bo = new ByteArrayOutputStream();
                 ObjectOutputStream so = new ObjectOutputStream(bo)) {
                so.writeObject(value);
                so.flush();
                byte[] bytes = bo.toByteArray();
                return Base64.encodeToString(bytes, Base64.NO_WRAP);
            } catch (IOException e) {
                logger.warn("Failed to serialize object {} to preferences", key);
            }
        }
        return null;
    }

    @Nullable
    private static Object readSerializable(@NonNull SharedPreferences prefs, @NonNull String key) {
        String propString = prefs.getString(key, null);
        if (propString != null) {
            byte[] propBytes = Base64.decode(propString, Base64.NO_WRAP);
            try (ByteArrayInputStream bi = new ByteArrayInputStream(propBytes);
                 ObjectInputStream si = new ObjectInputStream(bi)) {
                return si.readObject();
            } catch (IOException | ClassNotFoundException ex) {
                logger.warn("Failed to deserialize object {} from preferences", key);
            }
        }
        return null;
    }

    public long timeSinceLastUpdate() {
        return SystemClock.elapsedRealtime() - lastUpdate;
    }
}
