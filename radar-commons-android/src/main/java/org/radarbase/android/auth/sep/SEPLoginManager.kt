package org.radarbase.android.auth.sep

import android.app.Activity
import androidx.lifecycle.Observer
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.auth.commons.AbstractRadarLoginManager
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.ServerConfigUtil.toServerConfig
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.concurrent.locks.ReentrantLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

class SEPLoginManager(private val listener: AuthService, state: AppAuthState) :
    AbstractRadarLoginManager(listener) {
    override var client: AbstractRadarPortalClient? = null
    private var clientConfig: SEPClientConfig? = null
    private var restClient: RestClient? = null
    private val refreshLock = ReentrantLock()
    private val config = listener.radarConfig
    private val configUpdateObserver = Observer<SingleRadarConfiguration> {
        //TODO
    }

    override val sourceTypes: List<String> = sepSourceTypeList

    fun sepQrFlow(data: String) {
        val params = parseQueryParams(data)

        val data = params["data"]
        val referrer = params["referrer"]


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
    fun safeDecode(raw: String, key: String, enc: String = "UTF-8"): String? {
        return try {
            URLDecoder.decode(raw, enc)
        } catch (e: UnsupportedEncodingException) {
            logger.error("Unsupported encoding '{}' while decoding query parameter '$key'", enc, e)
            null
        } catch (e: IllegalArgumentException) {
            logger.error("Malformed percent-encoding in query parameter$key (rawLength=${raw.length})", e)
            null
        }
    }


    override fun refresh(authState: AppAuthState): Boolean {
        TODO("Not yet implemented")
    }

    override fun isRefreshable(authState: AppAuthState): Boolean {
        TODO("Not yet implemented")
    }

    override fun start(authState: AppAuthState) {
        TODO("Not yet implemented")
    }

    override fun onActivityCreate(activity: Activity): Boolean {
        TODO("Not yet implemented")
    }

    override fun invalidate(
        authState: AppAuthState,
        disableRefresh: Boolean
    ): AppAuthState? {
        TODO("Not yet implemented")
    }

    override fun onDestroy() {
        TODO("Not yet implemented")
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

        private val sepSourceTypeList = listOf(SOURCE_TYPE_SEP)
        const val SOURCE_TYPE_SEP = "org.radarbase.android.auth.sep.SelfEnrolmentPortal"

        @OptIn(ExperimentalContracts::class)
        private fun requireSepClient(client: AbstractRadarPortalClient) {
            contract {
                returns() implies (client is SEPClient)
            }
            if (client !is SEPClient) {
                throw AbstractRadarPortalClient.Companion.MismatchedClientException(
                    "Invalid client type detected: expected SEPClient, but actual ${client::class.simpleName} Terminating operation."
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