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

package org.radarcns.android.data

import com.crashlytics.android.Crashlytics
import org.apache.avro.generic.GenericData
import org.radarcns.android.kafka.KafkaDataSubmitter.Companion.SIZE_LIMIT_DEFAULT
import org.radarcns.android.util.SafeHandler
import org.radarcns.data.AvroRecordData
import org.radarcns.data.Record
import org.radarcns.data.RecordData
import org.radarcns.topic.AvroTopic
import org.radarcns.util.BackedObjectQueue
import org.radarcns.util.QueueFile
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Caches measurement on a BackedObjectQueue. Internally, all data is first cached on a local queue,
 * before being written in batches to the BackedObjectQueue, using a single-threaded
 * ExecutorService. Data is retrieved and removed from the queue in a blocking way using that same
 * ExecutorService. Sent messages are not kept, they are immediately removed.
 *
 * @param <K> measurement key type
 * @param <V> measurement value type
</V></K> */
class TapeCache<K, V>
/**
 * TapeCache to cache measurements with
 * @param topic Kafka Avro topic to write data for.
 * @throws IOException if a BackedObjectQueue cannot be created.
 */
@Throws(IOException::class)
constructor(override val file: File,
            override val topic: AvroTopic<K, V>,
            override val readTopic: AvroTopic<Any, Any>,
            private val executor: SafeHandler,
            private val inputFormat: GenericData,
            outputFormat: GenericData,
            config: DataCache.CacheConfiguration) : DataCache<K, V> {

    private val measurementsToAdd: MutableList<Record<K, V>>
    private val serializer: BackedObjectQueue.Serializer<Record<K, V>>
    private val deserializer: BackedObjectQueue.Deserializer<Record<Any, Any>>
    private var queueFile: QueueFile

    private var queue: BackedObjectQueue<Record<K, V>, Record<Any, Any>>
    private var addMeasurementFuture: SafeHandler.HandlerFuture? = null

    private val queueSize: AtomicInteger

    override var config = config
        get() = executor.compute { field }
        set(value) = executor.execute {
            if (value != field) {
                if (value.maximumSize != field.maximumSize) {
                    queueFile.maximumFileSize = value.maximumSize
                }
                field = value.copy()
            }
        }

    private val maximumSize: Int
        get() = config.maximumSize.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() }

    init {
        queueFile = try {
            QueueFile.newMapped(Objects.requireNonNull(file), maximumSize)
        } catch (ex: IOException) {
            logger.error("TapeCache $file was corrupted. Removing old cache.")
            if (file.delete()) {
                QueueFile.newMapped(file, maximumSize)
            } else {
                throw ex
            }
        }

        this.queueSize = AtomicInteger(queueFile.size())

        this.measurementsToAdd = mutableListOf()

        this.serializer = TapeAvroSerializer(topic, Objects.requireNonNull(inputFormat))
        this.deserializer = TapeAvroDeserializer(readTopic, Objects.requireNonNull(outputFormat))
        this.queue = BackedObjectQueue(queueFile, serializer, deserializer)
    }

    @Throws(IOException::class)
    override fun getUnsentRecords(limit: Int, sizeLimit: Long): RecordData<Any, Any>? {
        logger.debug("Trying to retrieve records from topic {}", topic)
        try {
            return executor.compute {
                try {
                    val records = queue.peek(limit, sizeLimit)
                    if (records.isEmpty()) {
                        return@compute null
                    }
                    val currentKey = records[0].key
                    val values = ArrayList<Any>(records.size)
                    for (record in records) {
                        if (currentKey != record.key) {
                            break
                        }
                        values.add(record.value)
                    }
                    return@compute AvroRecordData < Any, Any>(readTopic, currentKey, values)
                } catch (ex: IOException) {
                    fixCorruptQueue()
                    return@compute null
                } catch (ex: IllegalStateException) {
                    fixCorruptQueue()
                    return@compute null
                }
            }
        } catch (ex: InterruptedException) {
            logger.warn("getUnsentRecords was interrupted, returning an empty list", ex)
            Thread.currentThread().interrupt()
            return null
        } catch (ex: ExecutionException) {
            logger.warn("Failed to retrieve records for topic {}", topic, ex)
            val cause = ex.cause
            if (cause is RuntimeException) {
                throw cause
            } else {
                throw IOException("Unknown error occurred", ex)
            }
        }

    }

    @Throws(IOException::class)
    override fun getRecords(limit: Int): RecordData<Any, Any>? {
        return getUnsentRecords(limit, SIZE_LIMIT_DEFAULT)
    }

    override val numberOfRecords: Long
        get() = queueSize.get().toLong()

    @Throws(IOException::class)
    override fun remove(number: Int): Int {
        try {
            return executor.compute {
                val actualNumber = Math.min(number, queue.size())
                if (actualNumber == 0) {
                    return@compute 0
                }
                logger.debug("Removing {} records from topic {}", actualNumber, topic.name)
                queue.remove(actualNumber)
                queueSize.addAndGet(-actualNumber)
                actualNumber
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Failed to mark record as sent. May resend those at a later time.", ex)
            return 0
        } catch (ex: ExecutionException) {
            logger.warn("Failed to mark sent records for topic {}", topic, ex)
            val cause = ex.cause
            when (cause) {
                is RuntimeException -> return 0
                is IOException -> throw cause
                else -> throw IOException("Unknown error occurred", ex)
            }
        }

    }

    override fun addMeasurement(key: K?, value: V?) {
        if (!inputFormat.validate(topic.keySchema, key)
                || !inputFormat.validate(topic.valueSchema, value)) {
            throw IllegalArgumentException("Cannot send invalid record to topic " + topic
                    + " with {key: " + key + ", value: " + value + "}")
        }

        executor.execute {
            measurementsToAdd.add(Record<K, V>(key, value))

            if (addMeasurementFuture == null) {
                addMeasurementFuture = executor.delay(config.commitRate, this::doFlush)
            }
        }
    }

    @Throws(IOException::class)
    override fun close() {
        flush()
        queue.close()
    }

    override fun flush() {
        try {
            executor.await {
                addMeasurementFuture?.runNow()
            }
        } catch (e: InterruptedException) {
            logger.warn("Did not wait for adding measurements to complete.")
        } catch (ex: ExecutionException) {
            logger.warn("Failed to execute flush task", ex)
        }

    }

    private fun doFlush() {
        addMeasurementFuture = null

        if (measurementsToAdd.isEmpty()) {
            return
        }
        try {
            logger.info("Writing {} records to file in topic {}", measurementsToAdd.size, topic.name)
            queue.addAll(measurementsToAdd)
            queueSize.addAndGet(measurementsToAdd.size)
        } catch (ex: IOException) {
            logger.error("Failed to add records", ex)
            queueSize.set(queue.size())
            throw RuntimeException(ex)
        } catch (ex: IllegalStateException) {
            logger.error("Queue {} is full, not adding records", topic.name)
            queueSize.set(queue.size())
        } catch (ex: IllegalArgumentException) {
            logger.error("Failed to validate all records; adding individual records instead: {}", ex.message)
            try {
                logger.info("Writing {} records to file in topic {}", measurementsToAdd.size, topic.name)
                for (record in measurementsToAdd) {
                    try {
                        queue.add(record)
                    } catch (ex2: IllegalArgumentException) {
                        Crashlytics.logException(ex2)
                    }

                }
                queueSize.addAndGet(measurementsToAdd.size)
            } catch (illEx: IllegalStateException) {
                logger.error("Queue {} is full, not adding records", topic.name)
                queueSize.set(queue.size())
            } catch (ex2: IOException) {
                logger.error("Failed to add record", ex)
                queueSize.set(queue.size())
                throw RuntimeException(ex)
            }

        } finally {
            measurementsToAdd.clear()
        }
    }

    @Throws(IOException::class)
    private fun fixCorruptQueue() {
        logger.error("Queue {} was corrupted. Removing cache.", topic.name)
        try {
            queue.close()
        } catch (ioex: IOException) {
            logger.warn("Failed to close corrupt queue", ioex)
        }

        if (file.delete()) {
            queueFile = QueueFile.newMapped(file, maximumSize)
            queueSize.set(queueFile.size())
            queue = BackedObjectQueue(queueFile, serializer, deserializer)
        } else {
            throw IOException("Cannot create new cache.")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TapeCache::class.java)
    }
}
