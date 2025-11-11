package org.radarbase.android.auth.sep

import org.json.JSONObject
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthStringParser
import org.radarbase.android.auth.Jwt
import org.radarbase.android.auth.LoginManager.Companion.AUTH_TYPE_BEARER
import org.radarbase.android.auth.SourceMetadata.Companion.optNonEmptyString
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.SOURCE_IDS_PROPERTY
import org.radarbase.android.auth.sep.SEPClient.Companion.SEP_REFRESH_TOKEN_PROPERTY
import org.radarbase.android.auth.sep.SEPLoginManager.Companion.SOURCE_TYPE_SEP
import java.io.IOException
import java.util.concurrent.TimeUnit

class SepAccessTokenParser(private val state: AppAuthState) : AuthStringParser{

    override fun parse(value: String): AppAuthState {
        val responseJson = JSONObject(value)
        val accessToken = responseJson.getString("access_token")
        val jwt: Jwt = Jwt.parse(accessToken)
        val jwtBody = jwt.body
        try {
            val refreshToken = responseJson.optNonEmptyString("refresh_token")
                ?: state.getAttribute(SEP_REFRESH_TOKEN_PROPERTY)?.takeIf {
                    it.isNotEmpty()
                } ?: throw IOException("Missing refresh token")

            return state.alter {
                attributes[SEP_REFRESH_TOKEN_PROPERTY] = refreshToken
                attributes[SOURCE_IDS_PROPERTY] = jwtBody.optJSONArray("sources")?.join(",") ?: ""
                setHeader("Authorization", "Bearer $accessToken")
                token = accessToken
                tokenType = AUTH_TYPE_BEARER
                userId = jwtBody.getString("sub")
                expiration = TimeUnit.SECONDS.toMillis(jwtBody.optLong("exp", 3600L))
                needsRegisteredSources = true
                authenticationSource = SOURCE_TYPE_SEP
            }
        } catch (ex: Exception) {
            throw IOException("Failed to parse json string from sep access token parser", ex)
        }

    }
}