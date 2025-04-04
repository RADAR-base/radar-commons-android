package org.radarbase.android.gateway

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.radarbase.android.RadarApplication
import org.radarbase.android.data.RestConfiguration
import org.radarbase.android.data.TableDataHandler
import org.radarbase.producer.io.timeout
import org.radarcns.kafka.ObservationKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration.Companion.seconds

class GatewayFileUploadClient {
    private var httpClient: HttpClient? = null
    private var configHandler: RestConfiguration? = null
    private val mutex = Mutex()

    private fun gatewayConfigHandlerByContext(context: Context): RestConfiguration? =
        ((context.applicationContext as RadarApplication).radarServiceImpl.dataHandler as? TableDataHandler)?.config?.restConfig

    suspend fun init(context: Context) {
        mutex.withLock {
            configHandler = gatewayConfigHandlerByContext(context)

            httpClient = HttpClient(CIO) {
                timeout(15.seconds)
                defaultRequest {
                    configHandler?.let {
                        it.kafkaConfig?.urlString?.let(::url)
                        it.headers.apply { }
                    }
                }
            }
        }
    }

    @Throws(IllegalStateException::class)
    suspend fun uploadFile(
        context: Context,
        key: ObservationKey,
        topicName: String,
        fileToUpload: File
    ): Boolean {
        mutex.withLock {
            val client = httpClient
            check(client != null && configHandler != null) { "Gateway Client or Config Handler not configured yet" }
            gatewayConfigHandlerByContext(context).let { gch ->
                if (configHandler != gch) {
                    configHandler = gch
                }
            }
            val cHandler: Headers = configHandler?.headers ?: headersOf()

            client.submitFormWithBinaryData(
                url = "${key.projectId}/${key.userId}/${topicName}",
                formData = formData {
                    append(
                        "file",
                        fileToUpload.readBytes(),
                        Headers.build {
                            append(
                                HttpHeaders.ContentDisposition,
                                "filename=\"${fileToUpload.name}\"",
                            )
                            append(
                                HttpHeaders.ContentType,
                                ContentType.Application.OctetStream.toString()
                            )
                        }
                    )
                }
            ) {
                headers {
                    appendAll(cHandler)
                }
            }.also { response: HttpResponse ->
                if (response.status == HttpStatusCode.Created) {
                    response.headers[HttpHeaders.Location]?.substringAfter("/radar-gateway/")
                        .also { uploadedFileLocation ->
                            logger.info(
                                "Successfully uploaded the audio file to gateway at path: {}",
                                uploadedFileLocation
                            )
                        }
                    return true
                } else {
                    logger.error(
                        "Failed to upload file to gateway for topic {}. " +
                                "Response(status = ${response.status}, body = ${response.body<String>()})",
                        topicName
                    )
                    return false
                }
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            httpClient?.also {
                it.close()
                httpClient = null
            }
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(GatewayFileUploadClient::class.java)
    }
}