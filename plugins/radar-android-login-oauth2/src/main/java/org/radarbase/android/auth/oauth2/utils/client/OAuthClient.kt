package org.radarbase.android.auth.oauth2.utils.client

import okhttp3.MediaType.Companion.toMediaType
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthStringParser
import org.radarbase.android.auth.AuthenticationSource
import org.radarbase.android.auth.portal.GetSubjectParser
import org.radarbase.android.auth.portal.ManagementPortalClient
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException

class OAuthClient(serverConfig: ServerConfig, client: RestClient? = null) {

    val httpClient: RestClient = (client?.newBuilder() ?: RestClient.newClient())
        .server(serverConfig)
        .build()

    @Throws(IOException::class)
    fun requestSubjectsFromMp(state: AppAuthState): AppAuthState {
        if (state.userId == null) {
            throw IOException("AppAuthState doesn't contains userId")
        }
        logger.debug("Fetching Subject from MP via oAuth flow")

        httpClient.requestBuilder("/api/subjects/${state.userId}")
            .headers(state.okHttpHeaders)
            .header(ACCEPT_HEADER, APPLICATION_JSON)
            .build().also {
                httpClient.request(it).use { res ->
                    val body = RestClient.responseBody(res)
                        ?.takeIf { it.isNotEmpty() }
                        ?: throw IOException("Received empty response for subject from MP")

                    when (res.code) {
                        in 400..599 -> throw IOException("Failed to make request; response $body")
                    }

                    return GetSubjectParser(state, AuthenticationSource.SELF_ENROLMENT_PORTAL).parse(body)
                }
            }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(OAuthClient::class.java)
        private const val ACCEPT_HEADER = "Accept"
        private const val APPLICATION_JSON = "application/json"
        private const val APPLICATION_JSON_UTF8 = "$APPLICATION_JSON; charset=utf-8"
        private val APPLICATION_JSON_TYPE = APPLICATION_JSON_UTF8.toMediaType()
    }
}