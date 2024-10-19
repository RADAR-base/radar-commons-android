package org.radarbase.android.auth.portal

import android.app.Activity
import androidx.lifecycle.Observer
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.MANAGEMENT_PORTAL_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_ID
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_SECRET
import org.radarbase.android.RadarConfiguration.Companion.UNSAFE_KAFKA_CONNECTION
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.MP_REFRESH_TOKEN_PROPERTY
import org.radarbase.android.util.ServerConfigUtil.toServerConfig
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.config.ServerConfig
import org.radarbase.management.auth.MPOAuth2AccessToken
import org.radarbase.management.client.MPClient
import org.radarbase.management.client.mpClient
import org.radarbase.producer.AuthenticationException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.MalformedURLException

class ManagementPortalLoginManager(
    private val listener: AuthService,
    private val authState: StateFlow<AppAuthState>
) : LoginManager {
    private val sources: MutableMap<String, SourceMetadata> = mutableMapOf()

    private var client: MPClient? = null
    private var clientConfig: ManagementPortalConfig? = null
    private val config = listener.radarConfig
    private val configUpdateObserver = Observer<SingleRadarConfiguration> {
        ensureClientConnectivity(it)
    }

    init {
        config.config.observeForever(configUpdateObserver)
        updateSources(authState)
    }

    suspend fun setRefreshToken(authState: AppAuthState.Builder, refreshToken: String) {
        refresh(authState.alter { attributes[MP_REFRESH_TOKEN_PROPERTY] = refreshToken })
    }

    suspend fun setTokenFromUrl(authState: AppAuthState.Builder, refreshTokenUrl: String) {
        client?.let { client ->
            refreshLock.withLock {
                try {
                    // create parser
                    val parser = MetaTokenParser(authState)

                    // retrieve token and update authState
                    client.getRefreshToken(refreshTokenUrl, parser).let { authState ->
                        // update radarConfig
                        config.updateWithAuthState(listener, authState)
                        // refresh client
                        ensureClientConnectivity(config.latestConfig)
                        logger.info("Retrieved refreshToken from url")
                        // refresh token
                        refresh(authState)
                    }
                } catch (ex: Exception) {
                    logger.error("Failed to get meta token", ex)
                    listener.loginFailed(this, ex)
                }
            }
        }
    }

    override suspend fun refresh(authState: AppAuthState.Builder): Boolean {
        val client = client ?: return true
        return refreshLock.withLock {
            try {
                val parser = SubjectTokenParser(client, authState)

                client.refreshToken(authState, parser).let { authState ->
                    logger.info("Refreshed JWT")

                    updateSources(authState)
                    listener.loginSucceeded(this, authState)
                }
                true
            } catch (ex: Exception) {
                logger.error("Failed to get access token", ex)
                throw ex
            }
        }
    }

    override fun isRefreshable(authState: AppAuthState): Boolean {
        return authState.userId != null
                && authState.projectId != null
                && authState.getAttribute(MP_REFRESH_TOKEN_PROPERTY) != null
    }

    override suspend fun start(authState: AppAuthState) {
        refresh(authState)
    }

    override fun onActivityCreate(activity: Activity): Boolean {
        return false
    }

    override suspend fun invalidate(authState: AppAuthState.Builder, disableRefresh: Boolean) {
        if (disableRefresh) {
            authState.clear()
        }
    }

    override val sourceTypes: Set<String> = sourceTypeList

    override fun updateSource(appAuth: AppAuthState, source: SourceMetadata, success: (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit): Boolean {
        logger.debug("Handling source update")

        val client = client

        if (client != null) {
            try {
                val updatedSource = client.updateSource(appAuth, source)
                success(addSource(appAuth, updatedSource), updatedSource)
            } catch (ex: UnsupportedOperationException) {
                logger.warn("ManagementPortal does not support updating the app source.")
                success(addSource(appAuth, source), source)
            } catch (ex: ManagementPortalClient.Companion.SourceNotFoundException) {
                logger.warn("Source no longer exists - removing from auth state")
                val updatedAppAuth = appAuth.removeSource(source)
                registerSource(updatedAppAuth, source, success, failure)
            } catch (ex: ManagementPortalClient.Companion.UserNotFoundException) {
                logger.warn("User no longer exists - invalidating auth state")
                listener.invalidate(appAuth.token, false)
                failure(ex)
            } catch (ex: java.lang.IllegalArgumentException) {
                logger.error("Source {} is not valid", source)
                failure(ex)
            } catch (ex: AuthenticationException) {
                listener.invalidate(appAuth.token, false)
                logger.error("Authentication error; failed to update source {} of type {}",
                    source.sourceName, source.type, ex)
                failure(ex)
            } catch (ex: IOException) {
                logger.error("Failed to update source {} with {}",
                    source.sourceName, source.type, ex)
                failure(ex)
            } catch (ex: JSONException) {
                logger.error("Failed to update source {} with {}",
                    source.sourceName, source.type, ex)
                failure(ex)
            }
        } else {
            failure(IllegalStateException("Cannot update source without a client"))
        }

        return true
    }

    override suspend fun registerSource(authState: AppAuthState.Builder, source: SourceMetadata): SourceMetadata {
        logger.debug("Handling source registration")

        val existingSource = sources[source.sourceId]
        if (existingSource != null) {
            return existingSource
        }

        val client = requireNotNull(client) { "Cannot register source without a client" }
        requireNotNull(authState.original) { "Cannot register source without a base authentication." }

        return try {
            val updatedSource = if (source.sourceId == null) {
                // temporary measure to reuse existing source IDs if they exist
                source.type?.id
                        ?.let { sourceType -> sources.values.find { it.type?.id == sourceType } }
                        ?.let {
                            source.sourceId = it.sourceId
                            source.sourceName = it.sourceName
                            client.updateSource(authState.original, source)
                        }
                        ?: client.registerSource(authState.original, source)
            } else {
                client.updateSource(authState.original, source)
            }
            authState.addSource(updatedSource)
            updatedSource
        } catch (ex: UnsupportedOperationException) {
            logger.warn("ManagementPortal does not support updating the app source.")
            authState.addSource(source)
            source
        } catch (ex: ManagementPortalClient.Companion.SourceNotFoundException) {
            logger.warn("Source no longer exists - removing from auth state")
            authState.removeSource(source)
            registerSource(authState, source)
        } catch (ex: ManagementPortalClient.Companion.UserNotFoundException) {
            logger.warn("User no longer exists - invalidating auth state")
            listener.invalidate(authState.token, false)
            throw ex
        } catch (ex: ConflictException) {
            try {
                client.getSubject(authState, GetSubjectParser(authState)).let { authState ->
                    updateSources(authState)
                    requireNotNull(sources[source.sourceId]) { "Source was not added to ManagementPortal, even though conflict was reported." }
                }
            } catch (ioex: IOException) {
                logger.error("Failed to register source {} with {}: already registered",
                        source.sourceName, source.type, ex)
                throw ex
            }
        } catch (ex: java.lang.IllegalArgumentException) {
            logger.error("Source {} is not valid", source)
            throw ex
        } catch (ex: AuthenticationException) {
            listener.invalidate(authState.token, false)
            logger.error("Authentication error; failed to register source {} of type {}",
                    source.sourceName, source.type, ex)
            throw ex
        } catch (ex: IOException) {
            logger.error("Failed to register source {} with {}",
                    source.sourceName, source.type, ex)
            throw ex
        } catch (ex: JSONException) {
            logger.error("Failed to register source {} with {}",
                    source.sourceName, source.type, ex)
            throw ex
        }

        return true
    }


    private fun updateSources(authState: AppAuthState) {
        authState.sourceMetadata
                .forEach { sourceMetadata ->
                    sourceMetadata.sourceId?.let {
                        sources[it] = sourceMetadata
                    }
                }
    }

    override fun onDestroy() {
        config.config.removeObserver(configUpdateObserver)
    }

    @Synchronized
    private fun ensureClientConnectivity(config: SingleRadarConfiguration) {
        val newClientConfig = try {
            ManagementPortalConfig(
                config.getString(MANAGEMENT_PORTAL_URL_KEY),
                config.getBoolean(UNSAFE_KAFKA_CONNECTION, false),
                config.getString(OAUTH2_CLIENT_ID),
                config.getString(OAUTH2_CLIENT_SECRET, ""),
            )
        } catch (e: MalformedURLException) {
            logger.error("Cannot construct ManagementPortalClient with malformed URL")
            null
        } catch (e: IllegalArgumentException) {
            logger.error("Cannot construct ManagementPortalClient without client credentials")
            null
        }

        if (newClientConfig == clientConfig) return

        client = mpClient {
            url = config.getString(MANAGEMENT_PORTAL_URL_KEY)
            auth { emit ->
                bearer {
                    refreshTokens {
                        val oldRefreshToken = oldTokens?.refreshToken
                            ?: authState.value.attributes[MP_REFRESH_TOKEN_PROPERTY]
                            ?: return@refreshTokens null

                        val oauthToken = client.post("oauth/token") {
                            setBody(formData {
                                append("grant_type", "refresh_token")
                                append("refresh_token", oldRefreshToken)
                            })
                            basicAuth(
                                config.getString(OAUTH2_CLIENT_ID),
                                config.getString(OAUTH2_CLIENT_SECRET, ""),
                            )
                            markAsRefreshTokenRequest()
                        }.body<MPOAuth2AccessToken>()

                        emit(oauthToken)

                        val accessToken = oauthToken.accessToken
                        val refreshToken = oauthToken.refreshToken
                        if (accessToken != null && refreshToken != null) {
                            BearerTokens(accessToken, refreshToken)
                        } else null
                    }
                }
            }
        }
    }

    private fun AppAuthState.Builder.addSource(source: SourceMetadata) {
        val sourceId = source.sourceId
        return if (sourceId == null) {
            logger.error("Cannot add source {} without ID", source)
        } else {
            sources[sourceId] = source

            val containedPreviousSource = sourceMetadata.removeAll { it.sourceId == sourceId }
            if (!containedPreviousSource) {
                invalidate()
            }
            sourceMetadata += source
        }
    }

    private fun AppAuthState.Builder.removeSource(source: SourceMetadata) {
        sources.remove(source.sourceId)
        val containedPreviousSource = sourceMetadata.removeAll { it.sourceId == source.sourceId }
        if (containedPreviousSource) {
            invalidate()
        }
        source.sourceId = null
    }

    companion object {
        const val SOURCE_TYPE = "org.radarcns.auth.portal.ManagementPortal"
        private val logger = LoggerFactory.getLogger(ManagementPortalLoginManager::class.java)
        val sourceTypeList = setOf(SOURCE_TYPE)
    }

    private data class ManagementPortalConfig(
        val serverConfig: ServerConfig,
        val clientId: String,
        val clientSecret: String,
    ) {
        constructor(
            url: String,
            unsafe: Boolean,
            clientId: String,
            clientSecret: String,
        ) : this(
            url.toServerConfig(unsafe),
            clientId,
            clientSecret
        )
    }
}
