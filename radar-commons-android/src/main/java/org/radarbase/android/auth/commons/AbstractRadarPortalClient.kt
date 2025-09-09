package org.radarbase.android.auth.commons

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthStringParser
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.auth.SourceMetadata.Companion.optNonEmptyString
import org.radarbase.android.auth.portal.ConflictException
import org.radarbase.android.auth.portal.GetSubjectParser
import org.radarbase.android.util.Parser
import org.radarbase.config.ServerConfig
import org.radarbase.producer.AuthenticationException
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.RuntimeException

abstract class AbstractRadarPortalClient(
    private val authType: AuthType,
    portal: ServerConfig,
    client: RestClient? = null
) {
    val client: RestClient = (client?.newBuilder() ?: RestClient.newClient())
        .server(portal)
        .build()

    /**
     * Get subject information from the Management portal. This includes project ID, available
     * source types and assigned sources.
     * @param state current authentication state
     * @throws IOException if the management portal could not be reached or it gave an erroneous
     * response.
     */
    @Throws(IOException::class)
    fun getSubject(state: AppAuthState, parser: AuthStringParser): AppAuthState {
        if (state.userId == null) {
            throw IOException("Authentication state does not contain user ID")
        }
        val request = when(authType) {
            AuthType.MP -> client.requestBuilder("api/subjects/${state.userId}")
                .headers(state.okHttpHeaders)
                .header("Accept", APPLICATION_JSON)
                .build()

            AuthType.SEP, AuthType.OAUTH2 -> client.requestBuilder("managementportal/api/subjects/${state.userId}")
                .headers(state.okHttpHeaders)
                .header("Accept", APPLICATION_JSON)
                .build()
        }
        logger.info(
            "Requesting subject {} with parseHeaders {}", state.userId,
            state.okHttpHeaders
        )

        return handleRequest(request, parser)
    }

    @Throws(IOException::class)
    protected fun <T> handleRequest(request: Request, parser: Parser<String, T>): T {
        return client.request(request).use { response ->
            val body = RestClient.responseBody(response)
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("ManagementPortal did not yield a response body.")

            when (response.code) {
                401 -> throw AuthenticationException("QR code is invalid: $body")
                in 400..599 -> throw IOException("Failed to make request; response $body")
            }

            parser.parse(body)
        }
    }

    /** Register a source with the Management Portal.  */
    @Throws(IOException::class, JSONException::class)
    fun registerSource(auth: AppAuthState, source: SourceMetadata): SourceMetadata {
        return when (authType) {
            AuthType.MP -> handleSourceUpdateRequest(
                auth,
                "api/subjects/${auth.userId}/sources",
                sourceRegistrationBody(source),
                source,
            )

            AuthType.SEP, AuthType.OAUTH2 -> handleSourceUpdateRequest(
                auth,
                "managementportal/api/subjects/${auth.userId}/sources",
                sourceRegistrationBody(source),
                source,
            )
        }
    }

    /** Updates a source with the Management Portal.  */
    @Throws(IOException::class, JSONException::class)
    fun updateSource(auth: AppAuthState, source: SourceMetadata): SourceMetadata {
        return when (authType) {
            AuthType.MP -> handleSourceUpdateRequest(
                auth,
                "api/subjects/${auth.userId}/sources/${source.sourceName}",
                sourceUpdateBody(source),
                source,
            )

            AuthType.SEP, AuthType.OAUTH2 -> handleSourceUpdateRequest(
                auth,
                "managementportal/api/subjects/${auth.userId}/sources/${source.sourceName}",
                sourceUpdateBody(source),
                source,
            )
        }
    }

    private fun handleSourceUpdateRequest(
        auth: AppAuthState,
        relativePath: String,
        requestBody: JSONObject,
        source: SourceMetadata,
    ): SourceMetadata {
        val request = client.requestBuilder(relativePath)
            .post(requestBody.toString().toRequestBody(APPLICATION_JSON_TYPE))
            .headers(auth.okHttpHeaders)
            .header("Content-Type", APPLICATION_JSON_UTF8)
            .header("Accept", APPLICATION_JSON)
            .build()

        return client.request(request).use { response ->
            val responseBody = RestClient.responseBody(response)
                ?.takeIf { it.isNotEmpty() }
                ?: throw IOException("Source registration with the ManagementPortal did not yield a response body.")

            when (response.code) {
                401 -> throw AuthenticationException("Authentication failure with the ManagementPortal: $responseBody")
                403 -> throw UnsupportedOperationException("Not allowed to update source data: $responseBody")
                404 -> {
                    val error = JSONObject(responseBody)
                    if (error.getString("message").contains("source", true)) {
                        throw SourceNotFoundException("Source ${source.sourceId} is no longer registered with the ManagementPortal: $responseBody")
                    } else {
                        throw UserNotFoundException("User ${auth.userId} is no longer registered with the ManagementPortal: $responseBody")
                    }
                }

                409 -> throw ConflictException("Source registration conflicts with existing source registration: $responseBody")
                in 400..599 -> throw IOException("Cannot complete source registration with the ManagementPortal: $responseBody, using request $requestBody")
            }

            parseSourceRegistration(responseBody, source)
            source
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AbstractRadarPortalClient::class.java)

        const val APPLICATION_JSON = "application/json"
        private const val APPLICATION_JSON_UTF8 = "$APPLICATION_JSON; charset=utf-8"
        private val APPLICATION_JSON_TYPE = APPLICATION_JSON_UTF8.toMediaType()
        private val illegalSourceCharacters = "[^_'.@A-Za-z0-9- ]+".toRegex()

        @Throws(JSONException::class)
        internal fun sourceRegistrationBody(source: SourceMetadata): JSONObject {
            val requestBody = JSONObject()

            val typeId = requireNotNull(source.type?.id) { "Cannot register source without a type" }
            requestBody.put("sourceTypeId", typeId)

            source.sourceName?.let {
                val sourceName = it.replace(illegalSourceCharacters, "-")
                requestBody.put("sourceName", sourceName)
                logger.info("Add {} as sourceName", sourceName)
            }
            val sourceAttributes = source.attributes
            if (sourceAttributes.isNotEmpty()) {
                val attrs = JSONObject()
                for ((key, value) in sourceAttributes) {
                    attrs.put(key, value)
                }
                requestBody.put("attributes", attrs)
            }
            return requestBody
        }

        @Throws(JSONException::class)
        internal fun sourceUpdateBody(source: SourceMetadata): JSONObject {
            return JSONObject().apply {
                for ((key, value) in source.attributes) {
                    put(key, value)
                }
            }
        }

        /**
         * Parse the response of a subject/source registration.
         * @param body registration response body
         * @param source existing source to update with server information.
         * @throws JSONException if the provided body is not valid JSON with the correct properties
         */
        @Throws(JSONException::class)
        internal fun parseSourceRegistration(body: String, source: SourceMetadata) {
            logger.debug("Parsing source from {}", body)
            val responseObject = JSONObject(body)
            source.sourceId = responseObject.getString("sourceId")
            source.sourceName = responseObject.optNonEmptyString("sourceName") ?: source.sourceId
            source.expectedSourceName = responseObject.optNonEmptyString("expectedSourceName")
            source.attributes = GetSubjectParser.attributesToMap(
                responseObject.optJSONObject("attributes")
            )
        }

        class SourceNotFoundException(message: String) : RuntimeException(message)
        class UserNotFoundException(message: String) : RuntimeException(message)
        class MismatchedClientException(message: String) : RuntimeException(message)
    }
}