package org.radarbase.android.auth.portal

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.Observer
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.MANAGEMENT_PORTAL_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_ID
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_SECRET
import org.radarbase.android.RadarConfiguration.Companion.UNSAFE_KAFKA_CONNECTION
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.auth.commons.AbstractRadarLoginManager
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.android.auth.commons.AuthType
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.MP_REFRESH_TOKEN_PROPERTY
import org.radarbase.android.util.ServerConfigUtil.toServerConfig
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.net.MalformedURLException
import java.util.concurrent.locks.ReentrantLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * Handles login and token management for the RADAR Management Portal.
 *
 * Supports setting refresh tokens directly or via meta-token URLs,
 * refreshing access tokens, and initiating login flows tied to the
 * Management Portal authentication backend.
 *
 * @see ManagementPortalClient
 * @see AbstractRadarLoginManager
 */
@Suppress("unused")
class ManagementPortalLoginManager(private val listener: AuthService, state: AppAuthState) :
    AbstractRadarLoginManager(listener, AuthType.MP) {

    override var client: AbstractRadarPortalClient? = null
    private var clientConfig: ManagementPortalConfig? = null
    private var restClient: RestClient? = null
    private var refreshLock: ReentrantLock = ReentrantLock()
    private val config = listener.radarConfig
    private var configUpdateObserver: Observer<SingleRadarConfiguration> = Observer {
        ensureMpClientConnectivity(it)
    }

    init {
        mainHandler.post {
            config.config.observeForever(configUpdateObserver)
        }
        updateSources(state)
    }

    fun setRefreshToken(authState: AppAuthState, refreshToken: String) {
        refresh(authState.alter { attributes[MP_REFRESH_TOKEN_PROPERTY] = refreshToken })
    }

    fun setTokenFromUrl(authState: AppAuthState, refreshTokenUrl: String) {
        client?.let { client ->
            if (ensureMpClient(client)) {
                if (refreshLock.tryLock()) {
                    try {
                        // create parser
                        val parser = MetaTokenParser(authState)

                        // retrieve token and update authState
                        client.getRefreshToken(refreshTokenUrl, parser).let { authState ->
                            // update radarConfig
                            config.updateWithAuthState(listener, authState)
                            // refresh client
                            ensureMpClientConnectivity(config.latestConfig)
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
            } else {
                logger.error("Invalid client type detected: expected ManagementPortalClient for the meta token URL. Terminating operation")
            }
        }
    }

    override fun refresh(authState: AppAuthState): Boolean {
        if (authState.getAttribute(MP_REFRESH_TOKEN_PROPERTY) == null) {
            return false
        }
        client?.let { client ->
            if (ensureMpClient(client)) {
                if (refreshLock.tryLock()) {
                    try {
                        val parser = SubjectTokenParser(client, AuthType.MP, authState)

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
            } else {
                logger.error("Invalid client type detected: expected ManagementPortalClient for refresh_token flow. Terminating operation.")
            }
        }
        return true
    }

    override fun isRefreshable(authState: AppAuthState): Boolean {
        return authState.userId != null
                && authState.projectId != null
                && authState.getAttribute(MP_REFRESH_TOKEN_PROPERTY) != null
    }

    override fun start(authState: AppAuthState, activityResultLauncher: ActivityResultLauncher<Intent>?) {
        refresh(authState)
    }

    override fun onActivityCreate(activity: Activity, binder: AuthService.AuthServiceBinder): Boolean {
        return false
    }

    override fun invalidate(authState: AppAuthState, disableRefresh: Boolean): AppAuthState? {
        return when {
            authState.authenticationSource != SOURCE_TYPE_MP -> null
            disableRefresh -> authState.alter {
                attributes -= MP_REFRESH_TOKEN_PROPERTY
                isPrivacyPolicyAccepted = false
            }
            else -> authState
        }
    }

    override val sourceTypes: List<String> = sourceTypeList


    override fun onDestroy() {
        config.config.removeObserver(configUpdateObserver)
    }

    @Synchronized
    private fun ensureMpClientConnectivity(config: SingleRadarConfiguration) {
        val mpClientConfig = try {
            ManagementPortalConfig(
                config.getString(MANAGEMENT_PORTAL_URL_KEY),
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

        if (mpClientConfig == clientConfig) return

        client = mpClientConfig?.let { mpConfig ->
            ManagementPortalClient(
                mpConfig.serverConfig,
                mpConfig.clientId,
                mpConfig.clientSecret,
                client = restClient,
            ).also {
                restClient = it.client
                clientConfig = mpConfig
            }
        }
    }

    companion object {
        const val SOURCE_TYPE_MP = "org.radarcns.auth.portal.ManagementPortal"
        private val logger = LoggerFactory.getLogger(ManagementPortalLoginManager::class.java)
        private val sourceTypeList = listOf(SOURCE_TYPE_MP)

        @OptIn(ExperimentalContracts::class)
        private fun ensureMpClient(client: AbstractRadarPortalClient): Boolean {
            contract {
                returns(true) implies (client is ManagementPortalClient)
            }
            return client is ManagementPortalClient
        }
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
