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

package org.radarcns.android;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;

import com.crashlytics.android.Crashlytics;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.portal.GetSubjectParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static org.radarcns.android.auth.portal.ManagementPortalClient.BASE_URL_PROPERTY;

@SuppressWarnings("WeakerAccess")
public class RadarConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(RadarConfiguration.class);
    private static final long FIREBASE_FETCH_TIMEOUT_MS_DEFAULT = 12*60*60 * 1000L;

    public static final String RADAR_PREFIX = "org.radarcns.android.";

    public static final String RADAR_CONFIGURATION_CHANGED = RadarConfiguration.class.getSimpleName() + ".CHANGED";

    public static final String KAFKA_REST_PROXY_URL_KEY = "kafka_rest_proxy_url";
    public static final String SCHEMA_REGISTRY_URL_KEY = "schema_registry_url";
    public static final String MANAGEMENT_PORTAL_URL_KEY = "management_portal_url";
    public static final String PROJECT_ID_KEY = "radar_project_id";
    public static final String USER_ID_KEY = "radar_user_id";
    public static final String READABLE_USER_ID_KEY = "readable_user_id";
    public static final String BASE_URL_KEY = "radar_base_url";
    public static final String SOURCE_ID_KEY = "source_id";
    public static final String SEND_OVER_DATA_HIGH_PRIORITY = "send_over_data_high_priority_only";
    public static final String TOPICS_HIGH_PRIORITY = "topics_high_priority";
    public static final String UI_REFRESH_RATE_KEY = "ui_refresh_rate_millis";
    public static final String KAFKA_UPLOAD_RATE_KEY = "kafka_upload_rate";
    public static final String DATABASE_COMMIT_RATE_KEY = "database_commit_rate";
    public static final String KAFKA_RECORDS_SEND_LIMIT_KEY = "kafka_records_send_limit";
    public static final String KAFKA_RECORDS_SIZE_LIMIT_KEY = "kafka_records_size_limit";
    public static final String SENDER_CONNECTION_TIMEOUT_KEY = "sender_connection_timeout";
    public static final String DATA_RETENTION_KEY = "data_retention_ms";
    public static final String FIREBASE_FETCH_TIMEOUT_MS_KEY = "firebase_fetch_timeout_ms";
    public static final String START_AT_BOOT = "start_at_boot";
    public static final String DEVICE_SERVICES_TO_CONNECT = "device_services_to_connect";
    public static final String KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL = "kafka_upload_minimum_battery_level";
    public static final String MAX_CACHE_SIZE = "cache_max_size_bytes";
    public static final String SEND_ONLY_WITH_WIFI = "send_only_with_wifi";
    public static final String SEND_BINARY_CONTENT = "send_binary_content";
    public static final String SEND_WITH_COMPRESSION = "send_with_compression";
    public static final String UNSAFE_KAFKA_CONNECTION = "unsafe_kafka_connection";
    public static final String OAUTH2_AUTHORIZE_URL = "oauth2_authorize_url";
    public static final String OAUTH2_TOKEN_URL = "oauth2_token_url";
    public static final String OAUTH2_REDIRECT_URL = "oauth2_redirect_url";
    public static final String OAUTH2_CLIENT_ID = "oauth2_client_id";
    public static final String OAUTH2_CLIENT_SECRET = "oauth2_client_secret";

    public static final boolean SEND_ONLY_WITH_WIFI_DEFAULT = true;
    public static final boolean SEND_OVER_DATA_HIGH_PRIORITY_DEFAULT = true;
    public static final boolean SEND_BINARY_CONTENT_DEFAULT = true;

    private static final Pattern IS_TRUE = Pattern.compile(
            "^(1|true|t|yes|y|on)$", CASE_INSENSITIVE);
    private static final Pattern IS_FALSE = Pattern.compile(
            "^(0|false|f|no|n|off|)$", CASE_INSENSITIVE);
    private final OnFailureListener onFailureListener;
    private final AtomicBoolean hasChange;

    public FirebaseStatus getStatus() {
        return status;
    }

    public enum FirebaseStatus {
        UNAVAILABLE, ERROR, READY, FETCHING, FETCHED
    }

    private static RadarConfiguration instance = null;
    private final FirebaseRemoteConfig config;
    private FirebaseStatus status;

    private final Handler handler;
    private final OnCompleteListener<Void> onFetchCompleteHandler;
    private final Map<String, String> localConfiguration;
    private final Runnable persistChanges;

    private RadarConfiguration(@NonNull final Context context, @NonNull FirebaseRemoteConfig config) {
        this.config = config;

        this.localConfiguration = new ConcurrentHashMap<>();
        this.handler = new Handler();
        this.hasChange = new AtomicBoolean(false);

        this.onFetchCompleteHandler = task -> {
            if (task.isSuccessful()) {
                setStatus(FirebaseStatus.FETCHED);
                // Once the config is successfully fetched it must be
                // activated before newly fetched values are returned.
                activateFetched();

                // Set global properties.
                logger.info("RADAR configuration changed: {}", RadarConfiguration.this);
                LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(new Intent(RadarConfiguration.RADAR_CONFIGURATION_CHANGED));
            } else {
                setStatus(FirebaseStatus.ERROR);
                logger.warn("Remote Config: Fetch failed. Stacktrace: {}", task.getException());
            }
        };

        this.onFailureListener = ex -> {
            logger.info("Failed to fetch Firebase config");
            setStatus(FirebaseStatus.ERROR);
            LocalBroadcastManager.getInstance(context)
                    .sendBroadcast(new Intent(RadarConfiguration.RADAR_CONFIGURATION_CHANGED));
        };

        final SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(getClass().getName(), Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof String) {
                this.localConfiguration.put(entry.getKey(), (String) value);
            }
        }

        persistChanges = () -> {
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, String> entry : localConfiguration.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            editor.apply();

            if (hasChange.compareAndSet(true, false)) {
                logger.info("RADAR configuration changed: {}", RadarConfiguration.this);
                LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(new Intent(RadarConfiguration.RADAR_CONFIGURATION_CHANGED));
            }
        };

        GoogleApiAvailability googleApi = GoogleApiAvailability.getInstance();
        if (googleApi.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            status = FirebaseStatus.READY;
            this.handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fetch();
                    long delay = getLong(FIREBASE_FETCH_TIMEOUT_MS_KEY, FIREBASE_FETCH_TIMEOUT_MS_DEFAULT);
                    handler.postDelayed(this, delay);
                }
            }, getLong(FIREBASE_FETCH_TIMEOUT_MS_KEY, FIREBASE_FETCH_TIMEOUT_MS_DEFAULT));
        } else {
            status = FirebaseStatus.UNAVAILABLE;
        }
    }

    public FirebaseRemoteConfig getFirebase() {
        return config;
    }

    public boolean isInDevelopmentMode() {
        return config.getInfo().getConfigSettings().isDeveloperModeEnabled();
    }

    private synchronized void setStatus(FirebaseStatus status) {
        this.status = status;
    }

    @Deprecated
    public synchronized static RadarConfiguration getInstance() {
        if (instance == null) {
            throw new IllegalStateException("RadarConfiguration instance is not yet "
                    + "initialized");
        }
        return instance;
    }

    public synchronized static RadarConfiguration configure(@NonNull final Context context, boolean inDevelopmentMode, int defaultSettings) {
        if (instance == null) {
            FirebaseRemoteConfigSettings configSettings = new FirebaseRemoteConfigSettings.Builder()
                    .setDeveloperModeEnabled(inDevelopmentMode)
                    .build();
            final FirebaseRemoteConfig config = FirebaseRemoteConfig.getInstance();
            config.setConfigSettings(configSettings);
            config.setDefaults(defaultSettings);

            instance = new RadarConfiguration(context, config);
            instance.fetch();
        }
        return instance;
    }

    /**
     * Adds a new or updated setting to the local configuration. This will be persisted to
     * SharedPreferences. Using this will override Firebase settings. Setting it to {@code null}
     * means that the default value in code will be used, not the Firebase setting. Use
     * {@link #reset(String...)} to completely unset any local configuration.
     *
     * @param key configuration name
     * @param value configuration value
     * @return previous local value for given name, if any
     */
    public String put(@NonNull String key, @NonNull Object value) {
        Objects.requireNonNull(value);
        if (!(value instanceof String
                || value instanceof Long
                || value instanceof Integer
                || value instanceof Float
                || value instanceof Boolean)) {
            throw new IllegalArgumentException("Cannot put value of type " + value.getClass()
                    + " into RadarConfiguration");
        }
        String config = value instanceof String ? (String)value : value.toString();
        String oldValue = getRawString(key);
        if (!oldValue.equals(config)) {
            hasChange.set(true);
        }
        localConfiguration.put(key, config);
        return oldValue;
    }

    public void persistChanges() {
        persistChanges.run();
    }

    /**
     * Reset configuration to Firebase Remote Config values. If no keys are given, all local
     * settings are reset, otherwise only the given keys are reset.
     * @param keys configuration names
     */
    public void reset(String... keys) {
        if (keys == null || keys.length == 0) {
            localConfiguration.clear();
            hasChange.set(true);
        } else {
            for (String key : keys) {
                String oldValue = getRawString(key);
                localConfiguration.remove(key);
                String newValue = getRawString(key);
                if (!Objects.equals(oldValue, newValue)) {
                    hasChange.set(true);
                }
            }
        }
        persistChanges();
    }

    /**
     * Fetch the configuration from the firebase server.
     * @return fetch task or null status is {@link FirebaseStatus#UNAVAILABLE}.
     */
    public Task<Void> fetch() {
        long delay;
        if (isInDevelopmentMode()) {
            delay = 0L;
        } else {
            delay = getLong(FIREBASE_FETCH_TIMEOUT_MS_KEY, FIREBASE_FETCH_TIMEOUT_MS_DEFAULT);
        }
        return fetch(delay);
    }

    /**
     * Fetch the configuration from the firebase server.
     * @param delay seconds
     * @return fetch task or null status is {@link FirebaseStatus#UNAVAILABLE}.
     */
    private Task<Void> fetch(long delay) {
        if (status == FirebaseStatus.UNAVAILABLE) {
            return null;
        }
        Task<Void> task = config.fetch(delay);
        synchronized (this) {
            status = FirebaseStatus.FETCHING;
            task.addOnCompleteListener(onFetchCompleteHandler);
            task.addOnFailureListener(onFailureListener);
        }
        return task;
    }

    public Task<Void> forceFetch() {
        return fetch(0L);
    }

    public boolean activateFetched() {
        return config.activateFetched();
    }

    private String getRawString(String key) {
        if (localConfiguration.containsKey(key)) {
            return localConfiguration.get(key);
        } else {
            return config.getValue(key).asString();
        }
    }

    public String getString(@NonNull String key) {
        String result = getRawString(key);

        if (result == null || result.isEmpty()) {
            throw new IllegalArgumentException("Key does not have a value");
        }

        return result;
    }

    /**
     * Get a configured long value.
     * @param key key of the value
     * @return long value
     * @throws NumberFormatException if the configured value is not a Long
     * @throws IllegalArgumentException if the key does not have an associated value
     */
    public long getLong(@NonNull String key) {
        return Long.parseLong(getString(key));
    }

    /**
     * Get a configured int value.
     * @param key key of the value
     * @return int value
     * @throws NumberFormatException if the configured value is not an Integer
     * @throws IllegalArgumentException if the key does not have an associated value
     */
    public int getInt(@NonNull String key) {
        return Integer.parseInt(getString(key));
    }

    /**
     * Get a configured float value.
     * @param key key of the value
     * @return float value
     * @throws NumberFormatException if the configured value is not an Float
     * @throws IllegalArgumentException if the key does not have an associated value
     */
    public float getFloat(@NonNull String key) {
        return Float.parseFloat(getString(key));
    }

    public String getString(@NonNull String key, String defaultValue) {
        String result = getRawString(key);

        if (result == null || result.isEmpty()) {
            return defaultValue;
        }

        return result;
    }

    /**
     * Get a configured long value. If the configured value is not present or not a valid long,
     * return a default value.
     * @param key key of the value
     * @param defaultValue default value
     * @return configured long value, or defaultValue if no suitable value was found.
     */
    public long getLong(@NonNull String key, long defaultValue) {
        try {
            String ret = getRawString(key);
            if (ret != null && !ret.isEmpty()) {
                return Long.parseLong(ret);
            }
        } catch (IllegalArgumentException ex) {
            // return default
        }
        return defaultValue;
    }

    /**
     * Get a configured int value. If the configured value is not present or not a valid int,
     * return a default value.
     * @param key key of the value
     * @param defaultValue default value
     * @return configured int value, or defaultValue if no suitable value was found.
     */
    public int getInt(@NonNull String key, int defaultValue) {
        try {
            String ret = getRawString(key);
            if (ret != null && !ret.isEmpty()) {
                return Integer.parseInt(ret);
            }
        } catch (IllegalArgumentException ex) {
            // return default
        }
        return defaultValue;
    }


    /**
     * Get a configured float value. If the configured value is not present or not a valid float,
     * return a default value.
     * @param key key of the value
     * @param defaultValue default value
     * @return configured float value, or defaultValue if no suitable value was found.
     */
    public float getFloat(@NonNull String key, float defaultValue) {
        try {
            String ret = getRawString(key);
            if (ret != null && !ret.isEmpty()) {
                return Float.parseFloat(ret);
            }
        } catch (IllegalArgumentException ex) {
            // return default
        }
        return defaultValue;
    }

    public boolean containsKey(@NonNull String key) {
        return config.getKeysByPrefix(key).contains(key);
    }

    public boolean getBoolean(@NonNull String key) {
        String str = getString(key);
        if (IS_TRUE.matcher(str).find()) {
            return true;
        } else if (IS_FALSE.matcher(str).find()) {
            return false;
        } else {
            throw new NumberFormatException("String '" + str + "' of property '" + key
                    + "' is not a boolean");
        }
    }


    public boolean getBoolean(@NonNull String key, boolean defaultValue) {
        String str = getString(key, null);
        if (str == null) {
            return defaultValue;
        }
        return IS_TRUE.matcher(str).find() || (!IS_FALSE.matcher(str).find() && defaultValue);
    }

    public Set<String> keySet() {
        Set<String> baseKeys = new HashSet<>(config.getKeysByPrefix(null));
        Iterator<String> iter = baseKeys.iterator();
        while (iter.hasNext()) {
            if (getString(iter.next(), null) == null) {
                iter.remove();
            }
        }
        return baseKeys;
    }

    public boolean equals(Object obj) {
        return obj != null
                && !obj.getClass().equals(getClass())
                && config.equals(((RadarConfiguration) obj).config);
    }

    public int hashCode() {
        return config.hashCode();
    }

    public void putExtras(Bundle bundle, String... extras) {
        for (String extra : extras) {
            String key = RADAR_PREFIX + extra;

            if (localConfiguration.containsKey(extra)) {
                bundle.putString(key, localConfiguration.get(extra));
            } else {
                try {
                    bundle.putString(key, getString(extra));
                } catch (IllegalArgumentException ex) {
                    // do nothing
                }
            }
        }
    }

    public boolean has(String key) {
        return localConfiguration.containsKey(key) || !config.getValue(key).asString().isEmpty();
    }

    public static boolean hasExtra(Bundle bundle, String key) {
        return bundle.containsKey(RADAR_PREFIX + key);
    }

    public static int getIntExtra(Bundle bundle, String key, int defaultValue) {
        String value = bundle.getString(RADAR_PREFIX + key);
        if (value == null) {
            return defaultValue;
        } else {
            return Integer.parseInt(value);
        }
    }

    public static boolean getBooleanExtra(Bundle bundle, String key, boolean defaultValue) {
        String value = bundle.getString(RADAR_PREFIX + key);
        if (value == null) {
            return defaultValue;
        } else {
            return IS_TRUE.matcher(value).find() || (!IS_FALSE.matcher(value).find() && defaultValue);
        }
    }

    public static int getIntExtra(Bundle bundle, String key) {
        return Integer.parseInt(bundle.getString(RADAR_PREFIX + key));
    }

    public static long getLongExtra(Bundle bundle, String key, long defaultValue) {
        String value = bundle.getString(RADAR_PREFIX + key);
        if (value == null) {
            return defaultValue;
        } else {
            return Long.parseLong(value);
        }
    }

    public static long getLongExtra(Bundle bundle, String key) {
        return Long.parseLong(bundle.getString(RADAR_PREFIX + key));
    }

    public static String getStringExtra(Bundle bundle, String key, String defaultValue) {
        return bundle.getString(RADAR_PREFIX + key, defaultValue);
    }

    public static String getStringExtra(Bundle bundle, String key) {
        return bundle.getString(RADAR_PREFIX + key);
    }

    public static float getFloatExtra(Bundle bundle, String key) {
        return Float.parseFloat(bundle.getString(RADAR_PREFIX + key));
    }

    public static String getOrSetUUID(@NonNull Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences("global", Context.MODE_PRIVATE);
        String uuid;
        synchronized (RadarConfiguration.class) {
            uuid = prefs.getString(key, null);
            if (uuid == null) {
                uuid = UUID.randomUUID().toString();
                prefs.edit().putString(key, uuid).apply();
            }
        }
        return uuid;
    }

    /**
     * Adds base URL from auth state to configuration.
     * @return true if the base URL configuration was updated, false otherwise.
     */
    public boolean updateWithAuthState(@NonNull Context context, @Nullable AppAuthState appAuthState) {
        if (appAuthState == null) {
            return false;
        }
        String baseUrl = stripEndSlashes(appAuthState.getAttribute(BASE_URL_PROPERTY));

        String projectId = appAuthState.getProjectId();
        String userId = appAuthState.getUserId();

        boolean baseUrlChanged = baseUrl != null
                && !baseUrl.equals(getString(BASE_URL_KEY, null));

        if (baseUrlChanged) {
            put(BASE_URL_KEY, baseUrl);
            put(KAFKA_REST_PROXY_URL_KEY, baseUrl + "/kafka/");
            put(SCHEMA_REGISTRY_URL_KEY, baseUrl + "/schema/");
            put(MANAGEMENT_PORTAL_URL_KEY, baseUrl + "/managementportal/");
            put(OAUTH2_TOKEN_URL, baseUrl + "/managementportal/oauth/token");
            put(OAUTH2_AUTHORIZE_URL, baseUrl + "/managementportal/oauth/authorize");
            logger.info("Broadcast config changed based on base URL {}", baseUrl);
        }

        if (projectId != null) {
            put(PROJECT_ID_KEY, projectId);
        }
        if (userId != null) {
            put(USER_ID_KEY, userId);
            put(READABLE_USER_ID_KEY, Objects.requireNonNull(
                    GetSubjectParser.Companion.getHumanReadableUserId(appAuthState)));
        }

        FirebaseAnalytics analytics = FirebaseAnalytics.getInstance(context);
        analytics.setUserId(userId);
        analytics.setUserProperty(USER_ID_KEY, maxCharacters(userId, 36));
        analytics.setUserProperty(PROJECT_ID_KEY, maxCharacters(projectId, 36));
        analytics.setUserProperty(BASE_URL_KEY, maxCharacters(baseUrl, 36));

        Crashlytics.setUserIdentifier(userId);

        return baseUrlChanged;
    }

    @Nullable
    private static String maxCharacters(@Nullable String value, int numChars) {
        if (value != null && value.length() > numChars) {
            return value.substring(0, numChars);
        } else {
            return value;
        }
    }

    /**
     * Strips all slashes from the end of a URL.
     * @param url string to strip
     * @return stripped URL or null if that would result in an empty or null string.
     */
    @Nullable
    private static String stripEndSlashes(@Nullable String url) {
        if (url == null) {
            return null;
        }
        int lastIndex = url.length() - 1;
        while (lastIndex >= 0 && url.charAt(lastIndex) == '/') {
            lastIndex--;
        }
        if (lastIndex == -1) {
            logger.warn("Base URL '{}' should be a valid URL.", url);
            return null;
        }
        return url.substring(0, lastIndex + 1);
    }

    public Map<String, String> toMap() {
        Set<String> keys = new HashSet<>(config.getKeysByPrefix(null));

        Map<String, String> result = new HashMap<>((keys.size() + localConfiguration.size()) * 2);
        for (String key : keys) {
            result.put(key, config.getValue(key).asString());
        }
        result.putAll(localConfiguration);
        return result;
    }

    @NonNull
    @Override
    public String toString() {
        Set<String> keys = new HashSet<>(config.getKeysByPrefix(null));
        keys.addAll(localConfiguration.keySet());
        StringBuilder builder = new StringBuilder(keys.size() * 40 + 20);
        builder.append("RadarConfiguration:\n");
        for (String key : keys) {
            builder.append("  ").append(key).append(": ").append(getString(key)).append('\n');
        }
        return builder.toString();
    }
}
