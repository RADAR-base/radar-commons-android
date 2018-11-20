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

import org.json.JSONArray;
import org.json.JSONException;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;

import static org.radarcns.android.auth.LoginManager.AUTH_TYPE_UNKNOWN;
import static org.radarcns.android.auth.portal.ManagementPortalClient.SOURCES_PROPERTY;

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
    private static final String LOGIN_ATTRIBUTES = "org.radarcns.android.auth.AppAuthState.attributes";
    private static final String LOGIN_HEADERS = "org.radarcns.android.auth.AppAuthState.headers";
    private static final String LOGIN_HEADERS_LIST = "org.radarcns.android.auth.AppAuthState.headerList";
    private static final String LOGIN_APP_SOURCES_LIST = "org.radarcns.android.auth.AppAuthState.appSourcesList";
    private static final String LOGIN_PRIVACY_POLICY_ACCEPTED = "org.radarcns.android.auth.AppAuthState.isPrivacyPolicyAccepted";
    private static final Logger logger = LoggerFactory.getLogger(AppAuthState.class);

    private final String projectId;
    private final String userId;
    private final String token;
    private final int tokenType;
    private long expiration;
    private long lastUpdate;
    private final Map<String, String> attributes;
    private final List<Map.Entry<String, String>> headers;
    private final ArrayList<SourceMetadata> sourceMetadata;
    private Boolean isPrivacyPolicyAccepted;

    private AppAuthState(Builder builder) {
        this.projectId = builder.projectId;
        this.userId = builder.userId;
        this.token = builder.token;
        this.tokenType = builder.tokenType;
        this.expiration = builder.expiration;
        this.attributes = builder.attributes;
        this.sourceMetadata = builder.sourceMetadata;
        this.headers = builder.headers;
        this.lastUpdate = builder.lastUpdate;
        this.isPrivacyPolicyAccepted = builder.isPrivacyPolicyAccepted;
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

    @Deprecated
    @Nullable
    public Serializable getProperty(@NonNull String key) {
        if (key.equals(SOURCES_PROPERTY)) {
            return sourceMetadata;
        } else {
            return attributes.get(key);
        }
    }

    @Nullable
    public String getAttribute(@NonNull String key) {
        return attributes.get(key);
    }

    @NonNull
    public ArrayList<SourceMetadata> getSourceMetadata() {
        return sourceMetadata;
    }

    private String serializableAttributeList() {
        return serializedMap(attributes.entrySet());
    }

    @Deprecated
    @NonNull
    public Map<String, ? extends Serializable> getProperties() {
        return attributes;
    }

    @NonNull
    public List<Map.Entry<String, String>> getHeaders() {
        return headers;
    }

    private String serializableHeaderList() {
        return serializedMap(headers);
    }

    private static String serializedMap(Collection<Map.Entry<String, String>> map) {
        JSONArray array = new JSONArray();
        for (Map.Entry<String, String> entry : map) {
            array.put(entry.getKey());
            array.put(entry.getValue());
        }
        return array.toString();
    }

    private static Map<String, String> deserializedMap(String jsonString) throws JSONException {
        JSONArray array = new JSONArray(jsonString);
        Map<String, String> map = new HashMap<>(array.length() * 4 / 6 + 1);
        for (int i = 0; i < array.length(); i += 2) {
            map.put(array.getString(i), array.getString(i + 1));
        }
        return map;
    }

    private static List<Map.Entry<String, String>> deserializedEntryList(String jsonString) throws JSONException {
        JSONArray array = new JSONArray(jsonString);
        List<Map.Entry<String, String>> list = new ArrayList<>(array.length() / 2);
        for (int i = 0; i < array.length(); i += 2) {
            list.add(new AbstractMap.SimpleImmutableEntry<>(array.getString(i), array.getString(i + 1)));
        }
        return list;
    }

    @NonNull
    public Boolean isPrivacyPolicyAccepted() {return isPrivacyPolicyAccepted;}

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
        bundle.putString(LOGIN_ATTRIBUTES, serializableAttributeList());
        ArrayList<String> jsonSources = new ArrayList<>(sourceMetadata.size());
        for (SourceMetadata source : sourceMetadata) {
            jsonSources.add(source.toJsonString());
        }
        bundle.putStringArrayList(LOGIN_APP_SOURCES_LIST, jsonSources);
        bundle.putString(LOGIN_HEADERS_LIST, serializableHeaderList());
        bundle.putString(LOGIN_TOKEN, token);
        bundle.putInt(LOGIN_TOKEN_TYPE, tokenType);
        bundle.putLong(LOGIN_EXPIRATION, expiration);
        bundle.putLong(LOGIN_UPDATE, lastUpdate);
        bundle.putBoolean(LOGIN_PRIVACY_POLICY_ACCEPTED, isPrivacyPolicyAccepted);
        return bundle;
    }

    public void addToPreferences(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
        Set<String> jsonSources = new HashSet<>(sourceMetadata.size());
        for (SourceMetadata source : sourceMetadata) {
            jsonSources.add(source.toJsonString());
        }

        prefs.edit()
                .putString(LOGIN_PROJECT_ID, projectId)
                .putString(LOGIN_USER_ID, userId)
                .putString(LOGIN_TOKEN, token)
                .putString(LOGIN_HEADERS_LIST, serializableHeaderList())
                .putString(LOGIN_ATTRIBUTES, serializableAttributeList())
                .putInt(LOGIN_TOKEN_TYPE, tokenType)
                .putLong(LOGIN_EXPIRATION, expiration)
                .putLong(LOGIN_UPDATE, lastUpdate)
                .putBoolean(LOGIN_PRIVACY_POLICY_ACCEPTED, isPrivacyPolicyAccepted)
                .remove(LOGIN_PROPERTIES)
                .remove(LOGIN_HEADERS)
                .putStringSet(LOGIN_APP_SOURCES_LIST, jsonSources)
                .remove(SOURCES_PROPERTY)
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
                logger.warn("Failed to deserialize object {} from preferences", key, ex);
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
                .attributes(attributes)
                .sourceMetadata(sourceMetadata)
                .tokenType(tokenType)
                .expiration(expiration)
                .headers(headers)
                .lastUpdate(lastUpdate)
                .privacyPolicyAccepted(isPrivacyPolicyAccepted);
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public static class Builder {
        private final ArrayList<Map.Entry<String, String>> headers = new ArrayList<>();
        private final ArrayList<SourceMetadata> sourceMetadata = new ArrayList<>();
        private final Map<String, String> attributes = new HashMap<>();

        private String projectId;
        private String userId;
        private String token;
        private int tokenType = AUTH_TYPE_UNKNOWN;
        private long expiration;
        private long lastUpdate = SystemClock.elapsedRealtime();
        private Boolean isPrivacyPolicyAccepted = false;

        @SuppressWarnings({"unchecked", "deprecation"})
        @NonNull
        public static Builder from(@NonNull Bundle bundle) {
            bundle.setClassLoader(AppAuthState.class.getClassLoader());
            Builder builder = new Builder();

            try {
                builder.properties((Map<String, ? extends Serializable>)bundle.getSerializable(LOGIN_PROPERTIES));
            } catch (Exception ex) {
                logger.warn("Cannot set AppAuthState properties", ex);
            }
            try {
                builder.headers((ArrayList<Map.Entry<String, String>>)bundle.getSerializable(LOGIN_HEADERS));
            } catch (Exception ex) {
                logger.warn("Cannot set AppAuthState headers", ex);
            }
            try {
                builder.appSources(bundle.getParcelableArrayList(SOURCES_PROPERTY));
            } catch (Exception ex) {
                logger.warn("Cannot deserialize app sources", ex);
            }
            try {
                builder.sourceMetadataJson(bundle.getStringArrayList(LOGIN_APP_SOURCES_LIST));
            } catch (Exception ex) {
                logger.warn("Cannot deserialize source metadata", ex);
            }
            return builder
                    .projectId(bundle.getString(LOGIN_PROJECT_ID, null))
                    .userId(bundle.getString(LOGIN_USER_ID))
                    .token(bundle.getString(LOGIN_TOKEN))
                    .tokenType(bundle.getInt(LOGIN_TOKEN_TYPE, 0))
                    .expiration(bundle.getLong(LOGIN_EXPIRATION, 0L))
                    .lastUpdate(bundle.getLong(LOGIN_UPDATE))
                    .privacyPolicyAccepted(bundle.getBoolean(LOGIN_PRIVACY_POLICY_ACCEPTED))
                    .attributes(bundle.getString(LOGIN_ATTRIBUTES))
                    .headers(bundle.getString(LOGIN_HEADERS_LIST));
        }

        @SuppressWarnings("unchecked")
        @NonNull
        public static Builder from(@NonNull Context context) {
            SharedPreferences prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);

            Builder builder = new Builder();

            try {
                builder.properties((HashMap<String, ? extends Serializable>)readSerializable(prefs, LOGIN_PROPERTIES));
            } catch (Exception ex) {
                logger.warn("Cannot read AppAuthState properties", ex);
            }
            try {
                builder.headers((ArrayList<Map.Entry<String, String>>)readSerializable(prefs, LOGIN_HEADERS));
            } catch (Exception ex) {
                logger.warn("Cannot read AppAuthState headers", ex);
            }
            try {
                builder.sourceMetadataJson(prefs.getStringSet(LOGIN_APP_SOURCES_LIST, null));
            } catch (JSONException ex) {
                logger.warn("Cannot parse source metadata headers", ex);
            }

            return builder
                    .projectId(prefs.getString(LOGIN_PROJECT_ID, null))
                    .userId(prefs.getString(LOGIN_USER_ID, null))
                    .token(prefs.getString(LOGIN_TOKEN, null))
                    .tokenType(prefs.getInt(LOGIN_TOKEN_TYPE, 0))
                    .expiration(prefs.getLong(LOGIN_EXPIRATION, 0L))
                    .lastUpdate(prefs.getLong(LOGIN_UPDATE, 0L))
                    .attributes(prefs.getString(LOGIN_ATTRIBUTES, null))
                    .headers(prefs.getString(LOGIN_HEADERS_LIST, null))
                    .privacyPolicyAccepted(prefs.getBoolean(LOGIN_PRIVACY_POLICY_ACCEPTED, false));
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

        @Deprecated
        public Builder clearProperties() {
            return this.clearAttributes();
        }

        @Deprecated
        public Builder property(@NonNull String key, @Nullable Serializable value) {
            if (value instanceof String) {
                this.attributes.put(key, (String) value);
            } else {
                throw new IllegalArgumentException("Cannot store non-string properties");
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        @Deprecated
        public Builder properties(@Nullable Map<String, ? extends Serializable> properties) {
            if (properties != null) {
                for (Map.Entry<String, ? extends Serializable> entry : properties.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (key.equals(SOURCES_PROPERTY)) {
                        appSources((List<AppSource>) value);
                    } else if (value instanceof String) {
                        this.attributes.put(key, (String) value);
                    } else {
                        logger.warn("Property {} no longer mapped in AppAuthState. Value discarded: {}", key, value);
                    }
                }
            }
            return this;
        }

        public Builder attributes(String jsonString) {
            if (jsonString != null) {
                try {
                    this.attributes.putAll(deserializedMap(jsonString));
                } catch (JSONException e) {
                    logger.warn("Cannot deserialize AppAuthState attributes: {}", e.toString());
                }
            }
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        public Builder attribute(@NonNull String key, @Nullable String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder clearAttributes() {
            this.attributes.clear();
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

        public Builder headers(String jsonString) {
            if (jsonString != null) {
                try {
                    this.headers.addAll(deserializedEntryList(jsonString));
                } catch (JSONException e) {
                    logger.warn("Cannot deserialize AppAuthState attributes: {}", e.toString());
                }
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

        public Builder privacyPolicyAccepted(Boolean isPrivacyPolicyAccepted) {
            this.isPrivacyPolicyAccepted = isPrivacyPolicyAccepted;
            return this;
        }

        @Deprecated
        public Builder appSources(List<AppSource> appSources) {
            if (appSources == null) {
                return this;
            }
            List<SourceMetadata> metadata = new ArrayList<>(appSources.size());
            for (AppSource source : appSources) {
                metadata.add(new SourceMetadata(source));
            }
            return sourceMetadata(metadata);
        }

        public Builder sourceMetadata(List<SourceMetadata> sourceMetadata) {
            if (sourceMetadata != null) {
                this.sourceMetadata.clear();
                this.sourceMetadata.addAll(sourceMetadata);
            }
            return this;
        }

        public Builder sourceMetadataJson(Collection<String> sourceJson) throws JSONException {
            if (sourceJson != null) {
                this.sourceMetadata.clear();
                for (String s : sourceJson) {
                    sourceMetadata.add(new SourceMetadata(s));
                }
            }
            return this;
        }

        public AppAuthState build() {
            return new AppAuthState(this);
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
                ", \nattributes=" + attributes +
                ", \nsourceMetadata=" + sourceMetadata +
                ", \nheaders=" + headers +
                ", \nisPrivacyPolicyAccepted=" + isPrivacyPolicyAccepted +
                "\n";
    }
}
