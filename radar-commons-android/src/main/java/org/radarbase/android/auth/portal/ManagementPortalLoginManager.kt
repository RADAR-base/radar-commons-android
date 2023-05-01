package org.radarbase.android.auth.portal

import org.json.JSONException
import org.radarbase.android.RadarConfiguration.Companion.MANAGEMENT_PORTAL_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.UNSAFE_KAFKA_CONNECTION
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.ServerConfigUtil.toServerConfig
import org.radarbase.config.ServerConfig
import org.radarbase.producer.AuthenticationException
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.MalformedURLException

class ManagementPortalLoginManager(state: AppAuthState) : LoginManager {
    private val sources: MutableMap<String, SourceMetadata> = mutableMapOf()

    private var client: ManagementPortalClient? = null
    private var clientConfig: ServerConfig? = null
    private var restClient: RestClient? = null

    init {
        updateSources(state)
    }

    override val sourceTypes: List<String> = listOf(SOURCE_TYPE)

    override fun fetch(appAuth: AppAuthState): AppAuthState? {
        val client = client ?: return null
        appAuth.userId ?: return null
        appAuth.token ?: return null
        appAuth.baseUrl ?: return null
        val newAuthState = client.getSubject(appAuth, GetSubjectParser(appAuth))
        updateSources(newAuthState)
        return newAuthState
    }

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
                val updatedAppAuth = removeSource(appAuth, source)
                registerSource(updatedAppAuth, source, success, failure)
            } catch (ex: ManagementPortalClient.Companion.UserNotFoundException) {
                logger.warn("User no longer exists - invalidating auth state")
                failure(ex)
            } catch (ex: java.lang.IllegalArgumentException) {
                logger.error("Source {} is not valid", source)
                failure(ex)
            } catch (ex: AuthenticationException) {
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

    override fun registerSource(authState: AppAuthState, source: SourceMetadata,
                                success: (AppAuthState, SourceMetadata) -> Unit,
                                failure: (Exception?) -> Unit): Boolean {
        logger.debug("Handling source registration")

        val existingSource = sources[source.sourceId]
        if (existingSource != null) {
            success(authState, existingSource)
            return true
        }

        client?.let { client ->
            try {
                val updatedSource = if (source.sourceId == null) {
                    // temporary measure to reuse existing source IDs if they exist
                    source.type?.id
                            ?.let { sourceType -> sources.values.find { it.type?.id == sourceType } }
                            ?.let {
                                source.sourceId = it.sourceId
                                source.sourceName = it.sourceName
                                client.updateSource(authState, source)
                            }
                            ?: client.registerSource(authState, source)
                } else {
                    client.updateSource(authState, source)
                }
                success(addSource(authState, updatedSource), updatedSource)
            } catch (ex: UnsupportedOperationException) {
                logger.warn("ManagementPortal does not support updating the app source.")
                success(addSource(authState, source), source)
            } catch (ex: ManagementPortalClient.Companion.SourceNotFoundException) {
                logger.warn("Source no longer exists - removing from auth state")
                val updatedAuthState = removeSource(authState, source)
                registerSource(updatedAuthState, source, success, failure)
            } catch (ex: ManagementPortalClient.Companion.UserNotFoundException) {
                logger.warn("User no longer exists - invalidating auth state")
                failure(ex)
            } catch (ex: ConflictException) {
                try {
                    client.getSubject(authState, GetSubjectParser(authState)).let { authState ->
                        updateSources(authState)
                        sources[source.sourceId]?.let { source ->
                            success(authState, source)
                        } ?: failure(IllegalStateException("Source was not added to ManagementPortal, even though conflict was reported."))
                    }
                } catch (ioex: IOException) {
                    logger.error("Failed to register source {} with {}: already registered",
                            source.sourceName, source.type, ex)

                    failure(ex)
                }
            } catch (ex: java.lang.IllegalArgumentException) {
                logger.error("Source {} is not valid", source)
                failure(ex)
            } catch (ex: AuthenticationException) {
                logger.error("Authentication error; failed to register source {} of type {}",
                        source.sourceName, source.type, ex)
                failure(ex)
            } catch (ex: IOException) {
                logger.error("Failed to register source {} with {}",
                        source.sourceName, source.type, ex)
                failure(ex)
            } catch (ex: JSONException) {
                logger.error("Failed to register source {} with {}",
                        source.sourceName, source.type, ex)
                failure(ex)
            }
        } ?: failure(IllegalStateException("Cannot register source without a client"))

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

    @Synchronized
    override fun configure(config: SingleRadarConfiguration) {
        val newClientConfig = try {
            config.getString(MANAGEMENT_PORTAL_URL_KEY).toServerConfig(
                isUnsafe = config.getBoolean(UNSAFE_KAFKA_CONNECTION, false)
            )
        } catch (e: MalformedURLException) {
            logger.error("Cannot construct ManagementPortalClient with malformed URL")
            null
        } catch (e: IllegalArgumentException) {
            logger.error("Cannot construct ManagementPortalClient without client credentials")
            null
        }

        if (newClientConfig == clientConfig) return

        client = newClientConfig?.let {
            ManagementPortalClient(
                newClientConfig,
                client = restClient,
            ).also { restClient = it.client }
        }
    }

    private fun addSource(authState: AppAuthState, source: SourceMetadata): AppAuthState {
        val sourceId = source.sourceId
        return if (sourceId == null) {
            logger.error("Cannot add source {} without ID", source)
            authState
        } else {
            sources[sourceId] = source

            authState.alter {
                val existing = sourceMetadata.filterTo(HashSet()) { it.sourceId == source.sourceId }
                if (existing.isEmpty()) {
                    invalidate()
                } else {
                    sourceMetadata -= existing
                }
                sourceMetadata += source
            }
        }
    }

    private fun removeSource(authState: AppAuthState, source: SourceMetadata): AppAuthState {
        sources.remove(source.sourceId)
        return authState.alter {
            val existing = sourceMetadata.filterTo(HashSet()) { it.sourceId == source.sourceId }
            if (existing.isNotEmpty()) {
                sourceMetadata -= existing
                invalidate()
            }
            source.sourceId = null
        }
    }

    companion object {
        const val SOURCE_TYPE = "org.radarcns.auth.portal.ManagementPortal"
        private val logger = LoggerFactory.getLogger(ManagementPortalLoginManager::class.java)
    }
}
