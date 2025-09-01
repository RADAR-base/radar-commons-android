package org.radarbase.android.auth.sep

import android.app.Activity
import androidx.lifecycle.Observer
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_ID
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_SECRET
import org.radarbase.android.RadarConfiguration.Companion.SEP_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.UNSAFE_KAFKA_CONNECTION
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.auth.commons.AbstractRadarLoginManager
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.android.auth.commons.AuthType
import org.radarbase.android.auth.portal.GetSubjectParser
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.MP_REFRESH_TOKEN_PROPERTY
import org.radarbase.android.auth.portal.ManagementPortalLoginManager.Companion.SOURCE_TYPE_MP
import org.radarbase.android.auth.sep.SEPClient.Companion.SEP_REFRESH_TOKEN_PROPERTY
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.ServerConfigUtil.toServerConfig
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URLDecoder
import java.util.concurrent.locks.ReentrantLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

@Suppress("unused")
class SEPLoginManager(private val listener: AuthService, state: AppAuthState) :
    AbstractRadarLoginManager(listener, AuthType.SEP) {
    override var client: AbstractRadarPortalClient? = null
    private var clientConfig: SEPClientConfig? = null
    private var restClient: RestClient? = null
    private val refreshLock = ReentrantLock()
    private val config = listener.radarConfig
    private val configUpdateObserver = Observer<SingleRadarConfiguration> {
        ensureClientConnectivity(it)
    }

    init {
        config.config.observeForever(configUpdateObserver)
        updateSources(state)
    }

    override val sourceTypes: List<String> = sepSourceTypeList

    fun sepQrFlow(authState: AppAuthState, qrData: String) {
        if (refreshLock.tryLock()) {
            try {
                val params = parseQueryParams(qrData)

                val data = checkNotNull(params["data"]) { "Data can't be null" }
                val referrer = checkNotNull(params["referrer"]) { "Referrer can't be null" }

                SepQrParser(authState, referrer).parse(data).let { appAuthState ->
                    config.updateWithAuthState(listener, appAuthState)
                    ensureClientConnectivity(config.latestConfig)
                    logger.info("Processed sep url data")
                    refresh(appAuthState)
                }
            } catch (ex: Exception) {
                logger.error("Failed to process sep qr data", ex)
                listener.loginFailed(this, ex)
            } finally {
                refreshLock.unlock()
            }
        } else {
            logger.warn("Refresh lock is acquired by other thread, skipping attempt")
        }
    }

    /**
     * Parse query parameters from a URL string into a map.
     *
     * @param url the full URL (can use custom schemes such as `org.radarbase.prmt://...`).
     * @return a map of decoded query parameters. Duplicate keys are resolved by keeping
     * the **last value**. Parameters with empty keys are skipped.
     *
     * ### Example
     * ```
     * val url = "app://login?foo=bar&token=abc%20123"
     * val params = parseQueryParams(url)
     *
     * // params["foo"] == "bar"
     * // params["token"] == "abc 123"
     * ```
     */
    fun parseQueryParams(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        if (query.isEmpty()) return emptyMap()

        return query.splitToSequence("&")
            .filter { it.isNotEmpty() }
            .map { it.split("=", limit = 2) }
            .mapNotNull { parts ->
                val key = parts.getOrNull(0) ?: return@mapNotNull null
                if (key.isEmpty()) {
                    logger.error("Skipping query parameter with empty key")
                    return@mapNotNull null
                }
                val raw = parts.getOrElse(1) { "" }
                val decoded = safeDecode(raw, key) ?: raw
                key to decoded
            }
            .toMap()
    }

    /**
     * Safely decode a single percent-encoded string.
     *
     * @param raw the raw url-encoded value, or `null`.
     * @param key the query parameter name (used only for logging).
     * @param enc the character encoding name (default = `"UTF-8"`).
     * @return the decoded string if decoding succeeds, or `null` if decoding fails.
     *
     * ### Notes
     *   - `UnsupportedEncodingException` is caught (should never happen for UTF-8).
     *   - `IllegalArgumentException` is caught if malformed `%` sequences are encountered.
     */
    fun safeDecode(raw: String, key: String, enc: String = UTF_8): String? {
        return try {
            URLDecoder.decode(raw, enc)
        } catch (e: UnsupportedEncodingException) {
            logger.error("Unsupported encoding '{}' while decoding query parameter '$key'", enc, e)
            null
        } catch (e: IllegalArgumentException) {
            logger.error(
                "Malformed percent-encoding in query parameter$key (rawLength=${raw.length})",
                e
            )
            null
        }
    }


    override fun refresh(authState: AppAuthState): Boolean {
        if (authState.getAttribute(SEP_REFRESH_TOKEN_PROPERTY) == null) {
            return false
        }
        client?.let { client ->
            operateOnSepClient(client) {
                client as SEPClient
                if (refreshLock.tryLock()) {
                    try {
                        val accessTokenParser = AccessTokenParser(authState)
                        client.refreshToken(authState, accessTokenParser, config.latestConfig).let { newState: AppAuthState ->
                            client.getSubject(newState, GetSubjectParser(newState))
                        }
                        logger.info("Refreshed JWT from sep")

                        updateSources(authState)
                        listener.loginSucceeded(this, authState)
                    } catch (ex: Exception) {
                        logger.error("Failed to get access token", ex)
                        listener.loginFailed(this, ex)
                    } finally {
                        refreshLock.unlock()
                    }
                }
            }
        }
        return true
    }

    override fun isRefreshable(authState: AppAuthState): Boolean {
        return authState.userId != null
                && authState.projectId != null
                && authState.getAttribute(SEP_REFRESH_TOKEN_PROPERTY) != null
    }

    override fun start(authState: AppAuthState) {
        refresh(authState)
    }

    override fun onActivityCreate(activity: Activity): Boolean {
        return false
    }

    override fun invalidate(
        authState: AppAuthState,
        disableRefresh: Boolean
    ): AppAuthState? {
        return when {
            authState.authenticationSource != SOURCE_TYPE_MP -> null
            disableRefresh -> authState.alter {
                attributes -= MP_REFRESH_TOKEN_PROPERTY
                isPrivacyPolicyAccepted = false
            }
            else -> authState
        }
    }

    override fun onDestroy() {
        config.config.removeObserver(configUpdateObserver)
    }

    @Synchronized
    private fun ensureClientConnectivity(config: SingleRadarConfiguration) {
        val newClientConfig = try {
            SEPClientConfig(
                config.getString(SEP_URL_KEY),
                config.getBoolean(UNSAFE_KAFKA_CONNECTION, false),
                config.getString(OAUTH2_CLIENT_ID),
                config.getString(OAUTH2_CLIENT_SECRET, ""),
            )
        } catch (_: MalformedURLException) {
            logger.error("Cannot construct ManagementPortalClient with malformed URL")
            null
        } catch (_: IllegalArgumentException) {
            logger.error("Cannot construct ManagementPortalClient without client credentials")
            null
        }

        if (newClientConfig == clientConfig) return

        client = newClientConfig?.let {
            SEPClient(
                it.serverConfig,
                it.clientId,
                it.clientSecret,
                client = restClient
            ).also { sep ->
                restClient = sep.client
            }
        }
    }

    fun operateOnSepClient(client: AbstractRadarPortalClient, operation: () -> Unit) {
        try {
            requireSepClient(client)
            operation()
        } catch (ex: AbstractRadarPortalClient.Companion.MismatchedClientException) {
            logger.error("Mismatched client: ", ex)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SEPLoginManager::class.java)

        const val SOURCE_TYPE_SEP = "org.radarbase.android.auth.sep.SelfEnrolmentPortal"
        private val sepSourceTypeList = listOf(SOURCE_TYPE_SEP)

        @OptIn(ExperimentalContracts::class)
        private fun requireSepClient(client: AbstractRadarPortalClient) {
            contract {
                returns() implies (client is SEPClient)
            }
            if (client !is SEPClient) {
                throw AbstractRadarPortalClient.Companion.MismatchedClientException(
                    "Invalid client type detected: expected 'SEPClient', but actual '${client::class.simpleName}' Terminating operation."
                )
            }
        }

        private const val UTF_8 = "UTF-8"
    }

    private data class SEPClientConfig(
        val serverConfig: ServerConfig,
        val clientId: String,
        val clientSecret: String
    ) {
        constructor(
            url: String,
            unsafe: Boolean,
            clientId: String,
            clientSecret: String,
        ) : this(url.toServerConfig(unsafe), clientId, clientSecret)
    }
}