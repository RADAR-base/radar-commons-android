package org.radarbase.android.auth.oauth2

import net.openid.appauth.AuthState
import org.json.JSONException
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.Jwt
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.auth.oauth2.OAuth2LoginManager.Companion.OAUTH2_REFRESH_TOKEN_PROPERTY
import org.radarbase.android.auth.portal.GetSubjectParser.Companion.SOURCE_TYPE_OAUTH2
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.SOURCE_IDS_PROPERTY
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.collections.set

class OAuthAccessTokenParser {
    fun parse(
        authState: AppAuthState, oAuthState: AuthState
    ): AppAuthState {
        val accessToken =
            checkNotNull(oAuthState.accessToken) { "Missing access token after successful login" }
        val refreshToken =
            checkNotNull(oAuthState.refreshToken) { "Missing refresh token after successful login" }

        try {
            val decodedJwt = Jwt.parse(accessToken).body

            return authState.alter {
                token = accessToken.also { setHeader("Authorization", "Bearer $it") }
                tokenType = LoginManager.AUTH_TYPE_BEARER

                expiration = oAuthState.accessTokenExpirationTime ?: TimeUnit.SECONDS.toMillis(
                    decodedJwt.optLong("exp", 0L)
                )
                userId = decodedJwt.getString("sub")
                needsRegisteredSources = true
                authenticationSource = SOURCE_TYPE_OAUTH2
                attributes[OAUTH2_REFRESH_TOKEN_PROPERTY] = refreshToken
                attributes[SOURCE_IDS_PROPERTY] = decodedJwt.optJSONArray("sources")?.join(",") ?: ""
            }
        } catch (ex: JSONException) {
            throw IOException("Failed to parse json string from oauth access token parser", ex)
        }
    }
}