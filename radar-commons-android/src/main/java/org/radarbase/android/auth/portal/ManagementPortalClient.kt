package org.radarbase.android.auth.portal

import okhttp3.Credentials
import okhttp3.FormBody
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthStringParser
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.MalformedURLException

class ManagementPortalClient(
    managementPortal: ServerConfig,
    clientId: String,
    clientSecret: String,
    client: RestClient? = null,
) : AbstractRadarPortalClient(managementPortal, client){
    private val credentials = Credentials.basic(clientId, clientSecret)

    /**
     * Get refresh-token from meta-token url.
     * @param metaTokenUrl current token url
     * @param parser string parser
     * @throws IOException if the management portal could not be reached or it gave an erroneous
     * response.
     */
    @Throws(IOException::class)
    fun getRefreshToken(metaTokenUrl: String, parser: AuthStringParser): AppAuthState {
        val request = client.requestBuilder(metaTokenUrl)
                .header("Accept", APPLICATION_JSON)
                .build()

        logger.debug("Requesting refreshToken with token-url {}", metaTokenUrl)

        return handleRequest(request, parser)
    }

    @Throws(IOException::class)
    fun refreshToken(authState: AppAuthState, parser: AuthStringParser): AppAuthState {
        try {
            val refreshToken = requireNotNull(authState.getAttribute(MP_REFRESH_TOKEN_PROPERTY)) {
                "No refresh token found"
            }

            val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build()

            val request = client.requestBuilder("oauth/token")
                    .post(body)
                    .addHeader("Authorization", credentials)
                    .build()

            return handleRequest(request, parser)
        } catch (e: MalformedURLException) {
            throw IllegalStateException("Failed to create request from ManagementPortal url", e)
        }

    }

    companion object {
        private val logger = LoggerFactory.getLogger(ManagementPortalClient::class.java)

        const val SOURCES_PROPERTY = "org.radarcns.android.auth.portal.ManagementPortalClient.sources"
        const val SOURCE_IDS_PROPERTY = "org.radarcns.android.auth.portal.ManagementPortalClient.sourceIds"
        const val MP_REFRESH_TOKEN_PROPERTY = "org.radarcns.android.auth.portal.ManagementPortalClient.refreshToken"
    }
}
