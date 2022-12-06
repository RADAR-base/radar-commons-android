package org.radarbase.android.auth

import android.content.Context
import android.util.Base64
import org.json.JSONException
import org.radarbase.android.auth.portal.ManagementPortalClient
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.Serializable
import java.util.*

class SharedPreferencesAuthSerialization(context: Context): AuthSerialization {
    private val prefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)

    override fun load(): AppAuthState? {
        val builder = AppAuthState.Builder()

        try {
            LOGIN_HEADERS.readSerializable()
                ?.let {
                    @Suppress("UNCHECKED_CAST")
                    it as ArrayList<Map.Entry<String, String>>?
                }
                ?.also { builder.headers += it }
        } catch (ex: Exception) {
            logger.warn("Cannot read AppAuthState parseHeaders", ex)
        }

        try {
            prefs.getStringSet(LOGIN_APP_SOURCES_LIST, null)
                ?.also { builder.parseSourceMetadata(it) }
        } catch (ex: JSONException) {
            logger.warn("Cannot parse source metadata parseHeaders", ex)
        }
        try {
            prefs.getStringSet(LOGIN_SOURCE_TYPES, null)
                ?.also { builder.parseSourceTypes(it) }
        } catch (ex: JSONException) {
            logger.warn("Cannot parse source types parseHeaders", ex)
        }

        return builder.apply {
            projectId = prefs.getString(LOGIN_PROJECT_ID, null)
            userId = prefs.getString(LOGIN_USER_ID, null) ?: return null
            token = prefs.getString(LOGIN_TOKEN, null)
            tokenType = prefs.getInt(LOGIN_TOKEN_TYPE, 0)
            expiration = prefs.getLong(LOGIN_EXPIRATION, 0L)
            parseAttributes(prefs.getString(LOGIN_ATTRIBUTES, null))
            parseHeaders(prefs.getString(LOGIN_HEADERS_LIST, null))
            isPrivacyPolicyAccepted = prefs.getBoolean(LOGIN_PRIVACY_POLICY_ACCEPTED, false)
            needsRegisteredSources = prefs.getBoolean(LOGIN_NEEDS_REGISTERD_SOURCES, true)
        }.build()
    }

    override fun store(state: AppAuthState) {
        prefs.edit().apply {
            putString(LOGIN_PROJECT_ID, state.projectId)
            putString(LOGIN_USER_ID, state.userId)
            putString(LOGIN_TOKEN, state.token)
            putString(LOGIN_HEADERS_LIST, state.serializableHeaderList())
            putString(LOGIN_ATTRIBUTES, state.serializableAttributeList())
            putInt(LOGIN_TOKEN_TYPE, state.tokenType)
            putLong(LOGIN_EXPIRATION, state.expiration)
            putBoolean(LOGIN_PRIVACY_POLICY_ACCEPTED, state.isPrivacyPolicyAccepted)
            putString(LOGIN_AUTHENTICATION_SOURCE, state.authenticationSource)
            putBoolean(LOGIN_NEEDS_REGISTERD_SOURCES, state.needsRegisteredSources)
            remove(LOGIN_PROPERTIES)
            remove(LOGIN_HEADERS)
            putStringSet(LOGIN_APP_SOURCES_LIST, HashSet<String>().apply {
                addAll(state.sourceMetadata.map(SourceMetadata::toJsonString))
            })
            putStringSet(LOGIN_SOURCE_TYPES, HashSet<String>().apply {
                addAll(state.sourceTypes.map(SourceType::toJsonString))
            })
            remove(ManagementPortalClient.SOURCES_PROPERTY)
        }.apply()
    }

    override fun remove() {
        prefs.edit().apply {
            remove(LOGIN_PROJECT_ID)
            remove(LOGIN_USER_ID)
            remove(LOGIN_TOKEN)
            remove(LOGIN_HEADERS_LIST)
            remove(LOGIN_ATTRIBUTES)
            remove(LOGIN_TOKEN_TYPE)
            remove(LOGIN_EXPIRATION)
            remove(LOGIN_PRIVACY_POLICY_ACCEPTED)
            remove(LOGIN_AUTHENTICATION_SOURCE)
            remove(LOGIN_NEEDS_REGISTERD_SOURCES)
            remove(LOGIN_PROPERTIES)
            remove(LOGIN_HEADERS)
            remove(LOGIN_APP_SOURCES_LIST)
            remove(LOGIN_SOURCE_TYPES)
            remove(ManagementPortalClient.SOURCES_PROPERTY)
        }.apply()
    }

    private fun String.readSerializable(): Any? {
        val propString = prefs.getString(this, null)
        if (propString != null) {
            val propBytes = Base64.decode(propString, Base64.NO_WRAP)
            try {
                ByteArrayInputStream(propBytes).use { bi ->
                    ObjectInputStream(bi).use { it.readObject() }
                }
            } catch (ex: IOException) {
                logger.warn("Failed to deserialize object {} from preferences", this, ex)
            } catch (ex: ClassNotFoundException) {
                logger.warn("Failed to deserialize object {} from preferences", this, ex)
            }
        }
        return null
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SharedPreferencesAuthSerialization::class.java)

        private const val LOGIN_PROJECT_ID = "org.radarcns.android.auth.AppAuthState.projectId"
        private const val LOGIN_USER_ID = "org.radarcns.android.auth.AppAuthState.userId"
        private const val LOGIN_TOKEN = "org.radarcns.android.auth.AppAuthState.token"
        private const val LOGIN_TOKEN_TYPE = "org.radarcns.android.auth.AppAuthState.tokenType"
        private const val LOGIN_EXPIRATION = "org.radarcns.android.auth.AppAuthState.expiration"
        internal const val AUTH_PREFS = "org.radarcns.auth"
        private const val LOGIN_PROPERTIES = "org.radarcns.android.auth.AppAuthState.properties"
        private const val LOGIN_ATTRIBUTES = "org.radarcns.android.auth.AppAuthState.attributes"
        private const val LOGIN_HEADERS = "org.radarcns.android.auth.AppAuthState.parseHeaders"
        private const val LOGIN_HEADERS_LIST = "org.radarcns.android.auth.AppAuthState.headerList"
        private const val LOGIN_APP_SOURCES_LIST = "org.radarcns.android.auth.AppAuthState.appSourcesList"
        private const val LOGIN_PRIVACY_POLICY_ACCEPTED = "org.radarcns.android.auth.AppAuthState.isPrivacyPolicyAccepted"
        private const val LOGIN_AUTHENTICATION_SOURCE = "org.radarcns.android.auth.AppAuthState.authenticationSource"
        private const val LOGIN_NEEDS_REGISTERD_SOURCES = "org.radarcns.android.auth.AppAuthState.needsRegisteredSources"
        private const val LOGIN_SOURCE_TYPES = "org.radarcns.android.auth.AppAuthState.sourceTypes"
    }
}
