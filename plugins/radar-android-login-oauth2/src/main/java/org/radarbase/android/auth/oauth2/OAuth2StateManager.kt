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
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.AnyThread
import net.openid.appauth.*
import org.json.JSONException
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.config.SingleRadarConfiguration
import org.slf4j.LoggerFactory
import androidx.core.net.toUri
import androidx.core.content.edit
import org.radarbase.android.RadarApplication

class OAuth2StateManager(private val config: RadarConfiguration, context: Context) {
    private val mPrefs: SharedPreferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private var mCurrentAuthState: AuthState
    private val oAuthService: AuthorizationService = AuthorizationService(context)

    init {
        mCurrentAuthState = readState()
    }

    val appContext = (context.applicationContext as RadarApplication).loginActivity

    @AnyThread
    @Synchronized
    fun login(
        context: AuthService,
        activityClass: Class<out Activity>,
        config: SingleRadarConfiguration,
        activityResultLauncher: ActivityResultLauncher<Intent>
    ) {

        val authorizeUri = config.getString(RadarConfiguration.OAUTH2_AUTHORIZE_URL).toUri()
        val tokenUri = config.getString(RadarConfiguration.OAUTH2_TOKEN_URL).toUri()
        val redirectUri = config.getString(RadarConfiguration.OAUTH2_REDIRECT_URL, APP_REDIRECT_URI).toUri()
        val clientId = config.getString(RadarConfiguration.OAUTH2_CLIENT_ID)

        logger.info("Performing o auth request with authorize uri: {}", authorizeUri)

        val authConfig = AuthorizationServiceConfiguration(authorizeUri, tokenUri)
        val additionalParams = mapOf(
            "audience" to DEFAUL_APP_RESOURCES
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

//        service.performAuthorizationRequest(
//            authRequestBuilder.build(),
//            PendingIntent.getActivity(
//                context,
//                OAUTH_INTENT_HANDLER_REQUEST_CODE,
//                Intent(context, activityClass),
//                PendingIntent.FLAG_UPDATE_CURRENT.toPendingIntentFlag(),
//            ),
//        )
        logger.info("Performed auth request")
    }

    @AnyThread
    @Synchronized
    fun updateAfterAuthorization(authService: AuthService, intent: Intent?, binder: AuthService.AuthServiceBinder) {
        logger.info("AuthRequestDebug: updating after authorization, is the intent null? {} if not the data is {}", intent == null, intent?.data.toString())
        if (intent == null) {
            return
        }

        val resp = AuthorizationResponse.fromIntent(intent)
        val ex = AuthorizationException.fromIntent(intent)

        val redirectUri = intent.data
        if (resp == null && ex == null) {
            logger.warn("AuthRequestDebug: No resp/ex, but redirectUri=$redirectUri")
        }

        if (resp != null || ex != null) {
            mCurrentAuthState.update(resp, ex)
            writeState(mCurrentAuthState)
        }

        val clientSecret: String = config.latestConfig.getString(RadarConfiguration.OAUTH2_CLIENT_SECRET)
        val clientAuth = ClientSecretBasic(clientSecret)

        if (resp != null) {
            logger.info("AuthRequestDebug: Response is not null, now performing the token request")
            binder.applyState {
                logger.info("AuthRequestDebug: Applying the auth state")
                oAuthService.performTokenRequest(
                    resp.createTokenExchangeRequest(),
                    clientAuth,
                    processTokenResponse(authService, this@applyState)
                )
            }
        } else if (ex != null) {
            logger.info("AuthRequestDebug: Exception is not null")
            authService.loginFailed(null, ex)
        }
    }

    @Synchronized
    fun refresh(context: AuthService, authState: AppAuthState, refreshToken: String?) {
        // refreshToken does not originate from the current auth state.

        val clientSecret: String = config.latestConfig.getString(RadarConfiguration.OAUTH2_CLIENT_SECRET)
        val clientAuth = ClientSecretBasic(clientSecret)

        if (refreshToken != null && refreshToken != mCurrentAuthState.refreshToken) {
            try {
                val json = mCurrentAuthState.jsonSerialize()
                json.put("refreshToken", refreshToken)
                mCurrentAuthState = AuthState.jsonDeserialize(json)
            } catch (e: JSONException) {
                logger.error("Failed to update refresh token")
            }
        }
        // authorization succeeded
        oAuthService.performTokenRequest(
            mCurrentAuthState.createTokenRefreshRequest(),
            clientAuth,
            processTokenResponse(context, authState),
        )
    }

    private fun processTokenResponse(
        context: AuthService,
        authState: AppAuthState,
    ) = AuthorizationService.TokenResponseCallback { resp, ex ->
        logger.info("AuthRequestDebug: Now processing the token response")
        resp ?: return@TokenResponseCallback context.loginFailed(null, ex)
        updateAfterTokenResponse(resp, ex)
        val accessTokenParser = OAuthAccessTokenParser()
        val updatedAuth = accessTokenParser.parse(authState, mCurrentAuthState)

        config.updateWithAuthState(context, updatedAuth)
        context.loginSucceeded(null, updatedAuth)
    }

    @AnyThread
    @Synchronized
    fun updateAfterTokenResponse(
        response: TokenResponse?,
        ex: AuthorizationException?
    ) {
        mCurrentAuthState.update(response, ex)
        writeState(mCurrentAuthState)
    }

    @AnyThread
    @Synchronized
    private fun readState(): AuthState {
        val currentState = mPrefs.getString(KEY_STATE, null) ?: return AuthState()

        return try {
            AuthState.jsonDeserialize(currentState)
        } catch (ex: JSONException) {
            logger.warn("Failed to deserialize stored auth state - discarding", ex)
            writeState(null)
            AuthState()
        }

    }

    @AnyThread
    @Synchronized
    private fun writeState(state: AuthState?) {
        mPrefs.edit {
            if (state != null) {
                putString(KEY_STATE, state.jsonSerializeString())
            } else {
                remove(KEY_STATE)
            }
        }
    }

    fun stop() {
        oAuthService.dispose()
    }

    companion object {
        private const val OAUTH_INTENT_HANDLER_REQUEST_CODE = 8422341

        private val logger = LoggerFactory.getLogger(OAuth2StateManager::class.java)

        private const val STORE_NAME = "AuthState"
        private const val KEY_STATE = "state"
        private const val APP_REDIRECT_URI = "org.radarbase.prmt://login"
        private const val DEFAULT_OAUTH_SCOPES = "SUBJECT.READ SUBJECT.UPDATE MEASUREMENT.CREATE offline_access"
        private const val DEFAUL_APP_RESOURCES = "res_gateway res_ManagementPortal"
    }
}
