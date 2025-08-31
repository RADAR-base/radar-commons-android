package org.radarbase.android.auth.sep

import okhttp3.Credentials
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient

class SEPClient(
    sep: ServerConfig,
    clientId: String,
    clientSecret: String,
    client: RestClient? = null
) : AbstractRadarPortalClient(sep, client) {
    private val credentials = Credentials.basic(clientId, clientSecret)

    companion object {
        const val SEP_REFRESH_TOKEN_PROPERTY = "org.radarbase.android.auth.sep.SEPClient.refreshToken"
    }
}