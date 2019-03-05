package org.radarcns.android.auth.portal

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.support.v4.content.LocalBroadcastManager
import org.json.JSONException
import org.radarcns.android.RadarApplication
import org.radarcns.android.RadarConfiguration.*
import org.radarcns.android.auth.AppAuthState
import org.radarcns.android.auth.AuthService
import org.radarcns.android.auth.LoginManager
import org.radarcns.android.auth.SourceMetadata
import org.radarcns.android.auth.portal.ManagementPortalClient.Companion.MP_REFRESH_TOKEN_PROPERTY
import org.radarcns.config.ServerConfig
import org.radarcns.producer.AuthenticationException
import org.radarcns.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.MalformedURLException
import java.util.concurrent.locks.ReentrantLock

class ManagementPortalLoginManager(private val listener: AuthService, state: AppAuthState) : LoginManager {
    private val sources: MutableMap<String, SourceMetadata> = mutableMapOf()
    private val firebaseUpdateReceiver: BroadcastReceiver

    private var client: ManagementPortalClient? = null
    private var restClient: RestClient? = null
    private val refreshLock: ReentrantLock
    private val config = (listener.application as RadarApplication).configuration

    init {
        ensureClientConnectivity()

        firebaseUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                ensureClientConnectivity()
            }
        }
        LocalBroadcastManager.getInstance(listener)
                .registerReceiver(firebaseUpdateReceiver, IntentFilter(RADAR_CONFIGURATION_CHANGED))
        updateSources(state)
        refreshLock = ReentrantLock()
    }

    fun setRefreshToken(authState: AppAuthState, refreshToken: String) {
        refresh(authState.alter { attributes[MP_REFRESH_TOKEN_PROPERTY] = refreshToken })
    }

    fun setTokenFromUrl(authState: AppAuthState, refreshTokenUrl: String) {
        client?.let { client ->
            if (refreshLock.tryLock()) {
                try {
                    // create parser
                    val parser = MetaTokenParser(authState)

                    // retrieve token and update authState
                    client.getRefreshToken(refreshTokenUrl, parser).let { authState ->
                        // update radarConfig
                        if (config.updateWithAuthState(listener, authState)) {
                            config.persistChanges()
                            // refresh client
                            ensureClientConnectivity()
                        }
                        logger.info("Retrieved refreshToken from url")
                        // refresh token
                        refresh(authState)
                    }
                } catch (ex: Exception) {
                    logger.error("Failed to get meta token", ex)
                    listener.loginFailed(this, ex)
                } finally {
                    refreshLock.unlock()
                }
            }
        }
    }

    override fun refresh(authState: AppAuthState): Boolean {
        if (authState.getAttribute(MP_REFRESH_TOKEN_PROPERTY) == null) {
            return false
        }
        client?.let { client ->
            if (refreshLock.tryLock()) {
                try {
                    val parser = SubjectTokenParser(client, authState)

                    client.refreshToken(authState, parser).let { authState ->
                        logger.info("Refreshed JWT")

                        updateSources(authState)
                        listener.loginSucceeded(this, authState)
                    }
                } catch (ex: Exception) {
                    logger.error("Failed to get access token", ex)
                    listener.loginFailed(this, ex)
                } finally {
                    refreshLock.unlock()
                }
            }
        }
        return true
    }

    override fun isRefreshable(authState: AppAuthState): Boolean {
        return authState.userId != null
                && authState.projectId != null
                && authState.getAttribute(MP_REFRESH_TOKEN_PROPERTY) != null
    }

    override fun start(authState: AppAuthState) {
        refresh(authState)
    }

    override fun onActivityCreate(activity: Activity): Boolean {
        return false
    }

    override fun invalidate(authState: AppAuthState, disableRefresh: Boolean): AppAuthState? {
        if (authState.authenticationSource != SOURCE_TYPE) {
            return null
        }
        return authState.alter {
            attributes -= MP_REFRESH_TOKEN_PROPERTY
            isPrivacyPolicyAccepted = false
        }
    }

    override val sourceTypes: List<String> = sourceTypeList

    override fun registerSource(authState: AppAuthState, source: SourceMetadata,
                                success: (AppAuthState, SourceMetadata) -> Unit,
                                failure: (Exception?) -> Unit): Boolean {
        logger.debug("Handling source registration")

        sources[source.sourceId]?.also { resultSource ->
            success(authState, resultSource)
            return true
        }

        client?.let { client ->
            try {
                client.registerSource(authState, source).let { source ->
                    success(addSource(authState, source), source)
                }
            } catch (ex: UnsupportedOperationException) {
                logger.warn("ManagementPortal does not support updating the app source.")
                success(addSource(authState, source), source)
            } catch (ex: ConflictException) {
                try {
                    client.getSubject(authState, GetSubjectParser(authState)).let { authState ->
                        updateSources(authState)
                        sources[source.sourceId]?.let { source ->
                            success(authState, source)
                        }
                                ?: failure(IllegalStateException("Source was not added to ManagementPortal, even though conflict was reported."))
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
                listener.invalidate(authState.token, false)
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
                .filter { it.sourceId != null }
                .forEach { sources[it.sourceId!!] = it }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(listener).unregisterReceiver(firebaseUpdateReceiver)
    }

    @Synchronized
    private fun ensureClientConnectivity() {
        val url = config.getString(MANAGEMENT_PORTAL_URL_KEY)
        val unsafe = config.getBoolean(UNSAFE_KAFKA_CONNECTION, false)
        try {
            val portalConfig = ServerConfig(url)
            portalConfig.isUnsafe = unsafe
            client = ManagementPortalClient(portalConfig,
                    config.getString(OAUTH2_CLIENT_ID),
                    config.getString(OAUTH2_CLIENT_SECRET, ""), client = restClient)
                    .also { restClient = it.client }
        } catch (e: MalformedURLException) {
            logger.error("Cannot construct ManagementPortalClient with malformed URL")
            client = null
        } catch (e: IllegalArgumentException) {
            logger.error("Cannot construct ManagementPortalClient without client credentials")
            client = null
        }
    }

    private fun addSource(authState: AppAuthState, source: SourceMetadata): AppAuthState {
        if (source.sourceId == null) {
            logger.error("Cannot add source {} without ID", source)
            return authState
        }

        sources[source.sourceId!!] = source

        return authState.alter {
            val existing = sourceMetadata.filter { it.sourceId == source.sourceId }
            if (existing.isEmpty()) {
                invalidate()
            } else {
                sourceMetadata -= existing
            }
            sourceMetadata += source
        }
    }

    companion object {
        const val SOURCE_TYPE = "org.radarcns.auth.portal.ManagementPortal"
        private val logger = LoggerFactory.getLogger(ManagementPortalLoginManager::class.java)
        val sourceTypeList = listOf(SOURCE_TYPE)
    }
}
