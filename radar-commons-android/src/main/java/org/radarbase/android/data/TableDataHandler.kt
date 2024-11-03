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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.kafka.*
import org.radarbase.android.util.BatteryStageReceiver
import org.radarbase.android.util.CoroutineTaskExecutor
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.android.util.SafeHandler
import org.radarbase.kotlin.coroutines.launchJoin
import org.radarbase.producer.io.timeout
import org.radarbase.producer.rest.RestKafkaSender
import org.radarbase.producer.rest.RestKafkaSender.Companion.GZIP_CONTENT_ENCODING
import org.radarbase.producer.rest.RestKafkaSender.Companion.KAFKA_REST_BINARY_ENCODING
import org.radarbase.producer.rest.RestKafkaSender.Companion.KAFKA_REST_JSON_ENCODING
import org.radarbase.producer.rest.RestKafkaSender.Companion.restKafkaSender
import org.radarbase.topic.AvroTopic
import org.radarcns.kafka.ObservationKey
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Stores data in databases and sends it to the server. If kafkaConfig is null, data will only be
 * stored to disk, not uploaded.
 */
class TableDataHandler(
    private val context: Context,
    private val cacheStore: CacheStore,
    handlerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DataHandler<ObservationKey, SpecificRecord> {
    private val tables: ConcurrentMap<String, DataCacheGroup<*, *>> = ConcurrentHashMap()
    private val batteryLevelReceiver: BatteryStageReceiver
    private val networkConnectedReceiver: NetworkConnectedReceiver = NetworkConnectedReceiver(context)
    private var config = DataHandlerConfiguration()

    override var serverStatus = MutableStateFlow(ServerStatus.DISCONNECTED)
    override val numberOfRecords = MutableSharedFlow<CacheSize>(onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val lastNumberOfRecordsSent = TreeMap<String, Long>()
    private var submitter: KafkaDataSubmitter? = null
    private var sender: RestKafkaSender? = null

    private val handlerExecutor: CoroutineTaskExecutor =
        CoroutineTaskExecutor(TableDataHandler::class.simpleName!!, handlerDispatcher)

    private var job: Job? = null
    private var networkStateCollectorJob: Job? = null
//    private val handlerScope = CoroutineScope(handlerDispatcher + Job() + CoroutineName("Table Data Handler"))

    private val isStarted: Boolean
        get() = submitter != null

    override val recordsSent = MutableSharedFlow<TopicSendReceipt>(
        replay = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        job = SupervisorJob().also(handlerExecutor::start)

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
                        pause()
                    }
                }
            }
        }

        submitter = null
        sender = null

        if (config.restConfig.kafkaConfig != null) {
            handlerExecutor.execute {
                doEnableSubmitter()
            }
        } else {
            logger.info("Submitter is disabled: no kafkaConfig provided in init")
            serverStatus.value = ServerStatus.DISABLED
        }
    }

    suspend fun monitor() {
        networkStateCollectorJob = handlerExecutor.returnJobAndExecute {
            networkConnectedReceiver.monitor()
            networkConnectedReceiver.state?.collectLatest { state ->
                if (isStarted) {
                    if (!state.hasConnection(config.sendOnlyWithWifi)) {
                        logger.info("Network was disconnected, stopping data sending")
                        pause()
                    }
                } else {
                    // Just try to start: the start method will not do anything if the parameters
                    // are not right.
                    start()
                }
            }
        }
    }

    /**
     * Start submitting data to the server.
     *
     * This will not do anything if there is not already a submitter running, if it is disabled,
     * if the network is not connected or if the battery is running too low.
     */
    fun start() = handlerExecutor.executeReentrant {
        if (isStarted
            || config.submitterConfig.userId == null
            || serverStatus.value === ServerStatus.DISABLED
            || !networkConnectedReceiver.latestState.hasConnection(config.sendOnlyWithWifi)
            || batteryLevelReceiver.stage == BatteryStageReceiver.BatteryStage.EMPTY
        ) {
            when {
                config.submitterConfig.userId == null ->
                    logger.info("Submitter has no user ID set. Not starting.")
                serverStatus.value === ServerStatus.DISABLED ->
                    logger.info("Submitter has been disabled earlier. Not starting")
                !networkConnectedReceiver.latestState.hasConnection(config.sendOnlyWithWifi) ->
                    logger.info("No networkconnection available. Not starting")
                batteryLevelReceiver.stage == BatteryStageReceiver.BatteryStage.EMPTY ->
                    logger.info("Battery is empty. Not starting")
            }
            return@executeReentrant
        }
        logger.info("Starting data submitter")

        val kafkaConfig = config.restConfig.kafkaConfig ?: return@executeReentrant

        serverStatus.value = ServerStatus.CONNECTING

        val kafkaUrl: String = kafkaConfig.urlString

        val kafkaSender = restKafkaSender {
            baseUrl = kafkaUrl
            headers.appendAll(config.restConfig.headers)
            httpClient {
                timeout(config.restConfig.connectionTimeout.seconds)
                contentType = if (config.restConfig.hasBinaryContent) {
                    KAFKA_REST_BINARY_ENCODING
                } else {
                    KAFKA_REST_JSON_ENCODING
                }
                if (config.restConfig.useCompression) {
                    contentEncoding = GZIP_CONTENT_ENCODING
                }
            }
            schemaRetriever = config.restConfig.schemaRetriever
        }.also {
            sender = it
        }

//        val client = RestClient.global()
//            .server(kafkaConfig)
//            .gzipCompression(config.restConfig.useCompression)
//            .timeout(config.restConfig.connectionTimeout, TimeUnit.SECONDS)
//            .build()

//        val sender = RestSender.Builder().apply {
//            httpClient(client)
//            schemaRetriever(config.restConfig.schemaRetriever)
//            headers(config.restConfig.headers)
//            useBinaryContent(config.restConfig.hasBinaryContent)
//        }.build().also {
//            sender = it
//        }

        this.submitter = KafkaDataSubmitter(this, kafkaSender, config.submitterConfig)
    }

    /**
     * Pause sending any data.
     * This waits for any remaining data to be sent.
     */
    fun pause() = handlerExecutor.executeReentrant {
        submitter?.close()
        submitter = null
        sender = null
        if (serverStatus.value != ServerStatus.DISABLED) {
            serverStatus.value = ServerStatus.READY
        }
    }

    /** Do not submit any data, only cache it. If it is already disabled, this does nothing.  */
    private fun disableSubmitter() = handlerExecutor.executeReentrant {
        if (serverStatus.value !== ServerStatus.DISABLED) {
            logger.info("Submitter is disabled")
            serverStatus.value = ServerStatus.DISABLED
            if (isStarted) {
                pause()
            }
//            networkConnectedReceiver.unregister()
            networkStateCollectorJob?.cancel()
            batteryLevelReceiver.unregister()
        }
    }

    /** Start submitting data. If it is already submitting data, this does nothing.  */
    private fun enableSubmitter() = handlerExecutor.executeReentrant {
        if (serverStatus.value === ServerStatus.DISABLED) {
            doEnableSubmitter()
            start()
        }
    }

    private fun doEnableSubmitter() {
        logger.info("Submitter is enabled")
        serverStatus.value = ServerStatus.READY
//        networkConnectedReceiver.register()
        networkConnectedReceiver.monitor()
        batteryLevelReceiver.register()
    }

    /**
     * Sends any remaining data and closes the tables and connections.
     * @throws IOException if the tables cannot be flushed
     */
    @Throws(IOException::class)
    suspend fun stop() {
//        job.cancelAndJoin()
        if (serverStatus.value !== ServerStatus.DISABLED) {
//            networkConnectedReceiver.unregister()
            networkStateCollectorJob?.cancel()
            batteryLevelReceiver.unregister()
        }
        this.submitter?.close()
        this.submitter = null
        this.sender = null

        tables.values.launchJoin(DataCacheGroup<*, *>::stop)
        handlerExecutor.stop()
    }

    /**
     * Get the table of a given topic
     */
    override fun getCache(topic: String): DataCache<*, *> {
        return tables[topic]?.activeDataCache ?: throw NullPointerException()
    }

    override val caches: List<ReadableDataCache>
        get() = buildList(tables.size) {
            tables.values.forEach {
                add(it.activeDataCache)
                addAll(it.deprecatedCaches)
            }
        }

    override val activeCaches: List<DataCacheGroup<*, *>>
        get() = if (submitter == null) {
                emptyList()
            } else if (networkConnectedReceiver.latestState.hasWifiOrEthernet || !config.sendOverDataHighPriority) {
                ArrayList(tables.values)
            } else {
                tables.values.filter { it.topicName in config.highPriorityTopics }
            }


//    override fun updateRecordsSent(topicName: String, numberOfRecords: Long) {
//        handlerThread.execute {
//            statusListener?.updateRecordsSent(topicName, numberOfRecords)
//
//            // Overwrite key-value if exists. Only stores the last
//            this.lastNumberOfRecordsSent[topicName] = numberOfRecords
//
//            if (numberOfRecords < 0) {
//                logger.warn("{} has FAILED uploading", topicName)
//            } else {
//                logger.info("{} uploaded {} records", topicName, numberOfRecords)
//            }
//        }
//    }

    override suspend fun flushCaches(successCallback: () -> Unit, errorCallback: () -> Unit) {
        submitter
            ?.flush(successCallback, errorCallback)
            ?: errorCallback()
    }

    @Throws(IOException::class)
    override suspend fun <V: SpecificRecord> registerCache(
        topic: AvroTopic<ObservationKey, V>,
        handler: SafeHandler?,
    ): DataCache<ObservationKey, V> {
        return cacheStore
                .getOrCreateCaches(context.applicationContext, topic, config.cacheConfig)
                .also { tables[topic.name] = it }
                .activeDataCache
    }

    override fun handler(build: DataHandlerConfiguration.() -> Unit) = handlerExecutor.executeReentrant {
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
            tables.values.forEach { it.activeDataCache.config.value = config.cacheConfig }
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

                val contentTypeChanged: Boolean =
                    oldConfig.restConfig.hasBinaryContent != newRest.hasBinaryContent

                sender = sender?.config {
                    baseUrl = kafkaConfig.urlString
                    headers.appendAll(newRest.headers)

                    httpClient {
                        timeout(newRest.connectionTimeout.seconds)
                        if (contentTypeChanged) {
                            contentType = if (config.restConfig.hasBinaryContent) {
                                KAFKA_REST_BINARY_ENCODING
                            } else {
                                KAFKA_REST_JSON_ENCODING
                            }
                        }
                        if (newRest.useCompression) {
                            contentEncoding = GZIP_CONTENT_ENCODING
                        }
                    }
                    if (newRest.schemaRetriever != oldConfig.restConfig.schemaRetriever) {
                        schemaRetriever = newRest.schemaRetriever
                    }
                }

                sender?.resetConnection()

//                    sender?.apply {
//                        setCompression(newRest.useCompression)
//                        setConnectionTimeout(newRest.connectionTimeout, TimeUnit.SECONDS)
//                        if (oldConfig.restConfig.hasBinaryContent != newRest.hasBinaryContent) {
//                            if (config.restConfig.hasBinaryContent) {
//                                useLegacyEncoding(
//                                    RestSender.KAFKA_REST_ACCEPT_ENCODING,
//                                    RestSender.KAFKA_REST_BINARY_ENCODING,
//                                    true
//                                )
//                            } else {
//                                useLegacyEncoding(
//                                    RestSender.KAFKA_REST_ACCEPT_ENCODING,
//                                    RestSender.KAFKA_REST_AVRO_ENCODING,
//                                    false
//                                )
//                            }
//                        }
//                        headers = config.restConfig.` headers
//                                setKafkaConfig(kafkaConfig)
//                        resetConnection()
//                    }
                }
        }

        batteryLevelReceiver.stageLevels = config.batteryStageLevels

        networkConnectedReceiver.latestState.also { state ->
            if (isStarted) {
                if (!state.hasConnection(config.sendOnlyWithWifi)) {
                    logger.info("Network was disconnected, stopping data sending.")
                    pause()
                }
            } else {
                // Just try to start: the start method will not do anything if the parameters
                // are not right.
                start()
            }
        }
        start()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TableDataHandler::class.java)
    }

    data class CacheSize(
        val topicName: String,
        val numberOfRecords: Long,
    )
}
