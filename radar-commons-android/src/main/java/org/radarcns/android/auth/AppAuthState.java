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
import okhttp3.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.radarcns.android.auth.LoginManager.AUTH_TYPE_UNKNOWN;

/** Authentication state of the application. */
@SuppressWarnings("unused")
public final class AppAuthState {
    public static final String LOGIN_PROJECT_ID = "org.radarcns.android.auth.AppAuthState.projectId";
    public static final String LOGIN_USER_ID = "org.radarcns.android.auth.AppAuthState.userId";
    public static final String LOGIN_TOKEN = "org.radarcns.android.auth.AppAuthState.token";
    public static final String LOGIN_TOKEN_TYPE = "org.radarcns.android.auth.AppAuthState.tokenType";
    public static final String LOGIN_EXPIRATION = "org.radarcns.android.auth.AppAuthState.expiration";
    public static final String LOGIN_UPDATE = "org.radarcns.android.auth.AppAuthState.lastUpdate";
    private static final String AUTH_PREFS = "org.radarcns.auth";
    private static final String LOGIN_PROPERTIES = "org.radarcns.android.auth.AppAuthState.properties";
    private static final String LOGIN_HEADERS = "org.radarcns.android.auth.AppAuthState.headers";
    private static final Logger logger = LoggerFactory.getLogger(AppAuthState.class);

    private final String projectId;
    private final String userId;
    private final String token;
    private final int tokenType;
    private long expiration;
    private long lastUpdate;
    private final Map<String, ? extends Serializable> properties;
    private final List<Map.Entry<String, String>> headers;

    private AppAuthState(String projectId, String userId,
            String token, Map<String, ? extends Serializable> properties, int tokenType,
            long expiration, List<Map.Entry<String, String>> headers,
            long lastUpdate) {
        this.projectId = projectId;
        this.userId = userId;
        this.token = token;
        this.tokenType = tokenType;
        this.expiration = expiration;
        this.properties = properties;
        this.headers = headers;
        this.lastUpdate = lastUpdate;
    }

    @Nullable
    public String getProjectId() {
        return projectId;
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
    public Serializable getProperty(@NonNull String key) {
        return properties.get(key);
    }

    @NonNull
    public Map<String, ? extends Serializable> getProperties() {
        return properties;
    }

    @NonNull
    public List<Map.Entry<String, String>> getHeaders() {
        return headers;
    }

    @NonNull
    public Headers getOkHttpHeaders() {
        Headers.Builder builder = new Headers.Builder();
        for (Map.Entry<String, String> header : headers) {
            builder.add(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    public boolean isValid() {
        return expiration > System.currentTimeMillis();
    }

    public boolean isValidFor(long time, TimeUnit unit) {
        return expiration - unit.toMillis(time) > System.currentTimeMillis();
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
        bundle.putString(LOGIN_PROJECT_ID, projectId);
        bundle.putString(LOGIN_USER_ID, userId);
        bundle.putSerializable(LOGIN_PROPERTIES, new HashMap<>(properties));
        bundle.putSerializable(LOGIN_HEADERS, new ArrayList<>(headers));
        bundle.putString(LOGIN_TOKEN, token);
        bundle.putInt(LOGIN_TOKEN_TYPE, tokenType);
        bundle.putLong(LOGIN_EXPIRATION, expiration);
        bundle.putLong(LOGIN_UPDATE, lastUpdate);
        return bundle;
    }

    public void addToPreferences(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(LOGIN_PROJECT_ID, projectId)
                .putString(LOGIN_USER_ID, userId)
                .putString(LOGIN_TOKEN, token)
                .putString(LOGIN_HEADERS, getSerialization(LOGIN_HEADERS, new ArrayList<>(headers)))
                .putString(LOGIN_PROPERTIES, getSerialization(LOGIN_PROPERTIES, new HashMap<>(properties)))
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

    public Builder newBuilder() {
        return new Builder()
                .projectId(projectId)
                .userId(userId)
                .token(token)
                .properties(properties)
                .tokenType(tokenType)
                .expiration(expiration)
                .headers(headers)
                .lastUpdate(lastUpdate);
    }

    public static class Builder {
        private final HashMap<String, Serializable> properties = new HashMap<>();
        private final ArrayList<Map.Entry<String, String>> headers = new ArrayList<>();

        private String projectId;
        private String userId;
        private String token;
        private int tokenType = AUTH_TYPE_UNKNOWN;
        private long expiration;
        private long lastUpdate = SystemClock.elapsedRealtime();

        @SuppressWarnings("unchecked")
        @NonNull
        public static Builder from(@NonNull Bundle bundle) {
            bundle.setClassLoader(AppAuthState.class.getClassLoader());
            return new Builder()
                    .projectId(bundle.getString(LOGIN_PROJECT_ID, null))
                    .userId(bundle.getString(LOGIN_USER_ID))
                    .token(bundle.getString(LOGIN_TOKEN))
                    .properties((HashMap<String, Serializable>)bundle.getSerializable(LOGIN_PROPERTIES))
                    .tokenType(bundle.getInt(LOGIN_TOKEN_TYPE, 0))
                    .expiration(bundle.getLong(LOGIN_EXPIRATION, 0L))
                    .headers((ArrayList<Map.Entry<String, String>>)bundle.getSerializable(LOGIN_HEADERS))
                    .lastUpdate(bundle.getLong(LOGIN_UPDATE));
        }

        @SuppressWarnings("unchecked")
        @NonNull
        public static Builder from(@NonNull Context context) {
            SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);

            HashMap<String, String> props = (HashMap<String, String>)readSerializable(prefs, LOGIN_PROPERTIES);
            ArrayList<Map.Entry<String, String>> headers = (ArrayList<Map.Entry<String, String>>)
                    readSerializable(prefs, LOGIN_HEADERS);

            return new Builder()
                    .projectId(prefs.getString(LOGIN_PROJECT_ID, null))
                    .userId(prefs.getString(LOGIN_USER_ID, null))
                    .token(prefs.getString(LOGIN_TOKEN, null))
                    .properties(props)
                    .tokenType(prefs.getInt(LOGIN_TOKEN_TYPE, 0))
                    .expiration(prefs.getLong(LOGIN_EXPIRATION, 0L))
                    .headers(headers)
                    .lastUpdate(prefs.getLong(LOGIN_UPDATE, 0L));
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder clearProperties() {
            this.properties.clear();
            return this;
        }

        public Builder property(@NonNull String key, @Nullable Serializable value) {
            this.properties.put(key, value);
            return this;
        }

        public Builder properties(Map<String, ? extends Serializable> properties) {
            if (properties != null) {
                this.properties.putAll(properties);
            }
            return this;
        }

        public Builder tokenType(int tokenType) {
            this.tokenType = tokenType;
            return this;
        }

        public Builder expiration(long expiration) {
            this.expiration = expiration;
            return this;
        }

        public Builder setHeader(@NonNull String name, @NonNull String value) {
            Iterator<Map.Entry<String, String>> iterator = this.headers.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getKey().equals(name)) {
                    iterator.remove();
                }
            }
            return header(name, value);
        }

        public Builder header(@NonNull String name, @NonNull String value) {
            this.headers.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
            return this;
        }

        public Builder headers(Collection<Map.Entry<String, String>> headers) {
            if (headers != null) {
                this.headers.addAll(headers);
            }
            return this;
        }

        public Builder clearHeaders() {
            this.headers.clear();
            return this;
        }

        public Builder lastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
            return this;
        }

        public AppAuthState build() {
            return new AppAuthState(projectId, userId, token,
                    Collections.unmodifiableMap(properties), tokenType, expiration,
                    Collections.unmodifiableList(headers), lastUpdate);
        }
    }

    @Override
    public String toString() {
        return "AppAuthState{" + "projectId='" + projectId + '\'' +
                ", \nuserId='" + userId + '\'' +
                ", \ntoken='" + token + '\'' +
                ", \ntokenType=" + tokenType +
                ", \nexpiration=" + expiration +
                ", \nlastUpdate=" + lastUpdate +
                ", \nproperties=" + properties +
                ", \nheaders=" + headers +
                "\n";
    }
}
