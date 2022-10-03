package org.radarbase.android.config

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.RadarConfiguration.Companion.BASE_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_ID
import org.radarbase.android.RadarConfiguration.Companion.UNSAFE_KAFKA_CONNECTION
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.util.ServerConfigUtil.toServerConfig
import org.radarbase.android.util.ChangeRunner
import org.radarbase.android.util.DelayedRetry
import org.radarbase.android.util.SafeHandler
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.IOException

@Suppress("unused")
class AppConfigRadarConfiguration(context: Context) : RemoteConfig {
    private var auth: AppAuthState? = null
    private var config: SingleRadarConfiguration? = null
    private val handler = SafeHandler.getInstance("appconfig", THREAD_PRIORITY_BACKGROUND).apply {
        if (!isStarted) {
            start()
        }
    }
    private val retryDelay = DelayedRetry(minDelay = 10_000L, maxDelay = 3_600_000L)

    @get:Synchronized
    private var appConfig: AppConfigClientConfig? = null

    private var statusChangeRunner = ChangeRunner(RadarConfiguration.RemoteConfigStatus.INITIAL)

    override var status: RadarConfiguration.RemoteConfigStatus
        get() = statusChangeRunner.value
        private set(value) {
            statusChangeRunner.applyIfChanged(value) {
                handler.execute {
                    logger.info("Updating status to {}", value)
                    this.onStatusUpdateListener(value)
                }
            }
        }

    override var onStatusUpdateListener: (RadarConfiguration.RemoteConfigStatus) -> Unit = {}

    private val preferences = context.getSharedPreferences(
        "org.radarbase.android.config.AppConfigRadarConfiguration",
        MODE_PRIVATE
    )

    @Suppress("UNCHECKED_CAST")
    @get:Synchronized
    @set:Synchronized
    override var cache: Map<String, String> = preferences.all.filterValues { it is String } as Map<String, String>
        private set

    @Volatile
    override var lastFetch: Long = 0L

    private var client: RestClient? = null

    init {
        lastFetch = preferences.getLong(LAST_FETCH, 0L)
        if (lastFetch != 0L) {
            status = RadarConfiguration.RemoteConfigStatus.FETCHED
        }
    }

    override fun doFetch(maxCacheAge: Long) = handler.execute {
        val (client, appConfig) = synchronized(this) {
            Pair(client, appConfig)
        }
        if (appConfig == null || client == null) {
            logger.warn("Cannot fetch configuration without AppConfig client configuration")
            status = RadarConfiguration.RemoteConfigStatus.UNAVAILABLE
            return@execute
        }
        val request = client.requestBuilder("projects/${appConfig.appAuthState.projectId}/users/${appConfig.appAuthState.userId}/config/${appConfig.clientId}")
                .build()

        status = RadarConfiguration.RemoteConfigStatus.FETCHING

        client.httpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                status = try {
                    val body = response.body
                    if (response.isSuccessful && body != null) {
                        logger.info("Successfully fetched app config body")
                        val json = JSONObject(body.string())

                        cache = HashMap<String, String>().apply {
                            json.optJSONArray("defaults")?.let { mergeConfig(it) }
                            json.optJSONArray("config")?.let { mergeConfig(it) }
                        }
                        lastFetch = System.currentTimeMillis()

                        retryDelay.reset()
                        RadarConfiguration.RemoteConfigStatus.FETCHED
                    } else if (response.code == 404) {
                        logger.warn("Cannot query appconfig at {}. Disabling app-config.", request.url)
                        RadarConfiguration.RemoteConfigStatus.UNAVAILABLE
                    } else {
                        logger.error("Failed to fetch remote config using {} (HTTP status {}): {}",
                                call.request(), response.code, response.body?.string())
                        retryLater(appConfig)
                        RadarConfiguration.RemoteConfigStatus.ERROR
                    }
                } catch (ex: java.lang.Exception) {
                    logger.error("Failed to parse remote config", ex)
                    retryLater(appConfig)
                    RadarConfiguration.RemoteConfigStatus.ERROR
                } finally {
                    response.close()
                }

                preferences.edit().apply {
                    cache.entries.forEach { (k, v) ->
                        putString(k, v)
                    }
                    putLong(LAST_FETCH, lastFetch)
                }.apply()
            }

            override fun onFailure(call: Call, e: IOException) {
                logger.error("AppConfig update request failed", e)
                status = RadarConfiguration.RemoteConfigStatus.ERROR
                retryLater(appConfig)
            }
        })
    }

    private fun retryLater(appConfig: AppConfigClientConfig) {
        handler.delay(retryDelay.nextDelay()) {
            fetch(appConfig.fetchTimeout)
        }
    }

    override fun updateWithAuthState(appAuthState: AppAuthState?) {
        auth = appAuthState
        updateConfiguration(appAuthState, config)
    }

    override fun updateWithConfig(config: SingleRadarConfiguration?) {
        this.config = config
        updateConfiguration(auth, config)
    }

    @Synchronized
    private fun updateConfiguration(auth: AppAuthState?, config: SingleRadarConfiguration?) {
        val appConfig = config?.let { value ->
            val serverConfig = value.optString(BASE_URL_KEY) { baseUrl ->
                "$baseUrl/appconfig/api/".toServerConfig(
                    isUnsafe = value.getBoolean(UNSAFE_KAFKA_CONNECTION, false),
                )
            }
            val clientId = value.optString(OAUTH2_CLIENT_ID)
            val timeout = config.getLong(RadarConfiguration.FETCH_TIMEOUT_MS_KEY, RadarConfiguration.FETCH_TIMEOUT_MS_DEFAULT)
            if (auth != null && auth.isValid && serverConfig != null && clientId != null) {
                AppConfigClientConfig(auth, serverConfig, clientId, timeout)
            } else null
        }
        if (appConfig == this.appConfig) {
            return
        }
        logger.info("AppConfig config {}", appConfig)
        this.appConfig = appConfig
        if (appConfig != null) {
            status = RadarConfiguration.RemoteConfigStatus.READY
            client = (client?.newBuilder() ?: RestClient.global())
                    .headers(appConfig.appAuthState.okHttpHeaders)
                    .server(appConfig.serverConfig)
                    .build()
            fetch(appConfig.fetchTimeout)
        } else {
            status = RadarConfiguration.RemoteConfigStatus.UNAVAILABLE
            client = null
        }
    }

    private data class AppConfigClientConfig(
        val appAuthState: AppAuthState,
        val serverConfig: ServerConfig,
        val clientId: String,
        val fetchTimeout: Long,
    )

    companion object {
        private val logger = LoggerFactory.getLogger(AppConfigRadarConfiguration::class.java)
        private const val LAST_FETCH = "org.radarbase.android.config.AppConfigRadarConfiguration.lastFetch"

        private fun MutableMap<String, String>.mergeConfig(configs: JSONArray) {
            for (index in 0 until configs.length()) {
                val singleConfig = configs.getJSONObject(index)
                val name = singleConfig.getString("name")
                val value = singleConfig.optString("value")
                if (value.isEmpty()) {
                    remove(name)
                } else {
                    put(name, value)
                }
            }
        }
    }
}
