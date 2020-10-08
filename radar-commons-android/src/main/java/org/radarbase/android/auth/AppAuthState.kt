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
    val projectId: String?
    val userId: String?
    val token: String?
    val tokenType: Int
    val authenticationSource: String?
    val needsRegisteredSources: Boolean
    val expiration: Long
    val lastUpdate: Long
    val attributes: Map<String, String>
    val headers: List<Map.Entry<String, String>>
    val sourceMetadata: List<SourceMetadata>
    val sourceTypes: List<SourceType>
    val isPrivacyPolicyAccepted: Boolean
    val okHttpHeaders: Headers
    val baseUrl: String?

    val isValid: Boolean
        get() = isPrivacyPolicyAccepted && expiration > System.currentTimeMillis()

    val isInvalidated: Boolean
        get() = expiration == 0L

    constructor() : this(Builder())

    constructor(initializer: Builder.() -> Unit) : this(Builder().also(initializer))

    init {
        this.projectId = builder.projectId
        this.userId = builder.userId
        this.token = builder.token
        this.tokenType = builder.tokenType
        this.expiration = builder.expiration
        this.attributes = HashMap(builder.attributes)
        this.sourceTypes = ArrayList(builder.sourceTypes)
        this.sourceMetadata = ArrayList(builder.sourceMetadata)
        this.headers = ArrayList(builder.headers)
        this.lastUpdate = builder.lastUpdate
        this.isPrivacyPolicyAccepted = builder.isPrivacyPolicyAccepted
        this.authenticationSource = builder.authenticationSource
        this.needsRegisteredSources = builder.needsRegisteredSources
        this.okHttpHeaders = Headers.Builder().apply {
            for (header in headers) {
                add(header.key, header.value)
            }
        }.build()
        this.baseUrl = attributes[AuthService.BASE_URL_PROPERTY]?.stripEndSlashes()
    }

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
        return ("AppAuthState{"
                + "authenticationSource='" + authenticationSource + '\''.toString()
                + ", \nprojectId='" + projectId + '\''.toString() +
                ", \nuserId='" + userId + '\''.toString() +
                ", \ntoken='" + token + '\''.toString() +
                ", \ntokenType=" + tokenType +
                ", \nexpiration=" + expiration +
                ", \nlastUpdate=" + lastUpdate +
                ", \nattributes=" + attributes +
                ", \nsourceTypes=" + sourceTypes +
                ", \nsourceMetadata=" + sourceMetadata +
                ", \nparseHeaders=" + headers +
                ", \nisPrivacyPolicyAccepted=" + isPrivacyPolicyAccepted +
                "\n")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppAuthState

        if (projectId != other.projectId) return false
        if (userId != other.userId) return false
        if (token != other.token) return false
        if (tokenType != other.tokenType) return false
        if (authenticationSource != other.authenticationSource) return false
        if (needsRegisteredSources != other.needsRegisteredSources) return false
        if (expiration != other.expiration) return false
        if (attributes != other.attributes) return false
        if (headers != other.headers) return false
        if (sourceMetadata != other.sourceMetadata) return false
        if (sourceTypes != other.sourceTypes) return false
        if (isPrivacyPolicyAccepted != other.isPrivacyPolicyAccepted) return false

        return true
    }

    override fun hashCode(): Int {
        var result = projectId?.hashCode() ?: 0
        result = 31 * result + (userId?.hashCode() ?: 0)
        result = 31 * result + (token?.hashCode() ?: 0)
        return result
    }

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
