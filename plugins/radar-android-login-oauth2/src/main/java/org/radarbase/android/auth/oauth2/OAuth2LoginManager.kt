/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.android.auth.oauth2

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.Observer
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_ID
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_SECRET
import org.radarbase.android.RadarConfiguration.Companion.SEP_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.UNSAFE_KAFKA_CONNECTION
import org.radarbase.android.auth.*
import org.radarbase.android.auth.commons.AbstractRadarLoginManager
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.android.auth.commons.AuthType
import org.radarbase.android.auth.portal.GetSubjectParser.Companion.SOURCE_TYPE_OAUTH2
import org.radarbase.android.auth.sep.SEPLoginManager.SEPClientConfig
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.net.MalformedURLException

/**
 * Handles OAuth2 login flows using the [AppAuth-Android](https://openid.github.io/AppAuth-Android/) library.
 *
 * Provides initialization, token refresh, interactive login
 * with activity launchers, and response handling for OAuth2
 * providers configured in RADAR.
 *
 * @property service the [AuthService] for callback dispatching.
 *
 * @see OAuth2StateManager
 * @see AbstractRadarLoginManager
 */
class OAuth2LoginManager(
    private val service: AuthService,
) : AbstractRadarLoginManager(service, AuthType.OAUTH2), LoginListener {

    private val config = service.radarConfig
    private lateinit var stateManager: OAuth2StateManager

    override var client: AbstractRadarPortalClient? = null
    private var clientConfig: SEPClientConfig? = null
    private var restClient: RestClient? = null
    private lateinit var configUpdateObserver: Observer<SingleRadarConfiguration>

    override fun init(authState: AppAuthState?) {
        requireNotNull(authState) { "Failed to initialize OAuth2 manager, provided auth state is null" }
        stateManager = OAuth2StateManager(config, this, service)
        configUpdateObserver = Observer {
            ensureOAuthClientConnectivity(it)
        }
        mainHandler.post {
            config.config.observeForever(configUpdateObserver)
        }
        updateSources(authState)
        super.init(null)
    }

    override fun refresh(authState: AppAuthState): Boolean {
        if (authState.tokenType != LoginManager.AUTH_TYPE_BEARER || authState.getAttribute(OAUTH2_REFRESH_TOKEN_PROPERTY) == null) {
            return false
        }
        if (!isStarted) {
            init()
            ensureOAuthClientConnectivity(config.latestConfig)
        }
        return authState.getAttribute(OAUTH2_REFRESH_TOKEN_PROPERTY)
            ?.also { stateManager.refresh(service, authState, it, client) } != null
    }

    override fun isRefreshable(authState: AppAuthState): Boolean {
        return authState.userId != null
                && authState.projectId != null
                && authState.getAttribute(OAUTH2_REFRESH_TOKEN_PROPERTY) != null
    }

    override fun start(authState: AppAuthState, activityResultLauncher: ActivityResultLauncher<Intent>?) {
        checkManagerStarted()
        requireNotNull(activityResultLauncher) {
            "Activity result launcher can't be null in OAuthLoginManager"
        }
        val latestConfig = config.latestConfig

        ensureOAuthClientConnectivity(latestConfig)
        stateManager.login(latestConfig, activityResultLauncher)
    }

    override fun onActivityCreate(activity: Activity, binder: AuthService.AuthServiceBinder): Boolean {
        stateManager.updateAfterAuthorization(service, activity.intent, binder, client)
        return true
    }

    override fun invalidate(authState: AppAuthState, disableRefresh: Boolean): AppAuthState? {
        return when {
            authState.authenticationSource != SOURCE_TYPE_OAUTH2 -> null
            disableRefresh -> authState.alter {
                attributes -= OAUTH2_REFRESH_TOKEN_PROPERTY
                isPrivacyPolicyAccepted = false
            }
            else -> authState
        }
    }

    override val sourceTypes: List<String> = OAUTH2_SOURCE_TYPES

    @Synchronized
    private fun ensureOAuthClientConnectivity(config: SingleRadarConfiguration) {
        val oauthClientConfig = try {
            SEPClientConfig(
                config.getString(SEP_URL_KEY),
                config.getBoolean(UNSAFE_KAFKA_CONNECTION, false),
                config.getString(OAUTH2_CLIENT_ID),
                config.getString(OAUTH2_CLIENT_SECRET, ""),
            )
        } catch (_: MalformedURLException) {
            logger.error("Cannot construct OAuth client with malformed URL")
            null
        } catch (_: IllegalArgumentException) {
            logger.error("Cannot construct OAuth client without client credentials")
            null
        }

        if (oauthClientConfig == clientConfig) return

        client = oauthClientConfig?.let { oauthConfig ->
            OAuth2Client(
                oauthConfig.serverConfig,
                oauthConfig.clientId,
                oauthConfig.clientSecret,
                client = restClient
            ).also { oauth ->
                restClient = oauth.client
                clientConfig = oauthConfig
            }
        }
    }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        val token = authState.token
        if (token == null) {
            loginFailed(this,
                    IllegalArgumentException("Cannot login using OAuth2 without a token"))
            return
        }
        logger.warn("(TestOauthDebug) Login Succeeded callback in oauth login manager on thread: {}", Thread.currentThread().name)

        logger.info("Updating sources with latest state")
        updateSources(authState)
        service.loginSucceeded(manager, authState)
    }

    override fun loginFailed(manager: LoginManager?, ex: Exception?) = this.service.loginFailed(this, ex)

    override fun onDestroy() {
        if (!isStarted) return
        config.config.removeObserver(configUpdateObserver)
        stateManager.stop()
        super.onDestroy()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OAuth2LoginManager::class.java)

        const val OAUTH2_REFRESH_TOKEN_PROPERTY = "org.radarbase.auth.OAuth2LoginManager.refreshToken"
        val OAUTH2_SOURCE_TYPES = listOf(SOURCE_TYPE_OAUTH2)
    }
}
