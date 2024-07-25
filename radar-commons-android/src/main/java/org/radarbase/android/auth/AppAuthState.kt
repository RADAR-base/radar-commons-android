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

package org.radarbase.android.auth

import android.os.SystemClock
import androidx.annotation.Keep
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONException
import org.radarbase.android.auth.LoginManager.Companion.AUTH_TYPE_UNKNOWN
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.SOURCES_PROPERTY
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.SOURCE_IDS_PROPERTY
import org.radarcns.android.auth.AppSource
import org.slf4j.LoggerFactory
import java.io.Serializable
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

/** Authentication state of the application.  */
@Keep
@Suppress("unused")
class AppAuthState private constructor(builder: Builder) {
    val projectId: String? = builder.projectId
    val userId: String? = builder.userId
    val token: String? = builder.token
    val tokenType: Int = builder.tokenType
    val authenticationSource: String? = builder.authenticationSource
    val needsRegisteredSources: Boolean = builder.needsRegisteredSources
    val expiration: Long = builder.expiration
    val lastUpdate: Long = builder.lastUpdate
    val attributes: Map<String, String> = HashMap(builder.attributes)
    val headers: List<Map.Entry<String, String>> = ArrayList(builder.headers)
    val sourceMetadata: List<SourceMetadata> = ArrayList(builder.sourceMetadata)
    val sourceTypes: List<SourceType> = ArrayList(builder.sourceTypes)
    val isPrivacyPolicyAccepted: Boolean = builder.isPrivacyPolicyAccepted
    val okHttpHeaders: Headers = Headers.Builder().apply {
        for (header in headers) {
            add(header.key, header.value)
        }
    }.build()
    val baseUrl: String? = attributes[AuthService.BASE_URL_PROPERTY]?.stripEndSlashes()

    val isValid: Boolean
        get() = isPrivacyPolicyAccepted && expiration > System.currentTimeMillis()

    val isInvalidated: Boolean
        get() = expiration == 0L

    constructor() : this(Builder())

    constructor(initializer: Builder.() -> Unit) : this(Builder().also(initializer))

    fun getAttribute(key: String) = attributes[key]

    fun serializableAttributeList() = serializedMap(attributes.entries)

    fun serializableHeaderList() = serializedMap(headers)

    fun isValidFor(time: Long, unit: TimeUnit) = isPrivacyPolicyAccepted
            && expiration - unit.toMillis(time) > System.currentTimeMillis()

    fun isAuthorizedForSource(sourceId: String?): Boolean {
        return !this.needsRegisteredSources
                || (sourceId != null && attributes[SOURCE_IDS_PROPERTY]?.let { sourceId in it } == true)
    }

    val timeSinceLastUpdate: Long
            get() = SystemClock.elapsedRealtime() - lastUpdate

    fun alter(changes: Builder.() -> Unit): AppAuthState {
        return Builder().also {
            it.projectId = projectId
            it.userId = userId
            it.token = token
            it.tokenType = tokenType
            it.expiration = expiration
            it.authenticationSource = authenticationSource
            it.isPrivacyPolicyAccepted = isPrivacyPolicyAccepted

            it.attributes += attributes
            it.sourceMetadata += sourceMetadata
            it.sourceTypes += sourceTypes
            it.headers += headers
        }.apply(changes).build()
    }

    fun reset(): AppAuthState = Builder().build()

    class Builder {
        val lastUpdate = SystemClock.elapsedRealtime()

        val headers: MutableCollection<Map.Entry<String, String>> = mutableListOf()
        val sourceMetadata: MutableCollection<SourceMetadata> = mutableListOf()
        val attributes: MutableMap<String, String> = mutableMapOf()

        var needsRegisteredSources = true

        var projectId: String? = null
        var userId: String? = null
        var token: String? = null
        var authenticationSource: String? = null
        var tokenType = AUTH_TYPE_UNKNOWN
        var expiration: Long = 0
        var isPrivacyPolicyAccepted = false
        val sourceTypes: MutableCollection<SourceType> = mutableListOf()

        @Deprecated("Use safe attributes instead of properties", replaceWith = ReplaceWith("attributes.addAll(properties)"))
        fun properties(properties: Map<String, Serializable>?): Builder {
            if (properties != null) {
                for ((key, value) in properties) {
                    @Suppress("UNCHECKED_CAST", "deprecation")
                    when {
                        key == SOURCES_PROPERTY -> appSources(value as List<AppSource>)
                        value is String -> this.attributes[key] = value
                        else -> logger.warn("Property {} no longer mapped in AppAuthState. Value discarded: {}", key, value)
                    }
                }
            }
            return this
        }

        fun parseAttributes(jsonString: String?): Builder = apply {
            jsonString?.also {
                attributes += try {
                    deserializedMap(it)
                } catch (e: JSONException) {
                    logger.warn("Cannot deserialize AppAuthState attributes: {}", e.toString())
                    emptyMap<String, String>()
                }
            }
        }

        fun invalidate(): Builder = apply { expiration = 0L }

        fun setHeader(name: String, value: String): Builder = apply {
            headers -= headers.filter { it.key == name }
            addHeader(name, value)
        }

        fun addHeader(name: String, value: String): Builder = apply {
            headers += AbstractMap.SimpleImmutableEntry(name, value)
        }

        fun parseHeaders(jsonString: String?): Builder = apply {
            jsonString?.also {
                this.headers += try {
                    deserializedEntryList(it)
                } catch (e: JSONException) {
                    logger.warn("Cannot deserialize AppAuthState attributes: {}", e.toString())
                    emptyList<Map.Entry<String, String>>()
                }
            }
        }

        @Deprecated("Use safe sourceMetadata instead of appSources", replaceWith = ReplaceWith("sourceMetadata.addAll(appSources)"))
        @Suppress("deprecation")
        private fun appSources(appSources: List<AppSource>?): Builder = apply {
            appSources?.also { sources ->
                sourceMetadata += sources.map { SourceMetadata(it) }
            }
        }

        @Throws(JSONException::class)
        fun parseSourceTypes(sourceJson: Collection<String>?): Builder = apply {
            sourceJson?.also { types ->
                sourceTypes += types.map { SourceType(it) }
            }
        }

        @Throws(JSONException::class)
        fun parseSourceMetadata(sourceJson: Collection<String>?): Builder = apply {
            sourceJson?.also { sources ->
                sourceMetadata += sources.map { SourceMetadata(it) }
            }
        }

        fun build(): AppAuthState {
            sourceMetadata.forEach { it.deduplicateType(sourceTypes) }
            return AppAuthState(this)
        }
    }

    override fun toString(): String {
        return """
            AppAuthState{
                authenticationSource='$authenticationSource',
                projectId='$projectId',
                userId='$userId',
                token='$token',
                tokenType=$tokenType,
                expiration=$expiration,
                lastUpdate=$lastUpdate,
                attributes=$attributes,
                sourceTypes=$sourceTypes,
                sourceMetadata=$sourceMetadata,
                parseHeaders=$headers,
                isPrivacyPolicyAccepted=$isPrivacyPolicyAccepted,
            }
        """.trimIndent()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppAuthState

        return projectId == other.projectId
            && userId == other.userId
            && token == other.token
            && tokenType == other.tokenType
            && authenticationSource == other.authenticationSource
            && needsRegisteredSources == other.needsRegisteredSources
            && expiration == other.expiration
            && attributes == other.attributes
            && headers == other.headers
            && sourceMetadata == other.sourceMetadata
            && sourceTypes == other.sourceTypes
            && isPrivacyPolicyAccepted == other.isPrivacyPolicyAccepted

    }

    override fun hashCode(): Int = Objects.hash(projectId, userId, token)

    companion object {
        private val logger = LoggerFactory.getLogger(AppAuthState::class.java)

        private fun serializedMap(map: Collection<Map.Entry<String, String>>): String {
            val array = JSONArray()
            for (entry in map) {
                array.put(entry.key)
                array.put(entry.value)
            }
            return array.toString()
        }

        @Throws(JSONException::class)
        private fun deserializedMap(jsonString: String): Map<String, String> {
            val array = JSONArray(jsonString)
            val map = HashMap<String, String>(array.length() * 4 / 6 + 1)
            var i = 0
            while (i < array.length()) {
                map[array.getString(i)] = array.getString(i + 1)
                i += 2
            }
            return map
        }

        @Throws(JSONException::class)
        private fun deserializedEntryList(jsonString: String): List<Map.Entry<String, String>> {
            val array = JSONArray(jsonString)
            val list = ArrayList<Map.Entry<String, String>>(array.length() / 2)
            var i = 0
            while (i < array.length()) {
                list += AbstractMap.SimpleImmutableEntry(array.getString(i), array.getString(i + 1))
                i += 2
            }
            return list
        }

        /**
         * Strips all slashes from the end of a URL.
         * @param url string to strip
         * @return stripped URL or null if that would result in an empty or null string.
         */
        private fun String.stripEndSlashes(): String? {
            var lastIndex = length - 1
            while (lastIndex >= 0 && this[lastIndex] == '/') {
                lastIndex--
            }
            if (lastIndex == -1) {
                logger.warn("Base URL '{}' should be a valid URL.", this)
                return null
            }
            return substring(0, lastIndex + 1)
        }
    }
}
