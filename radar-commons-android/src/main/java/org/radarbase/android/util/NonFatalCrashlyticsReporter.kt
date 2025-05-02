package org.radarbase.android.util

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import org.radarbase.android.util.exceptions.MismatchedProjectIdException
import org.radarbase.android.util.exceptions.MismatchedSourceIdException
import org.radarbase.android.util.exceptions.MismatchedUserIdException
import org.slf4j.LoggerFactory

/**
 * Reports non-fatal mismatches (source, project, or user IDs) to Firebase Crashlytics.
 *
 * This class centralizes reporting logic for mismatched IDs between payloads and
 * auth tokens
 */
object NonFatalCrashlyticsReporter {
    private val logger = LoggerFactory.getLogger(NonFatalCrashlyticsReporter::class.java)

    /**
     * Report a mismatch between the payload's source ID and the token's source ID.
     *
     * @param projectId The project identifier.
     * @param keySourceId The source ID found in the record key.
     * @param userId The user ID associated with the record key.
     * @param topic The Kafka topic used for which the payload is published.
     * @param pluginName The name of the plugin producing the payload.
     * @param tokenSourceId The source ID extracted from the access token.
     */
    fun reportMismatchedSourceId(
        projectId: String?,
        keySourceId: String?,
        userId: String?,
        topic: String,
        pluginName: String?,
        tokenSourceId: String?
    ) {
        logger.warn("Reporting MismatchedSourceId to Crashlytics")
        crashlyticsApplier {
            setCustomKey("error_type", "source_id_mismatch")
            setCustomKey("project_id", projectId.orEmpty())
            setCustomKey("plugin_name", pluginName.orEmpty())
            setCustomKey("topic", topic)
            setCustomKey("payload_source_id", keySourceId.orEmpty())
            setCustomKey("token_source_id", tokenSourceId.orEmpty())
            setUserId(userId.orEmpty())

            log("Detected sourceId mismatch for plugin=$pluginName, topic=$topic")
            recordException(
                MismatchedSourceIdException(
                    "Payload sourceId=$keySourceId ≠ token sourceId=$tokenSourceId"
                )
            )
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
        logger.warn("Reporting MismatchedProjectId to Crashlytics")
        crashlyticsApplier {
            setCustomKey("error_type", "project_id_mismatch")
            setCustomKey("key_project_id", keyProjectId.orEmpty())
            setCustomKey("token_project_id", tokenProjectId.orEmpty())
            setCustomKey("plugin_name", pluginName.orEmpty())
            setCustomKey("topic", topic)
            setUserId(userId.orEmpty())

            log("Detected projectId mismatch when sending data for plugin=$pluginName, topic=$topic")
            recordException(
                MismatchedProjectIdException(
                    "Payload projectId=$keyProjectId ≠ token projectId=$tokenProjectId"
                )
            )
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
        topic: String
    ) {
        logger.warn("Reporting MismatchedUserId to Crashlytics")
        crashlyticsApplier {
            setCustomKey("error_type", "user_id_mismatch")
            setCustomKey("key_user_id", keyUserId.orEmpty())
            setCustomKey("token_user_id", tokenUserId.orEmpty())
            setUserId(tokenUserId.orEmpty())

            log("Detected userId mismatch when sending data for plugin=$pluginName, topic=$topic")
            recordException(
                MismatchedUserIdException(
                    "Payload userId=$keyUserId ≠ token userId=$tokenUserId"
                )
            )
        }
    }

    private fun crashlyticsApplier(block: FirebaseCrashlytics.() -> Unit) {
        Firebase.crashlytics.apply(block)
    }

}
