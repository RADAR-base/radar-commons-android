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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.kafka.*
import org.radarbase.android.util.BatteryStageReceiver
import org.radarbase.android.util.CoroutineTaskExecutor
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.kotlin.coroutines.CacheConfig
import org.radarbase.kotlin.coroutines.launchJoin
import org.radarbase.producer.io.timeout
import org.radarbase.producer.io.unsafeSsl
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
import kotlin.collections.HashSet
import kotlin.time.Duration.Companion.hours
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
    private var config = DataHandlerConfiguration()

    private val batteryLevelReceiver: BatteryStageReceiver
    private val networkConnectedReceiver: NetworkConnectedReceiver = NetworkConnectedReceiver(context)

    override var serverStatus: MutableStateFlow<ServerStatus> = MutableStateFlow(ServerStatus.DISCONNECTED)
    override val numberOfRecords: MutableSharedFlow<CacheSize> = MutableSharedFlow(
        replay = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val recordsSent: MutableSharedFlow<TopicSendReceipt> = MutableSharedFlow(
        replay = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var submitter: KafkaDataSubmitter? = null
    private var sender: RestKafkaSender? = null

    private val handlerExecutor: CoroutineTaskExecutor = CoroutineTaskExecutor(TableDataHandler::class.simpleName!!, handlerDispatcher)

    private var cachedRecordObserverJob: Job? = null
    private var job: Job? = null
    private var networkStateCollectorJob: Job? = null
    private val observedCaches: MutableSet<String> = HashSet()

    private val isStarted: Boolean
        get() = submitter != null

    private val startMutex: Mutex = Mutex()

    init {
        job = SupervisorJob().also {
            handlerExecutor.start(it)
        }

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

        submitter?.close()
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
        if (networkStateCollectorJob?.isActive == true) return
        networkStateCollectorJob = handlerExecutor.returnJobAndExecute {
            startNetworkMonitoring()
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

    private suspend fun observerRecordsForCache(cache: ReadableDataCache) {
        if (observedCaches.contains(cache.readTopic.name)) {
            logger.debug("Records for cache are already being observed")
            return
        }
        cachedRecordObserverJob = handlerExecutor.returnJobAndExecute {
            cache.numberOfRecords.collect {
                logger.trace("{} records are cached for topic: {}", it, cache.readTopic.name)
                numberOfRecords.emit(
                    CacheSize(
                        cache.readTopic.name,
                        it
                    )
                )
            }
        }
        observedCaches.add(cache.readTopic.name)
    }

    private suspend fun startNetworkMonitoring() {
        networkConnectedReceiver.monitor()
    }

    /**
     * Start submitting data to the server.
     *
     * This will not do anything if there is not already a submitter running, if it is disabled,
     * if the network is not connected or if the battery is running too low.
     */
    suspend fun start() = handlerExecutor.execute {
        startMutex.withLock {
            if (isStarted
                || config.submitterConfig.userId == null
                || serverStatus.value === ServerStatus.DISABLED
                || !networkConnectedReceiver.latestState.hasConnection(config.sendOnlyWithWifi)
                || batteryLevelReceiver.stage == BatteryStageReceiver.BatteryStage.EMPTY
            ) {
                when {
                    isStarted -> logger.info("Submitter already started")

                    config.submitterConfig.userId == null ->
                        logger.info("Submitter has no user ID set. Not starting.")

                    serverStatus.value === ServerStatus.DISABLED ->
                        logger.info("Submitter has been disabled earlier. Not starting")

                    !networkConnectedReceiver.latestState.hasConnection(config.sendOnlyWithWifi) ->
                        logger.info("No network connection available. Not starting")

                    batteryLevelReceiver.stage == BatteryStageReceiver.BatteryStage.EMPTY ->
                        logger.info("Battery is empty. Not starting")
                }
                return@execute
            }
            logger.info("Starting data submitter")

            val kafkaConfig = config.restConfig.kafkaConfig ?: return@execute

            serverStatus.value = ServerStatus.CONNECTING

            val kafkaUrl: String = kafkaConfig.urlString
            logger.trace("KafkaSenderTrace: Initializing RestKafkaSender in TableDataHandler::start")
            try {
                val kafkaSender = restKafkaSender {
                    try {
                        val currentRestConfig = config.restConfig
                        baseUrl = kafkaUrl
                        headers.appendAll(currentRestConfig.headers)
                        httpClient {
                            timeout(currentRestConfig.connectionTimeout.seconds)
                            contentType = if (currentRestConfig.hasBinaryContent) {
                                KAFKA_REST_BINARY_ENCODING
                            } else {
                                KAFKA_REST_JSON_ENCODING
                            }
                            if (currentRestConfig.useCompression) {
                                contentEncoding = GZIP_CONTENT_ENCODING
                            }
                            if (currentRestConfig.unsafeKafka) {
                                unsafeSsl()
                            }
                        }
                        currentRestConfig.schemaRetrieverUrl?.let { schemaUrl ->
                            schemaRetriever(schemaUrl) {
                                schemaTimeout = CacheConfig(
                                    refreshDuration = 2.hours,
                                    retryDuration = 30.seconds
                                )
                                httpClient = HttpClient(CIO) {
                                    timeout(10.seconds)
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        logger.trace("KafkaSenderTrace: Error when setting configs for RestKafkaSender in TableDataHandler::start")
                    }
                }.also {
                    sender = it
                }

                this.submitter = KafkaDataSubmitter(this@TableDataHandler, kafkaSender, config.submitterConfig)
            } catch (e: Exception) {
                logger.trace(
                    "KafkaSenderTrace:  Exception when tweaking RestKafkaSender in TableDataHandler::start: ",
                    e
                )
            }
        }
    }

    /**
     * Pause sending any data.
     * This waits for any remaining data to be sent.
     */
    fun pause() = handlerExecutor.execute {
        submitter?.close()
        submitter = null
        sender = null
        if (serverStatus.value != ServerStatus.DISABLED) {
            serverStatus.value = ServerStatus.READY
        }
    }

    /** Do not submit any data, only cache it. If it is already disabled, this does nothing.  */
    private fun disableSubmitter() = handlerExecutor.execute {
        if (serverStatus.value !== ServerStatus.DISABLED) {
            logger.info("Submitter is disabled")
            serverStatus.value = ServerStatus.DISABLED
            if (isStarted) {
                pause()
            }
            networkStateCollectorJob?.cancel()
            networkStateCollectorJob = null
            batteryLevelReceiver.unregister()
        }
    }

    /** Start submitting data. If it is already submitting data, this does nothing.  */
    private fun enableSubmitter() = handlerExecutor.execute {
        if (serverStatus.value === ServerStatus.DISABLED) {
            doEnableSubmitter()
            start()
        }
    }

    private suspend fun doEnableSubmitter() {
        logger.info("Submitter is enabled")
        serverStatus.value = ServerStatus.READY
        monitor()
        batteryLevelReceiver.register()
    }

    /**
     * Sends any remaining data and closes the tables and connections.
     * @throws IOException if the tables cannot be flushed
     */
    @Throws(IOException::class)
    suspend fun stop() {
        if (serverStatus.value !== ServerStatus.DISABLED) {
            networkStateCollectorJob?.cancel()
            networkStateCollectorJob = null
            batteryLevelReceiver.unregister()
        }
        tables.values.forEach{
            it.stop()
        }
        this.submitter?.close()
        this.submitter = null
        this.sender = null

        cachedRecordObserverJob?.cancel()
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

    override suspend fun flushCaches(successCallback: () -> Unit, errorCallback: () -> Unit) {
        submitter
            ?.flush(successCallback, errorCallback)
            ?: errorCallback()
    }

    @Throws(IOException::class)
    override suspend fun <V : SpecificRecord> registerCache(
        topic: AvroTopic<ObservationKey, V>,
    ): DataCache<ObservationKey, V> {
        val cache = cacheStore
            .getOrCreateCaches(context.applicationContext, topic, config.cacheConfig)
            .also { tables[topic.name] = it }
            .activeDataCache

        observerRecordsForCache(cache)
        return cache
    }

    override fun handler(build: DataHandlerConfiguration.() -> Unit) = handlerExecutor.execute {
            val oldConfig = config

            config = config.copy().apply(build)
            if (config == oldConfig) {
                return@execute
            }

            if (config.restConfig.kafkaConfig != null
                && config.restConfig.schemaRetrieverUrl != null
                && config.submitterConfig.userId != null
            ) {
                enableSubmitter()
            } else {
                if (config.restConfig.kafkaConfig == null) {
                    logger.info("No kafka configuration given. Disabling submitter")
                }
                if (config.restConfig.schemaRetrieverUrl == null) {
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

                    oldConfig.restConfig.hasBinaryContent != newRest.hasBinaryContent
                    logger.trace(
                        "KafkaSenderTrace: Tweaking  RestKafkaSender in TableDataHandler::handler: Is sender null? {}.",
                        sender == null
                    )

                    try {
                        sender = sender?.config {
                            baseUrl = kafkaConfig.urlString
                            headers.appendAll(newRest.headers)

                            httpClient {
                                timeout(newRest.connectionTimeout.seconds)
                                contentType = if (newRest.hasBinaryContent) {
                                    KAFKA_REST_BINARY_ENCODING
                                } else {
                                    KAFKA_REST_JSON_ENCODING
                                }
                                if (newRest.useCompression) {
                                    contentEncoding = GZIP_CONTENT_ENCODING
                                }
                                if (newRest.unsafeKafka) {
                                    unsafeSsl()
                                }
                            }
                            val schemaRetrieverUrl = newRest.schemaRetrieverUrl
                            if (schemaRetrieverUrl != oldConfig.restConfig.schemaRetrieverUrl) {
                                schemaRetrieverUrl?.let { srUrl ->
                                    schemaRetriever(srUrl) {
                                        schemaTimeout = CacheConfig(
                                            refreshDuration = 2.hours,
                                            retryDuration = 30.seconds
                                        )
                                        httpClient = HttpClient(CIO) {
                                            timeout(10.seconds)
                                        }
                                    }
                                }
                            }
                        }
                        logger.trace("KafkaSenderTrace: Completed Initializing RestKafkaSender in TableDataHandler::handler")
                    } catch (e: Exception) {
                        logger.trace(
                            "KafkaSenderTrace:  Exception when tweaking RestKafkaSender in TableDataHandler::handler: ",
                            e
                        )
                    }

                    sender?.resetConnection()
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
