package org.radarbase.android.auth

import org.json.JSONException
import org.json.JSONObject
import org.radarbase.android.util.buildJson
import org.radarbase.android.util.equalTo
import org.slf4j.LoggerFactory
import java.util.*

class SourceType(
    val id: Int,
    val producer: String,
    val model: String,
    val catalogVersion: String,
    val hasDynamicRegistration: Boolean,
) {

    @Throws(JSONException::class)
    constructor(jsonString: String) : this(JSONObject(jsonString)) {
        logger.debug("Creating source type from {}", jsonString)
    }

    @Throws(JSONException::class)
    constructor(json: JSONObject) : this(
        id = json.getInt("sourceTypeId"),
        producer = json.getString("sourceTypeProducer"),
        model = json.getString("sourceTypeModel"),
        catalogVersion = json.getString("sourceTypeCatalogVersion"),
        hasDynamicRegistration = json.optBoolean("dynamicRegistration", false),
    )

    override fun equals(other: Any?): Boolean = equalTo(
        other,
        SourceType::id,
        SourceType::producer,
        SourceType::model,
        SourceType::catalogVersion
    )

    override fun hashCode(): Int = Objects.hash(id)

    override fun toString(): String = "SourceType{" +
            "id='$id', " +
            "producer='$producer', " +
            "model='$model', " +
            "catalogVersion='$catalogVersion', " +
            "dynamicRegistration=$hasDynamicRegistration}"

    fun toJson(): JSONObject = try {
        buildJson {
            put("sourceTypeId", id)
            put("sourceTypeProducer", producer)
            put("sourceTypeModel", model)
            put("sourceTypeCatalogVersion", catalogVersion)
            put("dynamicRegistration", hasDynamicRegistration)
        }
    } catch (ex: JSONException) {
        throw IllegalStateException("Cannot serialize existing SourceMetadata")
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SourceType::class.java)
    }
}
