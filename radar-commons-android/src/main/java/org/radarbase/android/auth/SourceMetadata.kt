package org.radarbase.android.auth

import org.json.JSONException
import org.json.JSONObject
import org.radarbase.android.RadarService.Companion.sanitizeIds
import org.radarbase.android.util.takeTrimmedIfNotEmpty
import org.radarbase.util.Strings
import org.slf4j.LoggerFactory
import java.util.*
import java.util.regex.Pattern

class SourceMetadata {
    var type: SourceType? = null
    var sourceId: String? = null
    var sourceName: String? = null
    var expectedSourceName: String? = null
        set(value) {
            field = value?.takeTrimmedIfNotEmpty()
                    ?.takeUnless { it == "null" }
        }
    var attributes: Map<String, String> = mapOf()
        set(value) {
            field = HashMap(value)
        }

    constructor()

    constructor(type: SourceType) {
        this.type = type
    }

    @Throws(JSONException::class)
    constructor(jsonString: String) {
        val json = JSONObject(jsonString)

        this.type = try {
            SourceType(json)
        } catch (ex: JSONException) {
            null
        }
        this.sourceId = json.optNonEmptyString("sourceId")
        this.sourceName = json.optNonEmptyString("sourceName")
        this.expectedSourceName = json.optNonEmptyString("expectedSourceName")

        val attr = HashMap<String, String>()
        val attributesJson = json.optJSONObject("attributes")
        if (attributesJson != null) {
            val it = attributesJson.keys()
            while (it.hasNext()) {
                val key = it.next()
                attr[key] = attributesJson.getString(key)
            }
        }
        this.attributes = attr
    }

    fun deduplicateType(types: MutableCollection<SourceType>) {
        type?.let { t ->
            val storedType = types.find { it.id == t.id }
            if (storedType == null) {
                types += t
            } else {
                type = storedType
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null || javaClass != other.javaClass) {
            return false
        }
        val appSource = other as SourceMetadata
        return (type == appSource.type
                && sourceId == appSource.sourceId
                && sourceName == appSource.sourceName
                && expectedSourceName == appSource.expectedSourceName
                && attributes == appSource.attributes)
    }

    override fun hashCode(): Int = Objects.hash(type, sourceId)

    override fun toString(): String = "SourceMetadata{" +
        "type=$type, " +
        "sourceId='$sourceId', " +
        "sourceName='$sourceName', " +
        "expectedSourceName='$expectedSourceName', " +
        "attributes=$attributes'}"

    fun toJsonString(): String {
        return try {
            (type?.toJson() ?: JSONObject()).apply {
                put("sourceId", sourceId)
                put("sourceName", sourceName)
                put("expectedSourceName", expectedSourceName)
                put("attributes", attributes.toJson())
            }.toString()
        } catch (ex: JSONException) {
            throw IllegalStateException("Cannot serialize existing SourceMetadata")
        }
    }

    fun matches(other: SourceMetadata): Boolean {
        val type = type ?: return false
        val otherType = other.type ?: return false

        return sourceId == other.sourceId
                || type == otherType
                || (type.producer.equals(otherType.producer, ignoreCase = true)
                    && type.model.equals(otherType.model, ignoreCase = true))
    }

    fun matches(vararg names: String?): Boolean {
        val actualNames = names.filterNotNull()
        if (actualNames.isEmpty()) {
            return false
        }
        val expectedSourceName = expectedSourceName ?: return true
        val hasMatch = Strings.containsPatterns(expectedSourceName
                .split(expectedNameSplit)
                .sanitizeIds())
                .any { pattern -> actualNames.any { pattern.matches(it) } }

        return if (hasMatch) {
            true
        } else {
            logger.warn("Source names {} were not matched by {}", actualNames, expectedSourceName)
            false
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SourceMetadata::class.java)
        private val expectedNameSplit: Regex = ",".toRegex()
        private fun Pattern.matches(string: String): Boolean = matcher(string).find()
        internal fun JSONObject.optNonEmptyString(key: String): String? = if (isNull(key)) null else optString(key).takeTrimmedIfNotEmpty().takeIf { it != "null" }
        internal fun Map<String, Any>.toJson(): JSONObject = JSONObject().also { obj ->
            forEach { (k, v) -> obj.put(k, v) }
        }
    }
}
