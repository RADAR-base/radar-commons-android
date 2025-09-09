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
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_ID
import org.radarbase.android.RadarConfiguration.Companion.OAUTH2_CLIENT_SECRET
import org.radarbase.android.RadarConfiguration.Companion.SEP_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.UNSAFE_KAFKA_CONNECTION
import org.radarbase.android.auth.*
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.android.auth.sep.SEPClient
import org.radarbase.android.auth.sep.SEPLoginManager.Companion.SOURCE_TYPE_OAUTH2
import org.radarbase.android.auth.sep.SEPLoginManager.SEPClientConfig
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.producer.AuthenticationException
import org.radarbase.producer.rest.RestClient
import org.slf4j.LoggerFactory
import java.net.MalformedURLException
import java.util.concurrent.locks.ReentrantLock

/**
 * Authenticates against the RADAR Management Portal.
 */
class OAuth2LoginManager(
    private val service: AuthService,
) : LoginManager, LoginListener {
    private val config = service.radarConfig
    private val stateManager: OAuth2StateManager = OAuth2StateManager(config, service)

    var client: AbstractRadarPortalClient? = null
    private var clientConfig: SEPClientConfig? = null
    private var restClient: RestClient? = null
    private val refreshLock = ReentrantLock()
    private val configUpdateObserver = Observer<SingleRadarConfiguration> {
        ensureClientConnectivity(it)
    }

    init {
        config.config.observeForever(configUpdateObserver)
    }

        override fun refresh(authState: AppAuthState): Boolean {
        if (authState.tokenType != LoginManager.AUTH_TYPE_BEARER) {
            return false
        }
        return authState.getAttribute(OAUTH_REFRESH_TOKEN)
                ?.also { stateManager.refresh(service, authState, it, client) } != null
    }
    
    override fun isRefreshable(authState: AppAuthState): Boolean =
        authState.userId != null && authState.getAttribute(OAUTH_REFRESH_TOKEN) != null

    override fun start(authState: AppAuthState, activityResultLauncher: ActivityResultLauncher<Intent>?) {
        requireNotNull(activityResultLauncher) {
            "Activity result launcher can't be null in OAuthLoginManager"
        }
        config.updateWithAuthState(service, authState)
        ensureClientConnectivity(config.latestConfig)

        service.radarApp.let { app ->
            stateManager.login(
                app.configuration.latestConfig,
                activityResultLauncher
            )
        }
    }

    override fun onActivityCreate(activity: Activity, binder: AuthService.AuthServiceBinder): Boolean {
        stateManager.updateAfterAuthorization(service, activity.intent, binder, client)
        return true
    }

    override fun invalidate(authState: AppAuthState, disableRefresh: Boolean): AppAuthState? =
        authState.takeIf { it.authenticationSource == SOURCE_TYPE_OAUTH2 }

    override val sourceTypes: List<String> = OAUTH2_SOURCE_TYPES

    @Throws(AuthenticationException::class)
    override fun registerSource(authState: AppAuthState, source: SourceMetadata,
                       success: (AppAuthState, SourceMetadata) -> Unit,
                       failure: (Exception?) -> Unit): Boolean {
        success(authState, source)
        return true
    }

    @Throws(AuthenticationException::class)
    override fun updateSource(appAuth: AppAuthState, source: SourceMetadata,
                              success: (AppAuthState, SourceMetadata) -> Unit,
                              failure: (Exception?) -> Unit): Boolean {
        success(appAuth, source)
        return true
    }

    @Synchronized
    private fun ensureClientConnectivity(config: SingleRadarConfiguration) {
        val newClientConfig = try {
            SEPClientConfig(
                config.getString(SEP_URL_KEY),
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

        if (newClientConfig == clientConfig) return

        client = newClientConfig?.let {
            SEPClient(
                it.serverConfig,
                it.clientId,
                it.clientSecret,
                client = restClient
            ).also { sep ->
                restClient = sep.client
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
    }

    override fun loginFailed(manager: LoginManager?, ex: Exception?) = this.service.loginFailed(this, ex)

    override fun onDestroy() {
        config.config.removeObserver(configUpdateObserver)
        stateManager.stop()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OAuth2LoginManager::class.java)

        const val OAUTH_REFRESH_TOKEN = "org.radarcns.auth.OAuth2LoginManager.refreshToken"
        val OAUTH2_SOURCE_TYPES = listOf(SOURCE_TYPE_OAUTH2)
    }
}
