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

package org.radarbase.android.data

import android.content.Context
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.apache.avro.specific.SpecificData
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.device.DeviceService.Companion.CACHE_RECORDS_UNSENT_NUMBER
import org.radarbase.android.device.DeviceService.Companion.CACHE_TOPIC
import org.radarbase.android.kafka.KafkaDataSubmitter
import org.radarbase.android.kafka.ServerStatusListener
import org.radarbase.android.util.BatteryStageReceiver
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.android.util.SafeHandler
import org.radarbase.android.util.send
import org.radarbase.producer.rest.RestClient
import org.radarbase.producer.rest.RestSender
import org.radarbase.topic.AvroTopic
import org.radarcns.kafka.ObservationKey
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

/**
 * Stores data in databases and sends it to the server. If kafkaConfig is null, data will only be
 * stored to disk, not uploaded.
 */
class TableDataHandler(private val context: Context) : DataHandler<ObservationKey, SpecificRecord> {

    private val tables: ConcurrentMap<String, DataCacheGroup<*, *>> = ConcurrentHashMap()
    private val statusSync = Any()
    private val batteryLevelReceiver: BatteryStageReceiver
    private val networkConnectedReceiver: NetworkConnectedReceiver
    private val specificData: SpecificData
    private val handler: SafeHandler = SafeHandler("TableDataHandler", THREAD_PRIORITY_BACKGROUND)
    private val broadcaster = LocalBroadcastManager.getInstance(context)

    private var config = DataHandler.DataHandlerConfiguration()
    private var submitterConfig = KafkaDataSubmitter.SubmitterConfiguration()
    private var restConfig = DataHandler.RestConfiguration()
    private var cacheConfig = DataCache.CacheConfiguration()

    override var status: ServerStatusListener.Status = ServerStatusListener.Status.DISCONNECTED
        get() = synchronized(statusSync) { field }
        set(value) = synchronized(statusSync) { field = value }

    private val lastNumberOfRecordsSent = TreeMap<String, Int>()
    private var submitter: KafkaDataSubmitter? = null
    private var sender: RestSender? = null

    private val isStarted: Boolean
        @Synchronized get() = submitter != null

    override val recordsSent: Map<String, Int>
        get() = synchronized(statusSync) {
            HashMap(lastNumberOfRecordsSent)
        }

    init {
        this.handler.start()
        this.handler.repeat(10_000L, this::broadcastNumberOfRecords)

        this.batteryLevelReceiver = BatteryStageReceiver(context, config.minimumBatteryLevel, config.reducedBatteryLevel) { stage ->
            when (stage) {
                BatteryStageReceiver.BatteryStage.FULL -> {
                    handler {
                        submitter {
                            uploadRateMultiplier = 1
                        }
                    }
                    start()
                }
                BatteryStageReceiver.BatteryStage.REDUCED -> {
                    logger.info("Battery level getting low, reducing data sending")
                    handler {
                        submitter {
                            uploadRateMultiplier = reducedUploadMultiplier
                        }
                    }
                    start()
                }
                BatteryStageReceiver.BatteryStage.EMPTY -> {
                    if (isStarted) {
                        logger.info("Battery level getting very low, stopping data sending")
                        stop()
                    }
                }
            }
        }
        this.networkConnectedReceiver = NetworkConnectedReceiver(context) { isConnected, hasWifiOrEthernet ->
            if (isStarted) {
                if (!isConnected || (!hasWifiOrEthernet && config.sendOnlyWithWifi)) {
                    logger.info("Network was disconnected, stopping data sending")
                    stop()
                }
            } else {
                // Just try to start: the start method will not do anything if the parameters
                // are not right.
                start()
            }
        }
        this.specificData = CacheStore.specificData

        submitter = null
        sender = null

        if (restConfig.kafkaConfig != null) {
            doEnableSubmitter()
        } else {
            updateServerStatus(ServerStatusListener.Status.DISABLED)
        }
    }

    private fun broadcastNumberOfRecords() {
        caches.forEach { cache ->
            val records = cache.numberOfRecords
            broadcaster.send(CACHE_TOPIC) {
                putExtra(CACHE_TOPIC, cache.readTopic.name)
                putExtra(CACHE_RECORDS_UNSENT_NUMBER, records)
            }
        }
    }

    /**
     * Start submitting data to the server.
     *
     * This will not do anything if there is not already a submitter running, if it is disabled,
     * if the network is not connected or if the battery is running too low.
     */
    @Synchronized
    fun start() {
        if (isStarted
                || submitterConfig.userId == null
                || status === ServerStatusListener.Status.DISABLED
                || !networkConnectedReceiver.hasConnection(config.sendOnlyWithWifi)
                || batteryLevelReceiver.stage == BatteryStageReceiver.BatteryStage.EMPTY) {
            return
        }

        updateServerStatus(ServerStatusListener.Status.CONNECTING)

        val client = RestClient.global()
                .server(restConfig.kafkaConfig!!)
                .gzipCompression(restConfig.useCompression)
                .timeout(restConfig.connectionTimeout, TimeUnit.SECONDS)
                .build()

        val sender = RestSender.Builder().apply {
            httpClient(client)
            schemaRetriever(restConfig.schemaRetriever)
            headers(restConfig.headers)
            useBinaryContent(restConfig.hasBinaryContent)
        }.build().also {
            sender = it
        }

        this.submitter = KafkaDataSubmitter(this, sender, submitterConfig)
    }

    /**
     * Pause sending any data.
     * This waits for any remaining data to be sent.
     */
    @Synchronized
    fun stop() {
        submitter?.close()
        submitter = null
        sender = null
        if (status != ServerStatusListener.Status.DISABLED) {
            updateServerStatus(ServerStatusListener.Status.READY)
        }
    }

    /** Do not submit any data, only cache it. If it is already disabled, this does nothing.  */
    @Synchronized
    fun disableSubmitter() {
        if (status !== ServerStatusListener.Status.DISABLED) {
            updateServerStatus(ServerStatusListener.Status.DISABLED)
            if (isStarted) {
                stop()
            }
            networkConnectedReceiver.unregister()
            batteryLevelReceiver.unregister()
        }
    }

    /** Start submitting data. If it is already submitting data, this does nothing.  */
    @Synchronized
    fun enableSubmitter() {
        if (status === ServerStatusListener.Status.DISABLED) {
            doEnableSubmitter()
            start()
        }
    }

    private fun doEnableSubmitter() {
        networkConnectedReceiver.register()
        batteryLevelReceiver.register()
        updateServerStatus(ServerStatusListener.Status.READY)
    }

    /**
     * Sends any remaining data and closes the tables and connections.
     * @throws IOException if the tables cannot be flushed
     */
    @Synchronized
    @Throws(IOException::class)
    fun close() {
        handler.stop { }

        if (status !== ServerStatusListener.Status.DISABLED) {
            networkConnectedReceiver.unregister()
            batteryLevelReceiver.unregister()
        }
        this.submitter?.close()
        this.submitter = null
        this.sender = null

        tables.values.forEach(DataCacheGroup<*, *>::close)
    }

    /**
     * Check the connection with the server.
     *
     * Updates will be given to any listeners registered to
     * [.setStatusListener].
     */
    @Synchronized
    fun checkConnection() {
        if (status === ServerStatusListener.Status.DISABLED) {
            return
        }
        if (!isStarted) {
            start()
        }
        if (isStarted) {
            submitter?.checkConnection()
        }
    }

    /**
     * Get the table of a given topic
     */
    override fun getCache(topic: String): DataCache<*, *> {
        return tables[topic]?.activeDataCache ?: throw NullPointerException()
    }

    override val caches: List<ReadableDataCache>
        get() {
            val caches = ArrayList<ReadableDataCache>(tables.size)
            tables.values.forEach {
                caches += it.activeDataCache
                caches += it.deprecatedCaches
            }
            return caches
        }

    @get:Synchronized
    override val activeCaches: List<DataCacheGroup<*, *>>
        get() {
            return if (submitter == null) {
                emptyList()
            } else if (networkConnectedReceiver.hasWifiOrEthernet || !config.sendOverDataHighPriority) {
                ArrayList<DataCacheGroup<*, *>>(tables.values)
            } else {
                tables.values.filter { it.topicName in config.highPriorityTopics }
            }
        }

    var statusListener: ServerStatusListener? = null
        get() = synchronized(statusSync) { field }
        set(value) = synchronized(statusSync) { field = value }

    override fun updateServerStatus(status: ServerStatusListener.Status) {
        synchronized(statusSync) {
            statusListener?.updateServerStatus(status)
            this.status = status
        }
    }

    override fun updateRecordsSent(topicName: String, numberOfRecords: Int) {
        synchronized(statusSync) {
            statusListener?.updateRecordsSent(topicName, numberOfRecords)

            // Overwrite key-value if exists. Only stores the last
            this.lastNumberOfRecordsSent[topicName] = numberOfRecords

            if (numberOfRecords < 0) {
                logger.warn("{} has FAILED uploading", topicName)
            } else {
                logger.info("{} uploaded {} records", topicName, numberOfRecords)
            }
        }
    }

    @Throws(IOException::class)
    override fun <V: SpecificRecord> registerCache(topic: AvroTopic<ObservationKey, V>): DataCache<ObservationKey, V> {
        return CacheStore.get()
                .getOrCreateCaches(context.applicationContext, topic, cacheConfig)
                .also { tables[topic.name] = it }
                .let { it.activeDataCache }
    }

    @Synchronized
    override fun handler(build: DataHandler.DataHandlerConfiguration.() -> Unit) {
        val oldConfig = config
        val newConfig = config.copy().apply(build)
        if (newConfig == oldConfig) {
            return
        }
        if (newConfig.restConfig.kafkaConfig != null
                && newConfig.restConfig.schemaRetriever != null
                && newConfig.submitterConfig.userId != null) {
            enableSubmitter()
        } else {
            disableSubmitter()
        }

        config = newConfig

        if (newConfig.cacheConfig != oldConfig.cacheConfig) {
            tables.values.forEach { it.activeDataCache.config = newConfig.cacheConfig }
        }

        if (newConfig.submitterConfig != oldConfig.submitterConfig) {
            when {
                newConfig.submitterConfig.userId == null -> disableSubmitter()
                oldConfig.submitterConfig.userId == null -> enableSubmitter()
                else -> submitter?.apply {
                    config = newConfig.submitterConfig
                }
            }
        }

        if (newConfig.restConfig != oldConfig.restConfig) {
            val newRest = newConfig.restConfig
            newRest.kafkaConfig?.let { kafkaConfig ->
                sender?.apply {
                    setCompression(newRest.useCompression)
                    setConnectionTimeout(newRest.connectionTimeout, TimeUnit.SECONDS)
                    if (oldConfig.restConfig.hasBinaryContent != newRest.hasBinaryContent) {
                        if (newConfig.restConfig.hasBinaryContent) {
                            useLegacyEncoding(RestSender.KAFKA_REST_ACCEPT_ENCODING, RestSender.KAFKA_REST_BINARY_ENCODING, true)
                        } else {
                            useLegacyEncoding(RestSender.KAFKA_REST_ACCEPT_ENCODING, RestSender.KAFKA_REST_AVRO_ENCODING, false)
                        }
                    }
                    headers = newConfig.restConfig.headers
                    setKafkaConfig(kafkaConfig)
                }
            }
        }

        batteryLevelReceiver.minimumLevel = newConfig.minimumBatteryLevel
        batteryLevelReceiver.reducedLevel = newConfig.reducedBatteryLevel
        batteryLevelReceiver.notifyListener()

        networkConnectedReceiver.notifyListener()
        start()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TableDataHandler::class.java)
    }
}
