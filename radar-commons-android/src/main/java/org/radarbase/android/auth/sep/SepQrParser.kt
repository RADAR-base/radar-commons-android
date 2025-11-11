package org.radarbase.android.auth.sep

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService.Companion.BASE_URL_PROPERTY
import org.radarbase.android.auth.AuthStringParser
import org.radarbase.android.auth.sep.SEPClient.Companion.SEP_REFRESH_TOKEN_PROPERTY
import org.radarbase.android.auth.sep.SEPLoginManager.Companion.SOURCE_TYPE_SEP
import java.io.IOException

class SepQrParser(private val state: AppAuthState, private val referrer: String) : AuthStringParser {

    override fun parse(value: String): AppAuthState {
        try {
            val json = JSONObject(value)
            val serverUrl = referrer.toHttpUrlOrNull() ?: throw IOException("Referrer is null, can't get base url")

            val defaultPort = when (serverUrl.scheme.lowercase()) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }

            val baseUrl = buildString {
                append(serverUrl.scheme)
                append("://")
                append(serverUrl.host)
                val port = serverUrl.port
                if (port != -1 && port != defaultPort) {
                    append(":")
                    append(port)
                }
            }

            return state.alter {
                attributes[SEP_REFRESH_TOKEN_PROPERTY] = json.getString("refresh_token")
                attributes[BASE_URL_PROPERTY] = baseUrl
                needsRegisteredSources = true
                authenticationSource = SOURCE_TYPE_SEP
            }
        } catch (ex: JSONException) {
            throw IOException("Failed to parse json string $value", ex)
        }
    }


}