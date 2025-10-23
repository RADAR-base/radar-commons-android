package org.radarbase.android.auth.sep

import okhttp3.Credentials
import okhttp3.FormBody
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_TOKEN_URL
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthStringParser
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.android.auth.commons.AuthType
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import java.net.MalformedURLException
import java.net.URI

open class SEPClient(
    sep: ServerConfig,
    clientId: String,
    clientSecret: String,
    client: RestClient? = null
) : AbstractRadarPortalClient(AuthType.SEP, sep, client) {
    private val credentials = Credentials.basic(clientId, clientSecret)

    fun refreshToken(
        authState: AppAuthState,
        parser: AuthStringParser,
        config: SingleRadarConfiguration
    ): AppAuthState {
        try {
            val refreshToken = requireNotNull(authState.getAttribute(SEP_REFRESH_TOKEN_PROPERTY)) {
                "No refresh token found"
            }

            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val hydraTokenEndpoint: String = config.optString(OAUTH2_TOKEN_URL) ?: run {
                val baseUrl = requireNotNull(authState.baseUrl) {
                    "Hydra token endpoint is not set, and there is no base url, quitting refresh operation"
                }
                buildString {
                    append(baseUrl)
                    append("/hydra/oauth2/token")
                }
            }

            val request = client.requestBuilder(URI(hydraTokenEndpoint).path)
                .post(body)
                .addHeader("Authorization", credentials)
                .build()

            return handleRequest(request, parser)
        } catch (e: MalformedURLException) {
            throw IllegalStateException("Failed to create request from token url", e)
        }

    }

    companion object {
        const val SEP_REFRESH_TOKEN_PROPERTY = "org.radarbase.android.auth.sep.SEPClient.refreshToken"
    }
}