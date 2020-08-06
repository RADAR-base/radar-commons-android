package org.radarbase.android.config

import android.content.Context
import android.content.Context.MODE_PRIVATE
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthServiceConnection
import org.radarbase.android.auth.LoginListener
import org.radarbase.android.auth.LoginManager
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.IOException

class AppConfigRadarConfiguration(
        context: Context,
        server: ServerConfig,
        private val clientId: String) : RemoteConfig {
    private val authConnection: AuthServiceConnection
    private var auth: AppAuthState? = null
    override var status: RadarConfiguration.RemoteConfigStatus = RadarConfiguration.RemoteConfigStatus.INITIAL
        private set(value) {
            field = value
            onStatusUpdateListener(value)
        }

    override var onStatusUpdateListener: (RadarConfiguration.RemoteConfigStatus) -> Unit = {}

    private val preferences = context.getSharedPreferences("org.radarbase.android.config.AppConfigRadarConfiguration", MODE_PRIVATE)

    @Volatile
    override var cache: Map<String, String> = preferences.all
            .mapNotNull { (k, v) ->
                if (v is String) Pair(k, v) else null
            }
            .toMap()
        private set

    @Volatile
    override var lastFetch: Long = 0L

    private var client = RestClient.global()
            .server(server)
            .build()

    init {
        authConnection = AuthServiceConnection(context, object : LoginListener {
            override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
                client.newBuilder()
                        .headers(authState.okHttpHeaders)
                        .build()
                auth = authState
                forceFetch()
            }

            override fun loginFailed(manager: LoginManager?, ex: Exception?) {
                // nothing to do
            }
        }).apply {
            bind()
        }

        lastFetch = preferences.getLong(LAST_FETCH, 0L)
        if (lastFetch != 0L) {
            status = RadarConfiguration.RemoteConfigStatus.FETCHED
        }
    }

    override fun doFetch(maxCacheAge: Long) {
        val auth = auth
        if (auth == null) {
            logger.error("Cannot fetch configuration without AuthState")
            return
        }
        val request = client.requestBuilder("projects/${auth.projectId}/users/${auth.userId}/config/$clientId")
                .build()

        status = RadarConfiguration.RemoteConfigStatus.FETCHING

        client.httpClient.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                status = try {
                    if (response.isSuccessful && response.body != null) {
                        val retrieved = response.body!!
                        val json = JSONObject(retrieved.string())

                        val properties = HashMap<String, String>()
                        properties.mergeConfig(json.getJSONArray("defaults"))
                        properties.mergeConfig(json.getJSONArray("config"))
                        cache = properties
                        lastFetch = System.currentTimeMillis()

                        preferences.edit().apply {
                            cache.entries.forEach { (k, v) ->
                                putString(k, v)
                            }
                            putLong(LAST_FETCH, lastFetch)
                        }.apply()

                        RadarConfiguration.RemoteConfigStatus.FETCHED
                    } else {
                        logger.error("Failed to fetch remote config (HTTP status {})", response.code)
                        RadarConfiguration.RemoteConfigStatus.ERROR
                    }
                } catch (ex: java.lang.Exception) {
                    logger.error("Failed to parse remote config", ex)
                    RadarConfiguration.RemoteConfigStatus.ERROR
                } finally {
                    response.close()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                status = RadarConfiguration.RemoteConfigStatus.ERROR
            }
        })
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AppConfigRadarConfiguration::class.java)
        private const val LAST_FETCH = "org.radarbase.android.config.AppConfigRadarConfiguration.lastFetch"

        private fun MutableMap<String, String>.mergeConfig(configs: JSONArray) {
            for (index in 0 until configs.length()) {
                val singleConfig = configs.getJSONObject(index)
                val name = singleConfig.getString("name")
                if (singleConfig.isNull("value")) {
                    remove(name)
                } else {
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
}
