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

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Process
import androidx.activity.result.ActivityResultLauncher
import net.openid.appauth.*
import org.json.JSONException
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.config.SingleRadarConfiguration
import org.slf4j.LoggerFactory
import androidx.core.net.toUri
import androidx.core.content.edit
import org.radarbase.android.auth.commons.AbstractRadarPortalClient
import org.radarbase.android.auth.commons.AuthType
import org.radarbase.android.auth.portal.GetSubjectParser
import org.radarbase.android.auth.sep.SEPClient
import org.radarbase.android.util.SafeHandler
import java.util.concurrent.locks.ReentrantLock

class OAuth2StateManager(private val config: RadarConfiguration, private val oauthManager: OAuth2LoginManager, context: Context) {
    private val oauthPrefs: SharedPreferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private var currentAuthState: AuthState
    private val oAuthService: AuthorizationService = AuthorizationService(context)
    private val handler: SafeHandler = SafeHandler.getInstance("OAuth2StateManager-Handler", Process.THREAD_PRIORITY_BACKGROUND)
    private val refreshLock = ReentrantLock()

    init {
        currentAuthState = readState()
        handler.start()
    }

    fun login(
        config: SingleRadarConfiguration,
        activityResultLauncher: ActivityResultLauncher<Intent>,
    ) {
        if (refreshLock.tryLock()) {
            try {
                val (authorizeUri: Uri, tokenUri: Uri, redirectUri: Uri, clientId: String) = getOAuthConfig(config)
                logger.debug("Performing oAuth request at authorization url: {}", authorizeUri)

                val authConfig = AuthorizationServiceConfiguration(authorizeUri, tokenUri)
                val additionalParams = mapOf(
                    "audience" to DEFAULT_APP_RESOURCES
                )

                val authRequestBuilder = AuthorizationRequest.Builder(
                    authConfig,
                    clientId,
                    ResponseTypeValues.CODE,
                    redirectUri,
                ).apply {
                    setScopes(DEFAULT_OAUTH_SCOPES)
                    setAdditionalParameters(additionalParams)
                }

                logger.info("Performing auth request")
                val authIntent = oAuthService.getAuthorizationRequestIntent(authRequestBuilder.build())
                activityResultLauncher.launch(authIntent)
            } finally {
                refreshLock.unlock()
            }
        }
    }

    private fun getOAuthConfig(config: SingleRadarConfiguration): OAuthConfig {
        try {
            val authorizeUri = config.getString(RadarConfiguration.OAUTH2_AUTHORIZE_URL).toUri()
            val tokenUri = config.getString(RadarConfiguration.OAUTH2_TOKEN_URL).toUri()
            val redirectUri = config.getString(RadarConfiguration.OAUTH2_REDIRECT_URL, APP_REDIRECT_URI).toUri()
            val clientId = config.getString(RadarConfiguration.OAUTH2_CLIENT_ID)
            config.getString(RadarConfiguration.OAUTH2_CLIENT_SECRET)

            return OAuthConfig(authorizeUri, tokenUri, redirectUri, clientId)
        } catch (e: IllegalArgumentException) {
            throw MissingConfigurationException("Missing firebase remote configuration for either oauth endpoints, redirect uri or client credentials.", e)
        }
    }

    private fun logIntent(intent: Intent?) {
        val data = intent?.data
        val action = intent?.action
        val flags = intent?.flags
        val extrasKeys = intent?.extras?.keySet()?.joinToString(", ") ?: "null"
        logger.warn("(TestOauthDebug) AuthRequestDebug: action=$action, data=$data, flags=$flags, extras=$extrasKeys")
        if (intent?.extras != null) {
            intent.extras!!.keySet().forEach { k ->
                logger.warn("(TestOauthDebug) AuthRequestDebug: extra $k => ${intent.extras?.get(k)}")
            }
        }
    }

        fun updateAfterAuthorization(
        authService: AuthService,
        intent: Intent?,
        binder: AuthService.AuthServiceBinder,
        client: AbstractRadarPortalClient?
    ) {
        if (refreshLock.tryLock()) {
            try {
                if (intent == null) {
                    logger.info("Intent is null ")
                    return
                }
                logIntent(intent)
                val resp = AuthorizationResponse.fromIntent(intent)
                val ex = AuthorizationException.fromIntent(intent)

                if (resp == null && ex == null) {
                    logger.warn("Both response and exception are null for authorization redirect intent")
                }

                if (resp != null || ex != null) {
                    currentAuthState.update(resp, ex)
                    writeState(currentAuthState)
                }

                val clientAuth = clientSecretBasic()

                if (resp != null) {
                    binder.applyState {
                        oAuthService.performTokenRequest(
                            resp.createTokenExchangeRequest(),
                            clientAuth,
                            processTokenResponse(authService, this@applyState, client)
                        )
                    }
                } else if (ex != null) {
                    authService.loginFailed(null, ex)
                }
            } finally {
                refreshLock.unlock()
            }
        }
    }

    fun refresh(
        context: AuthService,
        authState: AppAuthState,
        refreshToken: String?,
        client: AbstractRadarPortalClient?
    ) {
        // refreshToken does not originate from the current auth state.
        if (refreshLock.tryLock()) {
            try {
                val clientAuth = clientSecretBasic()
                if (refreshToken != null && refreshToken != currentAuthState.refreshToken) {
                    try {
                        val json = currentAuthState.jsonSerialize()
                        json.put("refreshToken", refreshToken)
                        currentAuthState = AuthState.jsonDeserialize(json)
                    } catch (ex: JSONException) {
                        logger.error("Failed to update refresh token", ex)
                    }
                }
                // authorization succeeded
                oAuthService.performTokenRequest(
                    currentAuthState.createTokenRefreshRequest(),
                    clientAuth,
                    processTokenResponse(context, authState, client),
                )
            } finally {
                refreshLock.unlock()
            }
        }
    }

    private fun clientSecretBasic(): ClientSecretBasic {
        return config.latestConfig.getString(RadarConfiguration.OAUTH2_CLIENT_SECRET)
            .let { clientSecret ->
                ClientSecretBasic(clientSecret)
            }
    }

    private fun processTokenResponse(
        context: AuthService,
        authState: AppAuthState,
        client: AbstractRadarPortalClient?,
    ) = AuthorizationService.TokenResponseCallback { resp, ex ->
        resp ?: return@TokenResponseCallback context.loginFailed(null, ex)
        updateAfterTokenResponse(resp, ex)
        val accessTokenParser = OAuthAccessTokenParser()
        val updatedAuth = accessTokenParser.parse(authState, currentAuthState)

        logger.warn("(TestOauthDebug) Retrieved access token successfully, now getting subjects on thread: {}", Thread.currentThread().name)
        config.updateWithAuthState(context, updatedAuth)
        val getSubjectParser = GetSubjectParser(updatedAuth, AuthType.OAUTH2)
        if (client is SEPClient) {
            handler.compute {
                client.getSubject(updatedAuth, getSubjectParser)
            }.also { latestAuth ->
                oauthManager.loginSucceeded(null, latestAuth)
            }
        } else {
            logger.error("Can't process the get subject request, client is not the instance of SEP Client")
        }

    }

    @Synchronized
    fun updateAfterTokenResponse(
        response: TokenResponse?,
        ex: AuthorizationException?
    ) {
        currentAuthState.update(response, ex)
        writeState(currentAuthState)
    }

    @Synchronized
    private fun readState(): AuthState {
        val currentState = oauthPrefs.getString(KEY_STATE, null) ?: return AuthState()

        return try {
            AuthState.jsonDeserialize(currentState)
        } catch (ex: JSONException) {
            logger.warn("Failed to deserialize stored auth state - discarding", ex)
            writeState(null)
            AuthState()
        }

    }

    @Synchronized
    private fun writeState(state: AuthState?) {
        oauthPrefs.edit {
            if (state != null) {
                putString(KEY_STATE, state.jsonSerializeString())
            } else {
                remove(KEY_STATE)
            }
        }
    }

    fun stop() {
        oAuthService.dispose()
        handler.stop()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OAuth2StateManager::class.java)

        private const val STORE_NAME = "AuthState"
        private const val KEY_STATE = "state"
        private const val APP_REDIRECT_URI = "org.radarbase.prmt://login"
        private const val DEFAULT_OAUTH_SCOPES = "SUBJECT.READ SUBJECT.UPDATE MEASUREMENT.CREATE offline_access"
        private const val DEFAULT_APP_RESOURCES = "res_gateway res_ManagementPortal res_appconfig"

        class MissingConfigurationException(message: String) : RuntimeException(message) {
            constructor(message: String, cause: Throwable) : this(message) {
                initCause(cause)
            }
        }

        private data class OAuthConfig(
            val authorizeUri: Uri,
            val tokenUri: Uri,
            val redirectUri: Uri,
            val clientId: String,
        )
    }
}
