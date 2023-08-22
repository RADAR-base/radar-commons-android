package org.radarbase.android.data

import io.ktor.http.*
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.ServerConfigUtil.toServerConfig
import org.radarbase.config.ServerConfig
import org.radarbase.kotlin.coroutines.CacheConfig
import org.radarbase.producer.io.timeout
import org.radarbase.producer.io.unsafeSsl
import org.radarbase.producer.schema.SchemaRetriever
import org.radarbase.producer.schema.SchemaRetriever.Companion.schemaRetriever
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

data class RestConfiguration(
    /** Request headers. */
    var headers: Headers = headersOf(),
    /** Kafka server configuration. If null, no rest sender will be configured. */
    var kafkaConfig: ServerConfig? = null,
    /** Schema registry retriever. */
    var schemaRetriever: SchemaRetriever? = null,
    /** Connection timeout in seconds. */
    var connectionTimeout: Long = 10L,
    /** Whether to try to use GZIP compression in requests. */
    var useCompression: Boolean = false,
    /** Whether to try to use binary encoding in request. */
    var hasBinaryContent: Boolean = false,
) {
    fun configure(config: SingleRadarConfiguration) {
        val unsafeConnection = config.getBoolean(RadarConfiguration.UNSAFE_KAFKA_CONNECTION, false)

        kafkaConfig = config.optString(RadarConfiguration.KAFKA_REST_PROXY_URL_KEY)
            ?.toServerConfig(unsafeConnection)

        schemaRetriever = config.optString(RadarConfiguration.SCHEMA_REGISTRY_URL_KEY) { url ->
            schemaRetriever(url) {
                schemaTimeout = CacheConfig(refreshDuration = 2.hours, retryDuration = 30.seconds)
                httpClient {
                    if (unsafeConnection) {
                        unsafeSsl()
                    }
                    timeout(10.seconds)
                }
            }
        }
        hasBinaryContent = config.getBoolean(RadarConfiguration.SEND_BINARY_CONTENT, RadarConfiguration.SEND_BINARY_CONTENT_DEFAULT)
        useCompression = config.getBoolean(RadarConfiguration.SEND_WITH_COMPRESSION, false)
        connectionTimeout = config.getLong(RadarConfiguration.SENDER_CONNECTION_TIMEOUT_KEY, connectionTimeout)
    }
}
