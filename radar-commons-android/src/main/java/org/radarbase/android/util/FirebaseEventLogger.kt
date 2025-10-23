package org.radarbase.android.util

import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.logEvent
import com.google.firebase.ktx.Firebase
import org.slf4j.LoggerFactory

object FirebaseEventLogger {
    private val logger = LoggerFactory.getLogger(FirebaseEventLogger::class.java)

    private val firebaseAnalytics = Firebase.analytics
    private const val EVENT_SOURCE_ID_MISMATCH = "source_id_mismatch"
    private const val EVENT_PROJECT_ID_MISMATCH = "project_id_mismatch"
    private const val EVENT_USER_ID_MISMATCH = "user_id_mismatch"

    /**
     * Report a mismatch between the payload's source ID and the token's source ID.
     *
     * @param projectId The project identifier.
     * @param keySourceId The source ID found in the record key.
     * @param userId The user ID associated with the record key.
     * @param topic The Kafka topic used for which the payload is published.
     * @param pluginName The name of the plugin producing the payload.
     * @param tokenSourceIds The source ID's matching the source type.
     */
    fun reportMismatchedSourceId(
        projectId: String?,
        keySourceId: String?,
        userId: String?,
        topic: String,
        pluginName: String?,
        tokenSourceIds: String?
    ) {
        logger.warn("Reporting MismatchedSourceId to Firebase Analytics")
        firebaseAnalytics.logEvent(EVENT_SOURCE_ID_MISMATCH) {
            param("project_id", projectId.orEmpty())
            param("plugin_name", pluginName.orEmpty())
            param("topic", topic)
            param("payload_source_id", keySourceId.orEmpty())
            param("token_source_ids", tokenSourceIds.orEmpty())
            param("userId", userId.orEmpty())

            logger.info("Detected sourceId mismatch for plugin=$pluginName, topic=$topic")
        }
    }

    /**
     * Report a mismatch between the payload's project ID and the token's project ID.
     *
     * @param keyProjectId The project ID found in the payload.
     * @param tokenProjectId The project ID extracted from the access token.
     * @param pluginName The name of the plugin producing the payload.
     * @param topic The Kafka topic used for publishing the payload.
     * @param userId The user ID associated with the payload.
     */
    fun reportMismatchedProjectId(
        keyProjectId: String?,
        tokenProjectId: String?,
        pluginName: String?,
        topic: String,
        userId: String?
    ) {
        logger.warn("Reporting MismatchedProjectId to Firebase Analytics")
        firebaseAnalytics.logEvent(EVENT_PROJECT_ID_MISMATCH) {
            param("key_project_id", keyProjectId.orEmpty())
            param("token_project_id", tokenProjectId.orEmpty())
            param("plugin_name", pluginName.orEmpty())
            param("topic", topic)
            param("user_id", userId.orEmpty())

            logger.info("Detected projectId mismatch when sending data for plugin=$pluginName, topic=$topic")
        }
    }

    /**
     * Report a mismatch between the payload's user ID and the token's user ID.
     *
     * @param keyUserId The user ID found in the payload.
     * @param tokenUserId The user ID extracted from the access token.
     * @param pluginName The name of the plugin producing the payload.
     * @param topic The Kafka topic used for publishing the payload.
     */
    fun reportMismatchedUserId(
        keyUserId: String?,
        tokenUserId: String?,
        pluginName: String?,
        topic: String,
        projectId: String?
    ) {
        logger.warn("Reporting MismatchedUserId to Firebase Analytics")
        firebaseAnalytics.logEvent(EVENT_USER_ID_MISMATCH) {
            param("key_user_id", keyUserId.orEmpty())
            param("token_user_id", tokenUserId.orEmpty())
            param("plugin_name", pluginName.orEmpty())
            param("topic", topic)
            param("project_id", projectId.orEmpty())

            logger.info("Detected userId mismatch when sending data for plugin=$pluginName, topic=$topic")
        }
    }
}
