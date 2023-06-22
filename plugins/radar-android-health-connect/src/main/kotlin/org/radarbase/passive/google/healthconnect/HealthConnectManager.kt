package org.radarbase.passive.google.healthconnect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.util.Base64.NO_PADDING
import android.util.Base64.URL_SAFE
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ChangesTokenRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarcns.passive.google.healthconnect.HealthConnectDeletion
import org.radarcns.passive.google.healthconnect.HealthConnectDevice
import org.radarcns.passive.google.healthconnect.HealthConnectDeviceType
import org.radarcns.passive.google.healthconnect.HealthConnectMetadata
import org.radarcns.passive.google.healthconnect.HealthConnectRecordingMethod
import org.radarcns.passive.google.healthconnect.HealthConnectTypedData
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@RequiresApi(Build.VERSION_CODES.O_MR1)
class HealthConnectManager(service: HealthConnectService) :
    AbstractSourceManager<HealthConnectService, BaseSourceState>(service)
{
    private lateinit var healthConnectClient: HealthConnectClient
    private val dataStore = service.dataStore
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.Default)
    private val deletionCache = createCache("android_health_connect_deletion", HealthConnectDeletion())
    private val typedDataCache = createCache("android_health_connect_typed_data", HealthConnectTypedData())
    private val deviceCache = createCache("android_health_connect_device", HealthConnectDevice())

    @Volatile
    var dataTypes: Set<KClass<out Record>> = setOf()

    private val processor = OfflineProcessor(service) {
        requestCode = REQUEST_CODE
        requestName = MANAGER_NAME
        wake = false
        process = listOf(
            ::fetchRecords,
        )
        interval(defaultInterval)
    }

    var interval: Duration = defaultInterval
        set(value) {
            processor.interval(value)
            field = value
        }

    @Volatile
    private var changesTokens = mapOf<KClass<out Record>, String>()

    private val devices = ConcurrentHashMap<LocalDevice, HealthConnectDevice>()

    @Volatile
    private var didLoad = false

    init {
        if (HealthConnectClient.getSdkStatus(service) != HealthConnectClient.SDK_AVAILABLE) {
            status = SourceStatusListener.Status.UNAVAILABLE
        } else {
            healthConnectClient = HealthConnectClient.getOrCreate(service)
        }
    }

    override fun start(acceptableIds: Set<String>) {
        register()
        status = SourceStatusListener.Status.READY
    }

    override fun didRegister(source: SourceMetadata) {
        super.didRegister(source)
        processor.start()
        managerScope.launch {
            dataStore.data
                .catch { ex -> logger.error("Failed to read data from datastore", ex) }
                .collect { preferences ->
                    changesTokens = buildMap {
                        preferences.changeTokensMap.forEach { (name, token) ->
                            val cls = healthConnectTypes[name] ?: return@forEach
                            put(cls, token)
                        }
                    }
                    if (!didLoad) {
                        preferences.devicesList.forEach {
                            devices[it.toLocalDevice()] = it.toHealthConnectDevice()
                        }
                        didLoad = true
                    }
                }
        }
    }

    private fun fetchRecords() {
        managerScope.launch {
            status = SourceStatusListener.Status.CONNECTED

            coroutineScope {
                launch { processSteps() }
                launch { processHeartRate() }
            }

            status = SourceStatusListener.Status.READY
        }
    }

    private suspend fun processHeartRate() = processRecord<HeartRateRecord> { record ->
        record.samples.map { sample ->
            healthConnectTypedData<HeartRateRecord> {
                time = sample.time.toDouble()
                startTime = record.startTime.toDouble()
                endTime = record.endTime.toDouble()
                timeZoneOffset = record.startZoneOffset?.totalSeconds
                    ?: record.endZoneOffset?.totalSeconds
                metadata = record.metadata.toHealthConnectMetadata()
                unit = "bpm"
                intValue = sample.beatsPerMinute.toInt()
            }
        }
    }

    private suspend fun processSteps() = processRecord<StepsRecord> { record ->
        listOf(
            healthConnectTypedData<StepsRecord> {
                time = record.startTime.toDouble()
                endTime = record.endTime.toDouble()
                timeZoneOffset = record.startZoneOffset?.totalSeconds
                    ?: record.endZoneOffset?.totalSeconds
                metadata = record.metadata.toHealthConnectMetadata()
                intValue = record.count.toInt()
            }
        )
    }

    private suspend inline fun <reified T: Record> processRecord(convert: (T) -> List<HealthConnectTypedData>) {
        if (T::class !in dataTypes) {
            return
        }
        val permission = HealthPermission.getReadPermission(T::class)
        if (ActivityCompat.checkSelfPermission(service, permission) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val originalToken = changesTokens[T::class]

        try {
            var token = originalToken
                ?: healthConnectClient.getChangesToken(
                    ChangesTokenRequest(recordTypes = setOf(T::class))
                )

            do {
                var response = healthConnectClient.getChanges(token)

                if (response.changesTokenExpired) {
                    token = healthConnectClient.getChangesToken(
                        ChangesTokenRequest(recordTypes = setOf(T::class))
                    )
                    response = healthConnectClient.getChanges(token)
                }

                response.changes.forEach { change ->
                    when (change) {
                        is DeletionChange -> {
                            send(deletionCache, HealthConnectDeletion(currentTime, change.recordId))
                        }
                        is UpsertionChange -> {
                            val record = change.record
                            if (record is T) {
                                convert(record).forEach {
                                    send(typedDataCache, it)
                                }
                            }
                        }
                    }
                }

                token = response.nextChangesToken
            } while (response.hasMore)

            if (token != originalToken) {
                val key = healthConnectNames[T::class]
                if (key != null) {
                    dataStore.update {
                        putChangeTokens(key, token)
                    }
                }
            }
        } catch (ex: SecurityException) {
            logger.error("Not allowed to access Health Connect data", ex)
        }
    }


    private suspend fun Metadata.toHealthConnectMetadata() = HealthConnectMetadata.newBuilder().run {
        id = this@toHealthConnectMetadata.id
        dataOrigin = this@toHealthConnectMetadata.dataOrigin.packageName
        lastModified = this@toHealthConnectMetadata.lastModifiedTime.toDouble()
        recordingMethod = this@toHealthConnectMetadata.recordingMethod.toRecordingMethod()

        val mDevice = this@toHealthConnectMetadata.device
        if (mDevice != null) {
            deviceId = lookupDevice(mDevice.manufacturer, mDevice.manufacturer, mDevice.type.toDeviceType()).id
        }
        build()
    }

    private suspend fun lookupDevice(
        manufacturer: String?,
        model: String?,
        type: HealthConnectDeviceType,
    ): HealthConnectDevice {
        val device = LocalDevice(manufacturer, model, type.ordinal)
        val existingDevice = devices[device]
        if (existingDevice !== null) {
            return existingDevice
        }

        val hkDevice = HealthConnectDevice.newBuilder().run {
            this.time = currentTime
            id = buildString(12) {
                append(manufacturer?.firstOrNull() ?: 'U')
                append(model?.firstOrNull() ?: 'U')
                append(type)
                append('-')
                append(
                    Base64.encodeToString(
                        ByteArray(6).apply {
                            ThreadLocalRandom.current().nextBytes(this)
                        },
                        NO_PADDING or URL_SAFE,
                    ),
                )

            }
            this.manufacturer = manufacturer
            this.model = model
            this.type = type
            build()
        }
        val nextDevice = devices.putIfAbsent(device, hkDevice)
        return if (nextDevice !== null) {
            nextDevice
        } else {
            dataStore.update {
                addDevices(hkDevice.toHealthConnectDevicePreferences())
            }
            send(deviceCache, hkDevice)
            hkDevice
        }
    }

    override fun onClose() {
        runBlocking(Dispatchers.Unconfined) {
            managerJob.cancelAndJoin()
        }
    }

    companion object {
        private const val REQUEST_CODE = 231031
        private const val MANAGER_NAME = "org.radarbase.passive.google.healthconnect.HealthConnectManager"
        private val defaultInterval = 1.hours

        private val logger = LoggerFactory.getLogger(HealthConnectManager::class.java)

        private val Context.dataStore: DataStore<HealthConnectPreferences> by dataStore(
            fileName = "$MANAGER_NAME.pb",
            serializer = HealthConnectPreferencesSerializer,
        )

        private suspend fun DataStore<HealthConnectPreferences>.update(
            updater: HealthConnectPreferences.Builder.() -> Unit
        ) = updateData {
            it.newBuilderForType()
                .apply(updater)
                .build()
        }

        private fun Instant.toDouble(): Double = epochSecond + nano / 1_000_000_000.0

        private fun HealthConnectDevice.toHealthConnectDevicePreferences() = HealthConnectDevicePreferences.newBuilder().run {
            this.id = this@toHealthConnectDevicePreferences.id
            this.manufacturer = this@toHealthConnectDevicePreferences.manufacturer
            this.model = this@toHealthConnectDevicePreferences.model
            this.type = this@toHealthConnectDevicePreferences.type.ordinal
            build()
        }

        private fun HealthConnectDevicePreferences.toLocalDevice() = LocalDevice(
            manufacturer,
            model,
            type,
        )

        private fun HealthConnectDevicePreferences.toHealthConnectDevice() = HealthConnectDevice(
            currentTime,
            id,
            manufacturer,
            model,
            HealthConnectDeviceType.values()[type]
        )

        private fun Int.toDeviceType(): HealthConnectDeviceType = when (this) {
            Device.TYPE_WATCH -> HealthConnectDeviceType.WATCH
            Device.TYPE_PHONE -> HealthConnectDeviceType.PHONE
            Device.TYPE_SCALE -> HealthConnectDeviceType.SCALE
            Device.TYPE_RING -> HealthConnectDeviceType.RING
            Device.TYPE_HEAD_MOUNTED -> HealthConnectDeviceType.HEAD_MOUNTED
            Device.TYPE_FITNESS_BAND -> HealthConnectDeviceType.FITNESS_BAND
            Device.TYPE_CHEST_STRAP -> HealthConnectDeviceType.CHEST_STRAP
            Device.TYPE_SMART_DISPLAY -> HealthConnectDeviceType.SMART_DISPLAY
            else -> HealthConnectDeviceType.UNKNOWN
        }

        private fun Int.toRecordingMethod(): HealthConnectRecordingMethod = when(this) {
            1 -> HealthConnectRecordingMethod.ACTIVELY_RECORDED
            2 -> HealthConnectRecordingMethod.AUTOMATICALLY_RECORDED
            3 -> HealthConnectRecordingMethod.MANUAL_ENTRY
            else -> HealthConnectRecordingMethod.UNKNOWN
        }

        private inline fun <reified T: Record> healthConnectTypedData(convert: HealthConnectTypedData.Builder.() -> Unit): HealthConnectTypedData {
            return HealthConnectTypedData.newBuilder().run {
                key = checkNotNull(healthConnectNames[T::class])
                timeReceived = currentTime
                convert()
                build()
            }
        }

        data class LocalDevice(
            val manufacturer: String?,
            val model: String?,
            val type: Int
        )
    }
}

