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
import org.radarbase.android.RadarConfiguration.Companion.BASE_URL_KEY
import org.radarbase.android.auth.LoginManager.Companion.AUTH_TYPE_UNKNOWN
import org.radarbase.android.auth.portal.ManagementPortalClient.Companion.SOURCE_IDS_PROPERTY
import org.radarbase.android.util.buildJsonArray
import org.radarbase.android.util.equalTo
import org.slf4j.LoggerFactory
import java.util.*

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
    val isValid: Boolean = builder.isValid
    val lastUpdate: Long = builder.lastUpdate
    val attributes: Map<String, String> = HashMap(builder.attributes)
    val headers: List<Pair<String, String>> = ArrayList(builder.headers)
    val sourceMetadata: List<SourceMetadata> = ArrayList(builder.sourceMetadata)
    val sourceTypes: List<SourceType> = ArrayList(builder.sourceTypes)
    val okHttpHeaders: Headers = Headers.Builder().apply {
        headers.forEach { (k, v) -> add(k, v) }
    }.build()
    val baseUrl: String? = attributes[BASE_URL_KEY]?.trimEndSlash()

    constructor() : this(Builder())

    constructor(initializer: Builder.() -> Unit) : this(Builder().also(initializer))

    fun getAttribute(key: String) = attributes[key]

    fun serializableAttributeList() = buildJsonArray {
        attributes.forEach { (k, v) ->
            put(k)
            put(v)
        }
    }.toString()

    fun serializableHeaderList() = buildJsonArray {
        headers.forEach { (k, v) ->
            put(k)
            put(v)
        }
    }.toString()

    fun isAuthorizedForSource(sourceId: String?): Boolean {
        return !this.needsRegisteredSources
                || (sourceId != null && attributes[SOURCE_IDS_PROPERTY]?.let { sourceId in it } == true)
    }

    fun reset(): AppAuthState {
        return Builder().build()
    }

    fun alter(changes: Builder.() -> Unit): AppAuthState {
        return Builder().also {
            it.projectId = projectId
            it.userId = userId
            it.token = token
            it.tokenType = tokenType
            it.authenticationSource = authenticationSource

            it.attributes += attributes
            it.sourceMetadata += sourceMetadata
            it.sourceTypes += sourceTypes
            it.headers += headers
            it.changes()
        }.build()
    }

    class Builder {
        val lastUpdate = SystemClock.elapsedRealtime()

        val headers: MutableCollection<Pair<String, String>> = mutableListOf()
        val sourceMetadata: MutableCollection<SourceMetadata> = mutableListOf()
        val attributes: MutableMap<String, String> = mutableMapOf()

        var needsRegisteredSources = true

        var projectId: String? = null
        var userId: String? = null
        var token: String? = null
        var authenticationSource: String? = null
        var tokenType = AUTH_TYPE_UNKNOWN
        var isValid: Boolean = false
        val sourceTypes: MutableCollection<SourceType> = mutableListOf()

        fun parseAttributes(jsonString: String?) {
            jsonString ?: return
            try {
                attributes += JSONArray(jsonString).toStringPairs().toMap()
            } catch (e: JSONException) {
                logger.warn("Cannot deserialize AppAuthState attributes: {}", e.toString())
            }
        }

        fun invalidate() {
            isValid = false
        }

        fun setHeader(name: String, value: String) {
            headers.removeAll { (k, _) -> k == name }
            addHeader(name, value)
        }

        fun addHeader(name: String, value: String) {
            headers += name to value
        }

        fun parseHeaders(jsonString: String?) {
            jsonString ?: return
            try {
                this.headers += JSONArray(jsonString).toStringPairs()
            } catch (e: JSONException) {
                logger.warn("Cannot deserialize AppAuthState attributes: {}", e.toString())
            }
        }

        @Throws(JSONException::class)
        fun parseSourceTypes(sourceJson: Collection<String>?) {
            sourceJson ?: return
            sourceTypes += sourceJson.map { SourceType(it) }
        }

        @Throws(JSONException::class)
        fun parseSourceMetadata(sourceJson: Collection<String>?) {
            sourceJson ?: return
            sourceMetadata += sourceJson.map { SourceMetadata(it) }
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
                attributes=$attributes,
                sourceTypes=$sourceTypes,
                sourceMetadata=$sourceMetadata,
                parseHeaders=$headers,
            }
        """.trimIndent()
    }

    override fun equals(other: Any?) = equalTo(
        other,
        AppAuthState::projectId,
        AppAuthState::userId,
        AppAuthState::token,
        AppAuthState::tokenType,
        AppAuthState::authenticationSource,
        AppAuthState::needsRegisteredSources,
        AppAuthState::attributes,
        AppAuthState::headers,
        AppAuthState::sourceMetadata,
        AppAuthState::sourceTypes,
    )

    override fun hashCode(): Int = Objects.hash(projectId, userId, token)

    companion object {
        private val logger = LoggerFactory.getLogger(AppAuthState::class.java)

        @Throws(JSONException::class)
        private fun JSONArray.toStringPairs(): List<Pair<String, String>> = buildList(length() / 2) {
            for (i in 0 until length() step 2) {
                add(Pair(getString(i), getString(i + 1)))
            }
        }

        /**
         * Strips all slashes from the end of a URL.
         * @receiver string to strip
         * @return stripped URL or null if that would result in an empty or null string.
         */
        private fun String.trimEndSlash(): String? {
            val result = trimEnd('/')
            if (result.isEmpty()) {
                logger.warn("Base URL '{}' should be a valid URL.", this)
                return null
            }
            return result
        }
    }
}
