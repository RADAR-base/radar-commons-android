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

package org.radarbase.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.crashlytics.android.Crashlytics
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService.Companion.BASE_URL_PROPERTY
import org.radarbase.android.auth.portal.GetSubjectParser
import org.radarbase.android.util.send
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import java.util.regex.Pattern.CASE_INSENSITIVE
import kotlin.collections.HashSet

class RadarConfiguration private constructor(context: Context,
                                             private val firebase: FirebaseRemoteConfig) {
    private val onFailureListener: OnFailureListener
    private val hasChange: AtomicBoolean = AtomicBoolean(false)
    var status: FirebaseStatus?
        private set

    private val handler: Handler = Handler()
    private val onFetchCompleteHandler: OnCompleteListener<Void>
    private val localConfiguration: MutableMap<String, String> = ConcurrentHashMap()
    private val persistChanges: Runnable
    private var isInDevelopmentMode: Boolean = false
    private var firebaseKeys: Set<String> = HashSet(firebase.getKeysByPrefix(""))
    private val broadcaster = LocalBroadcastManager.getInstance(context)

    enum class FirebaseStatus {
        UNAVAILABLE, ERROR, READY, FETCHING, FETCHED
    }

    init {
        this.onFetchCompleteHandler = OnCompleteListener {  task ->
            if (task.isSuccessful) {
                status = FirebaseStatus.FETCHED
                // Once the config is successfully fetched it must be
                // activated before newly fetched values are returned.
                activateFetched().addOnCompleteListener {
                    // Set global properties.
                    logger.info("RADAR configuration changed: {}", this@RadarConfiguration)
                    broadcaster.send(RADAR_CONFIGURATION_CHANGED)
                }
            } else {
                status = FirebaseStatus.ERROR
                logger.warn("Remote Config: Fetch failed. Stacktrace: {}", task.exception)
            }
        }

        this.onFailureListener = OnFailureListener {
            logger.info("Failed to fetch Firebase config")
            status = FirebaseStatus.ERROR
            broadcaster.send(RADAR_CONFIGURATION_CHANGED)
        }

        val prefs = context.applicationContext.getSharedPreferences(javaClass.name, Context.MODE_PRIVATE)
        for ((key, value) in prefs.all) {
            if (value == null || value is String) {
                this.localConfiguration[key] = (value as String?)!!
            }
        }

        persistChanges = Runnable {
            val editor = prefs.edit()
            for ((key, value) in localConfiguration) {
                editor.putString(key, value)
            }
            editor.apply()

            if (hasChange.compareAndSet(true, false)) {
                logger.info("RADAR configuration changed: {}", this@RadarConfiguration)
                broadcaster.send(RADAR_CONFIGURATION_CHANGED)            }
        }

        val googleApi = GoogleApiAvailability.getInstance()
        if (googleApi.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            status = FirebaseStatus.READY
            this.handler.postDelayed(object : Runnable {
                override fun run() {
                    fetch()
                    val delay = getLong(FIREBASE_FETCH_TIMEOUT_MS_KEY, FIREBASE_FETCH_TIMEOUT_MS_DEFAULT)
                    handler.postDelayed(this, delay)
                }
            }, getLong(FIREBASE_FETCH_TIMEOUT_MS_KEY, FIREBASE_FETCH_TIMEOUT_MS_DEFAULT))
        } else {
            status = FirebaseStatus.UNAVAILABLE
        }
    }

    /**
     * Adds a new or updated setting to the local configuration. This will be persisted to
     * SharedPreferences. Using this will override Firebase settings. Setting it to `null`
     * means that the default value in code will be used, not the Firebase setting. Use
     * [.reset] to completely unset any local configuration.
     *
     * @param key configuration name
     * @param value configuration value
     * @return previous local value for given name, if any
     */
    fun put(key: String, value: Any): String? {
        Objects.requireNonNull(value)
        if (!(value is String
                        || value is Long
                        || value is Int
                        || value is Float
                        || value is Boolean)) {
            throw IllegalArgumentException("Cannot put value of type " + value.javaClass
                    + " into RadarConfiguration")
        }
        val config = value as? String ?: value.toString()
        val oldValue = getRawString(key)
        if (oldValue != config) {
            hasChange.set(true)
        }
        localConfiguration[key] = config
        return oldValue
    }

    fun persistChanges() = persistChanges.run()

    /**
     * Reset configuration to Firebase Remote Config values. If no keys are given, all local
     * settings are reset, otherwise only the given keys are reset.
     * @param keys configuration names
     */
    fun reset(vararg keys: String) {
        if (keys.isEmpty()) {
            localConfiguration.clear()
            hasChange.set(true)
        } else {
            for (key in keys) {
                val oldValue = getRawString(key)
                localConfiguration.remove(key)
                val newValue = getRawString(key)
                if (oldValue != newValue) {
                    hasChange.set(true)
                }
            }
        }
        persistChanges()
    }

    /**
     * Fetch the configuration from the firebase server.
     * @return fetch task or null status is [FirebaseStatus.UNAVAILABLE].
     */
    fun fetch(): Task<Void>? {
        val delay = if (isInDevelopmentMode) {
            0L
        } else {
            getLong(FIREBASE_FETCH_TIMEOUT_MS_KEY, FIREBASE_FETCH_TIMEOUT_MS_DEFAULT)
        }
        return fetch(delay)
    }

    /**
     * Fetch the configuration from the firebase server.
     * @param delay seconds
     * @return fetch task or null status is [FirebaseStatus.UNAVAILABLE].
     */
    private fun fetch(delay: Long): Task<Void>? {
        if (status == FirebaseStatus.UNAVAILABLE) {
            return null
        }
        val task = firebase.fetch(delay)
        synchronized(this) {
            status = FirebaseStatus.FETCHING
            task.addOnCompleteListener(onFetchCompleteHandler)
            task.addOnFailureListener(onFailureListener)
        }
        return task
    }

    fun forceFetch(): Task<Void>? = fetch(0L)

    fun activateFetched(): Task<Boolean> {
        val result = firebase.activate()
        firebaseKeys = HashSet(firebase.getKeysByPrefix(""))
        return result
    }

    private fun getRawString(key: String): String? {
        return localConfiguration[key]
                ?: if (key in firebaseKeys) firebase.getValue(key).asString() else null
    }

    fun getString(key: String): String {
        return optString(key)
                ?: throw IllegalArgumentException("Key does not have a value")
    }

    /**
     * Get a configured long value.
     * @param key key of the value
     * @return long value
     * @throws NumberFormatException if the configured value is not a Long
     * @throws IllegalArgumentException if the key does not have an associated value
     */
    fun getLong(key: String): Long = java.lang.Long.parseLong(getString(key))

    /**
     * Get a configured int value.
     * @param key key of the value
     * @return int value
     * @throws NumberFormatException if the configured value is not an Integer
     * @throws IllegalArgumentException if the key does not have an associated value
     */
    fun getInt(key: String): Int = Integer.parseInt(getString(key))

    /**
     * Get a configured float value.
     * @param key key of the value
     * @return float value
     * @throws NumberFormatException if the configured value is not an Float
     * @throws IllegalArgumentException if the key does not have an associated value
     */
    fun getFloat(key: String): Float = java.lang.Float.parseFloat(getString(key))

    fun getString(key: String, defaultValue: String): String = optString(key) ?: defaultValue

    fun optString(key: String): String? {
        val result = getRawString(key)
        return if (result?.isEmpty() == false) result else null
    }

    /**
     * Get a configured long value. If the configured value is not present or not a valid long,
     * return a default value.
     * @param key key of the value
     * @param defaultValue default value
     * @return configured long value, or defaultValue if no suitable value was found.
     */
    fun getLong(key: String, defaultValue: Long): Long {
        return optString(key)?.toLongOrNull()
                ?: defaultValue
    }

    /**
     * Get a configured int value. If the configured value is not present or not a valid int,
     * return a default value.
     * @param key key of the value
     * @param defaultValue default value
     * @return configured int value, or defaultValue if no suitable value was found.
     */
    fun getInt(key: String, defaultValue: Int): Int {
        return optString(key)?.toIntOrNull()
                ?: defaultValue
    }


    /**
     * Get a configured float value. If the configured value is not present or not a valid float,
     * return a default value.
     * @param key key of the value
     * @param defaultValue default value
     * @return configured float value, or defaultValue if no suitable value was found.
     */
    fun getFloat(key: String, defaultValue: Float): Float {
        return optString(key)?.toFloatOrNull()
                ?: defaultValue
    }

    fun containsKey(key: String) = key in firebase.getKeysByPrefix(key)

    fun getBoolean(key: String): Boolean {
        val str = getString(key)
        return when {
            IS_TRUE.matcher(str).find() -> true
            IS_FALSE.matcher(str).find() -> false
            else -> throw NumberFormatException("String '" + str + "' of property '" + key
                    + "' is not a boolean")
        }
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val str = optString(key) ?: return defaultValue
        return when {
            IS_TRUE.matcher(str).find() -> true
            IS_FALSE.matcher(str).find() -> false
            else -> defaultValue
        }
    }

    val keys: Set<String>
        get() {
            val baseKeys = HashSet(firebaseKeys + localConfiguration.keys)
            val iter = baseKeys.iterator()
            while (iter.hasNext()) {
                if (optString(iter.next()) == null) {
                    iter.remove()
                }
            }
            return baseKeys
        }

    override fun equals(other: Any?): Boolean {
        return (other != null
                && other.javaClass != javaClass
                && firebase == (other as RadarConfiguration).firebase)
    }

    override fun hashCode(): Int {
        return firebase.hashCode()
    }

    fun putExtras(bundle: Bundle, vararg extras: String) {
        for (extra in extras) {
            val key = RADAR_PREFIX + extra

            if (localConfiguration.containsKey(extra)) {
                bundle.putString(key, localConfiguration[extra])
            } else {
                try {
                    bundle.putString(key, getString(extra))
                } catch (ex: IllegalArgumentException) {
                    // do nothing
                }

            }
        }
    }

    fun has(key: String) = key in localConfiguration
            || (key in firebaseKeys && !firebase.getValue(key).asString().isEmpty())

    /**
     * Adds base URL from auth state to configuration.
     * @return true if the base URL configuration was updated, false otherwise.
     */
    fun updateWithAuthState(context: Context, appAuthState: AppAuthState?): Boolean {
        if (appAuthState == null) {
            return false
        }
        val baseUrl = stripEndSlashes(appAuthState.getAttribute(BASE_URL_PROPERTY))
        val projectId = appAuthState.projectId
        val userId = appAuthState.userId

        val baseUrlChanged = baseUrl != null
                && baseUrl != optString(BASE_URL_KEY)

        if (baseUrlChanged) {
            put(BASE_URL_KEY, baseUrl!!)
            put(KAFKA_REST_PROXY_URL_KEY, "$baseUrl/kafka/")
            put(SCHEMA_REGISTRY_URL_KEY, "$baseUrl/schema/")
            put(MANAGEMENT_PORTAL_URL_KEY, "$baseUrl/managementportal/")
            put(OAUTH2_TOKEN_URL, "$baseUrl/managementportal/oauth/token")
            put(OAUTH2_AUTHORIZE_URL, "$baseUrl/managementportal/oauth/authorize")
            logger.info("Broadcast config changed based on base URL {}", baseUrl)
        }

        projectId?.let {
            put(PROJECT_ID_KEY, it)
        }
        userId?.let {
            put(USER_ID_KEY, it)
            put(READABLE_USER_ID_KEY, GetSubjectParser.getHumanReadableUserId(appAuthState) ?: it)
        }

        FirebaseAnalytics.getInstance(context).apply {
            setUserId(userId)
            setUserProperty(USER_ID_KEY, maxCharacters(userId, 36))
            setUserProperty(PROJECT_ID_KEY, maxCharacters(projectId, 36))
            setUserProperty(BASE_URL_KEY, maxCharacters(baseUrl, 36))
        }
        Crashlytics.setUserIdentifier(userId)

        return baseUrlChanged
    }

    fun toMap(): Map<String, String> = keys.map { Pair(it, getString(it)) }.toMap()

    override fun toString(): String {
        val keys = keys
        val builder = StringBuilder(keys.size * 40 + 20)
        builder.append("RadarConfiguration:\n")
        for (key in keys) {
            builder.append("  ")
                    .append(key)
                    .append(": ")
                    .append(getString(key))
                    .append('\n')
        }
        return builder.toString()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RadarConfiguration::class.java)
        private const val FIREBASE_FETCH_TIMEOUT_MS_DEFAULT = 12 * 60 * 60 * 1000L

        const val RADAR_PREFIX = "org.radarcns.android."

        const val RADAR_CONFIGURATION_CHANGED = RADAR_PREFIX + "RadarConfiguration.CHANGED"

        const val KAFKA_REST_PROXY_URL_KEY = "kafka_rest_proxy_url"
        const val SCHEMA_REGISTRY_URL_KEY = "schema_registry_url"
        const val MANAGEMENT_PORTAL_URL_KEY = "management_portal_url"
        const val PROJECT_ID_KEY = "radar_project_id"
        const val USER_ID_KEY = "radar_user_id"
        const val READABLE_USER_ID_KEY = "readable_user_id"
        const val BASE_URL_KEY = "radar_base_url"
        const val SOURCE_ID_KEY = "source_id"
        const val SEND_OVER_DATA_HIGH_PRIORITY = "send_over_data_high_priority_only"
        const val TOPICS_HIGH_PRIORITY = "topics_high_priority"
        const val UI_REFRESH_RATE_KEY = "ui_refresh_rate_millis"
        const val KAFKA_UPLOAD_RATE_KEY = "kafka_upload_rate"
        const val DATABASE_COMMIT_RATE_KEY = "database_commit_rate"
        const val KAFKA_RECORDS_SEND_LIMIT_KEY = "kafka_records_send_limit"
        const val KAFKA_RECORDS_SIZE_LIMIT_KEY = "kafka_records_size_limit"
        const val SENDER_CONNECTION_TIMEOUT_KEY = "sender_connection_timeout"
        const val FIREBASE_FETCH_TIMEOUT_MS_KEY = "firebase_fetch_timeout_ms"
        const val START_AT_BOOT = "start_at_boot"
        const val DEVICE_SERVICES_TO_CONNECT = "device_services_to_connect"
        const val PLUGINS = "plugins"
        const val KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL = "kafka_upload_minimum_battery_level"
        const val KAFKA_UPLOAD_REDUCED_BATTERY_LEVEL = "kafka_upload_reduced_battery_level"
        const val MAX_CACHE_SIZE = "cache_max_size_bytes"
        const val SEND_ONLY_WITH_WIFI = "send_only_with_wifi"
        const val SEND_BINARY_CONTENT = "send_binary_content"
        const val SEND_WITH_COMPRESSION = "send_with_compression"
        const val UNSAFE_KAFKA_CONNECTION = "unsafe_kafka_connection"
        const val OAUTH2_AUTHORIZE_URL = "oauth2_authorize_url"
        const val OAUTH2_TOKEN_URL = "oauth2_token_url"
        const val OAUTH2_REDIRECT_URL = "oauth2_redirect_url"
        const val OAUTH2_CLIENT_ID = "oauth2_client_id"
        const val OAUTH2_CLIENT_SECRET = "oauth2_client_secret"
        const val ENABLE_BLUETOOTH_REQUESTS = "enable_bluetooth_requests"

        const val SEND_ONLY_WITH_WIFI_DEFAULT = true
        const val SEND_OVER_DATA_HIGH_PRIORITY_DEFAULT = true
        const val SEND_BINARY_CONTENT_DEFAULT = true

        private val IS_TRUE = Pattern.compile(
                "^(1|true|t|yes|y|on)$", CASE_INSENSITIVE)
        private val IS_FALSE = Pattern.compile(
                "^(0|false|f|no|n|off|)$", CASE_INSENSITIVE)

        private var instance: RadarConfiguration? = null

        @Deprecated("")
        @Synchronized
        fun getInstance(): RadarConfiguration {
            return instance
                    ?: throw IllegalStateException(
                            "RadarConfiguration instance is not yet initialized")
        }

        @Synchronized
        fun configure(context: Context, inDevelopmentMode: Boolean, defaultSettings: Int): RadarConfiguration {
            return instance ?:
                RadarConfiguration(context,
                        FirebaseRemoteConfig.getInstance().apply {
                            setDefaultsAsync(defaultSettings)
                        })
                        .also { instance = it }
                        .apply {
                            isInDevelopmentMode = inDevelopmentMode
                            fetch()
                        }
        }

        fun hasExtra(bundle: Bundle, key: String): Boolean {
            return bundle.containsKey(RADAR_PREFIX + key)
        }

        fun getIntExtra(bundle: Bundle, key: String, defaultValue: Int): Int {
            val value = bundle.getString(RADAR_PREFIX + key)
            return if (value == null) {
                defaultValue
            } else {
                Integer.parseInt(value)
            }
        }

        fun getBooleanExtra(bundle: Bundle, key: String, defaultValue: Boolean): Boolean {
            val value = bundle.getString(RADAR_PREFIX + key)
            return if (value == null) {
                defaultValue
            } else {
                IS_TRUE.matcher(value).find() || !IS_FALSE.matcher(value).find() && defaultValue
            }
        }

        fun getIntExtra(bundle: Bundle, key: String): Int {
            return Integer.parseInt(bundle.getString(RADAR_PREFIX + key)!!)
        }

        fun getLongExtra(bundle: Bundle, key: String, defaultValue: Long): Long {
            val value = bundle.getString(RADAR_PREFIX + key)
            return if (value == null) {
                defaultValue
            } else {
                java.lang.Long.parseLong(value)
            }
        }

        fun getLongExtra(bundle: Bundle, key: String): Long {
            return java.lang.Long.parseLong(bundle.getString(RADAR_PREFIX + key)!!)
        }

        fun getStringExtra(bundle: Bundle, key: String, defaultValue: String): String {
            return bundle.getString(RADAR_PREFIX + key, defaultValue)
        }

        fun getStringExtra(bundle: Bundle, key: String): String? {
            return bundle.getString(RADAR_PREFIX + key)
        }

        fun getFloatExtra(bundle: Bundle, key: String): Float {
            return java.lang.Float.parseFloat(bundle.getString(RADAR_PREFIX + key)!!)
        }

        @SuppressLint("ApplySharedPref")
        fun getOrSetUUID(context: Context, key: String): String {
            val prefs = context.getSharedPreferences("global", Context.MODE_PRIVATE)
            return synchronized(RadarConfiguration::class.java) {
                prefs.getString(key, null)
                        ?:  UUID.randomUUID().toString()
                                .also { prefs.edit()
                                        .putString(key, it)
                                        .commit() // commit immediately to avoid races
                                }
            }
        }

        private fun maxCharacters(value: String?, numChars: Int): String? {
            return if (value != null && value.length > numChars) {
                value.substring(0, numChars)
            } else {
                value
            }
        }

        /**
         * Strips all slashes from the end of a URL.
         * @param url string to strip
         * @return stripped URL or null if that would result in an empty or null string.
         */
        private fun stripEndSlashes(url: String?): String? {
            if (url == null) {
                return null
            }
            var lastIndex = url.length - 1
            while (lastIndex >= 0 && url[lastIndex] == '/') {
                lastIndex--
            }
            if (lastIndex == -1) {
                logger.warn("Base URL '{}' should be a valid URL.", url)
                return null
            }
            return url.substring(0, lastIndex + 1)
        }
    }
}
