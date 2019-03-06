package org.radarcns.android.auth.portal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.radarcns.android.auth.AppAuthState
import org.radarcns.android.auth.AuthStringParser
import org.radarcns.android.auth.SourceMetadata
import org.radarcns.android.auth.portal.ManagementPortalLoginManager.Companion.SOURCE_TYPE
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*

class GetSubjectParser(private val state: AppAuthState) : AuthStringParser {

    @Throws(IOException::class)
    override fun parse(authString: String): AppAuthState {
        try {
            val jsonObject = JSONObject(authString)
            val project = jsonObject.getJSONObject("project")
            val sources = jsonObject.getJSONArray("sources")

            val types = parseSourceTypes(project)

            return state.alter {
                sourceTypes.clear()
                sourceTypes += types
                sourceMetadata.clear()
                sourceMetadata += parseSources(types, sources)
                userId = parseUserId(jsonObject)
                projectId = parseProjectId(project)
                needsRegisteredSources = true
                authenticationSource = SOURCE_TYPE

                jsonObject.opt("attributes")?.also { attrObjects ->
                    if (attrObjects is JSONArray) {
                        if (attrObjects.length() > 0) {
                            for (i in 0 until attrObjects.length()) {
                                val attrObject = attrObjects.getJSONObject(i)
                                attributes[attrObject.getString("key")] = attrObject.getString("value")
                            }
                        }
                    } else {
                        attributes += attributesToMap(attrObjects as JSONObject)
                    }
                }
            }
        } catch (e: JSONException) {
            throw IOException(
                    "ManagementPortal did not give a valid response: $authString", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GetSubjectParser::class.java)

        @Throws(JSONException::class)
        internal fun parseSourceTypes(project: JSONObject): List<SourceType> {
            val sourceTypesArr = project.getJSONArray("sourceTypes")
            val numSources = sourceTypesArr.length()

            val sources = ArrayList<SourceType>(numSources)
            for (i in 0 until numSources) {
                sources += sourceTypesArr.getJSONObject(i).run {
                    SourceType(
                            getInt("id"),
                            getString("producer"),
                            getString("model"),
                            getString("catalogVersion"),
                            getBoolean("canRegisterDynamically"))
                }
            }
            return sources
        }

        @Throws(JSONException::class)
        internal fun parseSources(sourceTypes: List<SourceType>,
                                  sources: JSONArray): List<SourceMetadata> {

            val actualSources = ArrayList<SourceMetadata>(sources.length())

            for (i in 0 until sources.length()) {
                val sourceObj = sources.getJSONObject(i)
                val id = sourceObj.getString("sourceId")
                if (!sourceObj.optBoolean("assigned", true)) {
                    logger.debug("Skipping unassigned source {}", id)
                }
                val sourceTypeId = sourceObj.getInt("sourceTypeId")
                sourceTypes.find { it.id == sourceTypeId }?.let {
                    actualSources.add(SourceMetadata(it).apply {
                        expectedSourceName = sourceObj.optString("expectedSourceName")
                        sourceName = sourceObj.optString("sourceName")
                        sourceId = id
                        attributes = attributesToMap(sourceObj.optJSONObject("attributes"))
                    })
                } ?: logger.error("Source {} type {} not recognized", id, sourceTypeId)
            }

            logger.info("Sources from Management Portal: {}", actualSources)
            return actualSources
        }

        @Throws(JSONException::class)
        internal fun attributesToMap(attrObj: JSONObject?): Map<String, String> {
            return attrObj?.let {
                mutableMapOf<String, String>().apply {
                    val iterator = it.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        put(key, attrObj.getString(key))
                    }
                }
            } ?: mapOf()
        }

        @Throws(JSONException::class)
        internal fun parseProjectId(project: JSONObject): String {
            return project.getString("projectName")
        }

        /**
         * Parse the user ID from a subject response jsonObject.
         * @param jsonObject response JSON jsonObject of a subject call
         * @return user ID
         * @throws JSONException if the given JSON jsonObject does not contain a login property
         */
        @Throws(JSONException::class)
        internal fun parseUserId(jsonObject: JSONObject): String {
            return jsonObject.getString("login")
        }

        fun getHumanReadableUserId(state: AppAuthState): String? {
            return state.attributes.entries
                    .find { e -> e.key.equals("Human-readable-identifier", ignoreCase = true) && !e.value.isBlank() }
                    ?.let { it.value }
                    ?: state.userId
        }
    }
}
