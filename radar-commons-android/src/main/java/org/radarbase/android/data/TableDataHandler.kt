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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.kafka.*
import org.radarbase.android.source.SourceService.Companion.CACHE_RECORDS_UNSENT_NUMBER
import org.radarbase.android.source.SourceService.Companion.CACHE_TOPIC
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
class TableDataHandler(
    private val context: Context,
    private val cacheStore: CacheStore,
) : DataHandler<ObservationKey, SpecificRecord> {
    private val tables: ConcurrentMap<String, DataCacheGroup<*, *>> = ConcurrentHashMap()
    private val batteryLevelReceiver: BatteryStageReceiver
    private val networkConnectedReceiver: NetworkConnectedReceiver = NetworkConnectedReceiver(context)
    private val handlerThread: SafeHandler = SafeHandler.getInstance("TableDataHandler", THREAD_PRIORITY_BACKGROUND)

    private var config = DataHandlerConfiguration()

    @Volatile
    var latestStatus: ServerStatus = ServerStatus.DISCONNECTED

    override var serverStatus = MutableStateFlow(ServerStatus.DISCONNECTED)

    private val lastNumberOfRecordsSent = TreeMap<String, Long>()
    private var submitter: KafkaDataSubmitter? = null
    private var sender: RestSender? = null

    private val isStarted: Boolean
        get() = submitter != null

    override val recordsSent = MutableSharedFlow<TopicSendResult>(
        replay = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {

        this.handlerThread.start()
        this.handlerThread.repeat(10_000L, ::broadcastNumberOfRecords)

        this.batteryLevelReceiver = BatteryStageReceiver(context, config.batteryStageLevels) { stage ->
            when (stage) {
                BatteryStageReceiver.BatteryStage.FULL -> {
                    handler {
                        submitter {
                            uploadRateMultiplier = 1
                        }
                    }
                }
                BatteryStageReceiver.BatteryStage.REDUCED -> {
                    logger.info("Battery level getting low, reducing data sending")
                    handler {
                        submitter {
                            uploadRateMultiplier = reducedUploadMultiplier
                        }
                    }
                }
                BatteryStageReceiver.BatteryStage.EMPTY -> {
                    if (isStarted) {
                        logger.info("Battery level getting very low, stopping data sending")
                        stop()
                    }
                }
            }
        }

        submitter = null
        sender = null

        if (config.restConfig.kafkaConfig != null) {
            handlerThread.execute {
                doEnableSubmitter()
            }
        } else {
            logger.info("Submitter is disabled: no kafkaConfig provided in init")
            serverStatus.value = ServerStatus.DISABLED
        }
    }

    suspend fun monitor() {
        coroutineScope {
            launch {
                networkConnectedReceiver.state.collectLatest { state ->
                    if (isStarted) {
                        if (!state.hasConnection(config.sendOnlyWithWifi)) {
                            logger.info("Network was disconnected, stopping data sending")
                            stop()
                        }
                    } else {
                        // Just try to start: the start method will not do anything if the parameters
                        // are not right.
                        start()
                    }
                }
            }
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
    fun start() = handlerThread.executeReentrant {
        if (isStarted
            || config.submitterConfig.userId == null
            || serverStatus === ServerStatus.DISABLED
            || !networkConnectedReceiver.hasConnection(config.sendOnlyWithWifi)
            || batteryLevelReceiver.stage == BatteryStageReceiver.BatteryStage.EMPTY
        ) {
            when {
                config.submitterConfig.userId == null ->
                    logger.info("Submitter has no user ID set. Not starting.")
                serverStatus === ServerStatus.DISABLED ->
                    logger.info("Submitter has been disabled earlier. Not starting")
                !networkConnectedReceiver.hasConnection(config.sendOnlyWithWifi) ->
                    logger.info("No networkconnection available. Not starting")
                batteryLevelReceiver.stage == BatteryStageReceiver.BatteryStage.EMPTY ->
                    logger.info("Battery is empty. Not starting")
            }
            return@executeReentrant
        }
        logger.info("Starting data submitter")

        val kafkaConfig = config.restConfig.kafkaConfig ?: return@executeReentrant

        serverStatus = ServerStatus.CONNECTING

        val client = RestClient.global()
            .server(kafkaConfig)
            .gzipCompression(config.restConfig.useCompression)
            .timeout(config.restConfig.connectionTimeout, TimeUnit.SECONDS)
            .build()

        val sender = RestSender.Builder().apply {
            httpClient(client)
            schemaRetriever(config.restConfig.schemaRetriever)
            headers(config.restConfig.headers)
            useBinaryContent(config.restConfig.hasBinaryContent)
        }.build().also {
            sender = it
        }

        this.submitter = KafkaDataSubmitter(this, sender, config.submitterConfig)
    }

    /**
     * Pause sending any data.
     * This waits for any remaining data to be sent.
     */
    fun stop() = handlerThread.executeReentrant {
        submitter?.close()
        submitter = null
        sender = null
        if (serverStatus != ServerStatus.DISABLED) {
            serverStatus = ServerStatus.READY
        }
    }

    /** Do not submit any data, only cache it. If it is already disabled, this does nothing.  */
    private fun disableSubmitter() = handlerThread.executeReentrant {
        if (serverStatus !== ServerStatus.DISABLED) {
            logger.info("Submitter is disabled")
            serverStatus = ServerStatus.DISABLED
            if (isStarted) {
                stop()
            }
            networkConnectedReceiver.unregister()
            batteryLevelReceiver.unregister()
        }
    }

    /** Start submitting data. If it is already submitting data, this does nothing.  */
    private fun enableSubmitter() = handlerThread.executeReentrant {
        if (serverStatus === ServerStatus.DISABLED) {
            doEnableSubmitter()
            start()
        }
    }

    private fun doEnableSubmitter() {
        logger.info("Submitter is enabled")
        serverStatus = ServerStatus.READY
        networkConnectedReceiver.register()
        batteryLevelReceiver.register()
    }

    /**
     * Sends any remaining data and closes the tables and connections.
     * @throws IOException if the tables cannot be flushed
     */
    @Throws(IOException::class)
    fun close() {
        handlerThread.stop {
            if (serverStatus !== ServerStatus.DISABLED) {
                networkConnectedReceiver.unregister()
                batteryLevelReceiver.unregister()
            }
            this.submitter?.close()
            this.submitter = null
            this.sender = null
        }

        tables.values.forEach(DataCacheGroup<*, *>::close)
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

    override val activeCaches: List<DataCacheGroup<*, *>>
        get() = handlerThread.compute {
            if (submitter == null) {
                emptyList()
            } else if (networkConnectedReceiver.state.hasWifiOrEthernet || !config.sendOverDataHighPriority) {
                ArrayList(tables.values)
            } else {
                tables.values.filter { it.topicName in config.highPriorityTopics }
            }
        }

    var statusListener: ServerStatusListener? = null
        get() = handlerThread.compute { field }
        set(value) = handlerThread.execute { field = value }

    override fun updateRecordsSent(topicName: String, numberOfRecords: Long) {
        handlerThread.execute {
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

    override fun flushCaches(successCallback: () -> Unit, errorCallback: () -> Unit) {
        submitter
            ?.flush(successCallback, errorCallback)
            ?: errorCallback()
    }

    @Throws(IOException::class)
    override fun <V: SpecificRecord> registerCache(
        topic: AvroTopic<ObservationKey, V>,
        handler: SafeHandler?,
    ): DataCache<ObservationKey, V> {
        return cacheStore
                .getOrCreateCaches(context.applicationContext, topic, config.cacheConfig, handler)
                .also { tables[topic.name] = it }
                .activeDataCache
    }

    override fun handler(build: DataHandlerConfiguration.() -> Unit) = handlerThread.executeReentrant {
        val oldConfig = config

        config = config.copy().apply(build)
        if (config == oldConfig) {
            return@executeReentrant
        }

        if (config.restConfig.kafkaConfig != null
                && config.restConfig.schemaRetriever != null
                && config.submitterConfig.userId != null) {
            enableSubmitter()
        } else {
            if (config.restConfig.kafkaConfig == null) {
                logger.info("No kafka configuration given. Disabling submitter")
            }
            if (config.restConfig.schemaRetriever == null) {
                logger.info("No schema registry configuration given. Disabling submitter")
            }
            if (config.submitterConfig.userId == null) {
                logger.info("No user ID given. Disabling submitter")
            }
            disableSubmitter()
        }

        if (config.cacheConfig != oldConfig.cacheConfig) {
            tables.values.forEach { it.activeDataCache.config = config.cacheConfig }
        }

        if (config.submitterConfig != oldConfig.submitterConfig) {
            when {
                config.submitterConfig.userId == null -> disableSubmitter()
                oldConfig.submitterConfig.userId == null -> enableSubmitter()
                else -> submitter?.config = config.submitterConfig
            }
        }

        if (config.restConfig != oldConfig.restConfig) {
            val newRest = config.restConfig
            newRest.kafkaConfig?.let { kafkaConfig ->
                sender?.apply {
                    setCompression(newRest.useCompression)
                    setConnectionTimeout(newRest.connectionTimeout, TimeUnit.SECONDS)
                    if (oldConfig.restConfig.hasBinaryContent != newRest.hasBinaryContent) {
                        if (config.restConfig.hasBinaryContent) {
                            useLegacyEncoding(RestSender.KAFKA_REST_ACCEPT_ENCODING, RestSender.KAFKA_REST_BINARY_ENCODING, true)
                        } else {
                            useLegacyEncoding(RestSender.KAFKA_REST_ACCEPT_ENCODING, RestSender.KAFKA_REST_AVRO_ENCODING, false)
                        }
                    }
                    headers = config.restConfig.headers
                    setKafkaConfig(kafkaConfig)
                    resetConnection()
                }
            }
        }

        batteryLevelReceiver.stageLevels = config.batteryStageLevels

        networkConnectedReceiver.notifyListener()
        start()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TableDataHandler::class.java)
    }
}
