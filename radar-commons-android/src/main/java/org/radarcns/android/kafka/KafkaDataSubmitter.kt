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

package org.radarcns.android.kafka

import android.os.Process.THREAD_PRIORITY_BACKGROUND
import org.apache.avro.Schema
import org.apache.avro.SchemaValidationException
import org.apache.avro.generic.IndexedRecord
import org.radarcns.android.data.DataHandler
import org.radarcns.android.data.ReadableDataCache
import org.radarcns.android.util.SafeHandler
import org.radarcns.producer.AuthenticationException
import org.radarcns.producer.KafkaSender
import org.radarcns.producer.KafkaTopicSender
import org.radarcns.topic.AvroTopic
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Separate thread to read from the database and send it to the Kafka server. It cleans the
 * database.
 *
 * It uses a set of timers to addMeasurement data and clean the databases.
 */
class KafkaDataSubmitter(private val dataHandler: DataHandler<*, *>, private val sender: KafkaSender, config: SubmitterConfiguration) : Closeable {
    private val topicSenders: MutableMap<String, KafkaTopicSender<Any, Any>>
    private val connection: KafkaConnectionChecker
    private val mHandler: SafeHandler

    var config: SubmitterConfiguration = config
        set(newValue) {
            mHandler.execute {
                if (newValue != field) {
                    validate(newValue)
                    field = newValue.copy()
                    schedule()
                }
            }
        }

    private var uploadFuture: SafeHandler.HandlerFuture? = null
    private var uploadIfNeededFuture: SafeHandler.HandlerFuture? = null
    /** Upload rate in milliseconds.  */

    init {
        topicSenders = HashMap()

        mHandler = SafeHandler("data-submitter", THREAD_PRIORITY_BACKGROUND)
        mHandler.start()

        logger.debug("Started data submission executor")

        connection = KafkaConnectionChecker(sender, mHandler, dataHandler, config.uploadRate * 5)

        mHandler.execute {
            try {
                if (this@KafkaDataSubmitter.sender.isConnected) {
                    this@KafkaDataSubmitter.dataHandler.updateServerStatus(ServerStatusListener.Status.CONNECTED)
                    connection.didConnect()
                } else {
                    this@KafkaDataSubmitter.dataHandler.updateServerStatus(ServerStatusListener.Status.DISCONNECTED)
                    connection.didDisconnect(null)
                }
            } catch (ex: AuthenticationException) {
                connection.didDisconnect(ex)
            }
        }

        mHandler.execute {
            uploadFuture = null
            uploadIfNeededFuture = null
            validate(config)
            schedule()
        }
    }

    private fun validate(config: SubmitterConfiguration) {
        if (config.userId == null) {
            throw IllegalArgumentException("User ID is mandatory to start KafkaDataSubmitter")
        }
    }

    private fun schedule() {
        val uploadRate = config.uploadRate * config.uploadRateMultiplier * 1000L
        uploadFuture?.cancel()
        uploadIfNeededFuture?.cancel()

        // Get upload frequency from system property
        uploadFuture = mHandler.repeat(uploadRate) {
            val topicsToSend = HashSet<String>(dataHandler.activeCaches.map { it.topicName })
            while (connection.isConnected && !topicSenders.isEmpty()) {
                uploadCaches(topicsToSend)
            }
        }

        uploadIfNeededFuture = mHandler.repeat(uploadRate / 5) {
            while (connection.isConnected) {
                val sendAgain = uploadCachesIfNeeded()
                if (!sendAgain) {
                    break
                }
            }
        }
    }

    /**
     * Close the submitter eventually. This does not flush any caches.
     */
    @Synchronized
    override fun close() {
        mHandler.stop {
            for ((key, value) in topicSenders) {
                try {
                    value.close()
                } catch (e: IOException) {
                    logger.warn("failed to stop topicSender for topic {}", key, e)
                }

            }
            topicSenders.clear()

            try {
                sender.close()
            } catch (e1: IOException) {
                logger.warn("failed to addMeasurement latest batches", e1)
            }
        }
    }

    /** Get a sender for a topic. Per topic, only ONE thread may use this.  */
    @Throws(IOException::class, SchemaValidationException::class)
    private fun sender(topic: AvroTopic<Any, Any>): KafkaTopicSender<Any, Any> {
         return topicSenders[topic.name]
                ?: sender.sender(topic).also { topicSenders[topic.name] = it }
    }

    /**
     * Check the connection status eventually.
     */
    fun checkConnection() {
        connection.check()
    }

    /**
     * Upload the caches if they would cause the buffer to overflow
     */
    private fun uploadCachesIfNeeded(): Boolean {
        var sendAgain = false

        try {
            val uploadingNotified = AtomicBoolean(false)

            for (entry in dataHandler.activeCaches) {
                val unsent = entry.activeDataCache.numberOfRecords
                if (unsent > config.amountLimit) {
                    val sent = uploadCache(entry.activeDataCache, uploadingNotified)
                    if (unsent - sent > config.amountLimit) {
                        sendAgain = true
                    }
                }
            }
            if (uploadingNotified.get()) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.CONNECTED)
                connection.didConnect()
            }
        } catch (ex: IOException) {
            connection.didDisconnect(ex)
            sendAgain = false
        } catch (ex: SchemaValidationException) {
            connection.didDisconnect(ex)
            sendAgain = false
        }

        return sendAgain
    }

    /**
     * Upload a limited amount of data stored in the database which is not yet sent.
     */
    private fun uploadCaches(toSend: MutableSet<String>) {
        try {
            val uploadingNotified = AtomicBoolean(false)
            dataHandler.activeCaches
                    .filterNot { toSend.contains(it.topicName) }
                    .forEach { group ->
                        val sentActive = uploadCache(group.activeDataCache, uploadingNotified)
                        val sentDeprecated = group.deprecatedCaches.map { uploadCache(it, uploadingNotified) }

                        if (sentDeprecated.any { it == 0 }) {
                            group.deleteEmptyCaches()
                        }
                        if (sentActive < config.amountLimit
                                && sentDeprecated.all { it < config.amountLimit }) {
                            toSend.remove(group.topicName)
                        }
                    }

            if (uploadingNotified.get()) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.CONNECTED)
                connection.didConnect()
            }
        } catch (ex: IOException) {
            connection.didDisconnect(ex)
        } catch (ex: SchemaValidationException) {
            connection.didDisconnect(ex)
        }
    }

    /**
     * Upload some data from a single table.
     * @return number of records sent.
     */
    @Throws(IOException::class, SchemaValidationException::class)
    private fun uploadCache(cache: ReadableDataCache, uploadingNotified: AtomicBoolean): Int {
        val data = cache.getUnsentRecords(config.amountLimit, config.sizeLimit)
                ?: return 0

        val size = data.size()
        val topic = cache.readTopic

        val keyUserId = if (topic.keySchema.type == Schema.Type.RECORD) {
            topic.keySchema.getField("userId")?.let { userIdField ->
                (data.key as IndexedRecord).get(userIdField.pos()).toString()
            }
        } else null

        if (keyUserId == null || keyUserId == config.userId!!) {
            if (uploadingNotified.compareAndSet(false, true)) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.UPLOADING)
            }

            try {
                sender(topic).run {
                    send(data)
                    flush()
                }
            } catch (ex: AuthenticationException) {
                dataHandler.updateRecordsSent(topic.name, -1)
                throw ex
            } catch (e: Exception) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.UPLOADING_FAILED)
                dataHandler.updateRecordsSent(topic.name, -1)
                throw e
            }

            dataHandler.updateRecordsSent(topic.name, size)

            logger.debug("uploaded {} {} records", size, topic.name)
        }

        cache.remove(size)

        return size
    }

    data class SubmitterConfiguration(
            var userId: String? = null,
            var amountLimit: Int = SEND_LIMIT_DEFAULT,
            var sizeLimit: Long = SIZE_LIMIT_DEFAULT,
            var uploadRate: Long = UPLOAD_RATE_DEFAULT,
            var uploadRateMultiplier: Int = 1)

    companion object {
        private val logger = LoggerFactory.getLogger(KafkaDataSubmitter::class.java)
        const val SIZE_LIMIT_DEFAULT = 5000000L  // 5 MB
        private const val SEND_LIMIT_DEFAULT = 1000
        private const val UPLOAD_RATE_DEFAULT = 10L
    }
}
