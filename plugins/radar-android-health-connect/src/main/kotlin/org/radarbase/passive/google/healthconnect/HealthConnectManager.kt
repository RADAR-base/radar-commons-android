package org.radarbase.passive.google.healthconnect

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Base64.NO_PADDING
import android.util.Base64.URL_SAFE
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepStageRecord
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.google.healthconnect.HealthConnectDeletion
import org.radarcns.passive.google.healthconnect.HealthConnectDevice
import org.radarcns.passive.google.healthconnect.HealthConnectDeviceType
import org.radarcns.passive.google.healthconnect.HealthConnectMetadata
import org.radarcns.passive.google.healthconnect.HealthConnectRecordingMethod
import org.radarcns.passive.google.healthconnect.HealthConnectTypedData
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@RequiresApi(Build.VERSION_CODES.O_MR1)
class HealthConnectManager(service: HealthConnectService) :
    AbstractSourceManager<HealthConnectService, BaseSourceState>(service)
{
    private val healthConnectClient: HealthConnectClient
    private val dataStore = service.dataStore
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.Default)
    private val deletionCache = createCache("android_health_connect_deletion", HealthConnectDeletion())
    private val typedDataCache = createCache("android_health_connect_typed_data", HealthConnectTypedData())
    private val deviceCache = createCache("android_health_connect_device", HealthConnectDevice())

    @Volatile
    private var grantedPermissions: Set<String> = setOf()

    @Volatile
    var dataTypes: Set<KClass<out Record>> = setOf()

    private val changesTokens = ConcurrentHashMap<KClass<out Record>, String>()
    private val devices = ConcurrentHashMap<LocalDevice, HealthConnectDevice>()

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

    init {
        healthConnectClient = HealthConnectClient.getOrCreate(service)
    }

    override fun start(acceptableIds: Set<String>) {
        register()
        status = SourceStatusListener.Status.READY
    }

    override fun didRegister(source: SourceMetadata) {
        super.didRegister(source)
        try {
            managerScope.launch {
                val preferences = dataStore.data
                    .catch { ex -> logger.error("Failed to read data from datastore", ex) }
                    .first()

                preferences.changeTokensMap.forEach { (name, token) ->
                    val cls = healthConnectTypes[name] ?: return@forEach
                    changesTokens[cls] = token
                }
                preferences.devicesList.forEach {
                    devices[it.toLocalDevice()] = it.toHealthConnectDevice()
                }
                if (isActive) {
                    processor.start {
                        // start processing immediately
                        processor.trigger()
                    }
                }
            }
        } catch (ex: Exception) {
            logger.error("Failed to load data from dataStore", ex)
        }
    }

    private fun fetchRecords() {
        managerScope.launch {
            status = SourceStatusListener.Status.CONNECTED

            grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()

            coroutineScope {
                launch { processSteps() }
                launch { processHeartRate() }
                launch { processSleepStage() }
                launch { processExerciseSession() }
            }

            status = SourceStatusListener.Status.READY
        }
    }

    private suspend fun processExerciseSession() {
        processRecord<ExerciseSessionRecord, HealthConnectTypedData>(typedDataCache) { record ->
            listOf(
                healthConnectTypedData<ExerciseSessionRecord> {
                    time = record.startTime.toDouble()
                    endTime = record.endTime.toDouble()
                    timeZoneOffset = record.startZoneOffset?.totalSeconds
                        ?: record.endZoneOffset?.totalSeconds
                    metadata = record.metadata.toHealthConnectMetadata()
                    intValue = record.exerciseType
                    stringValue = record.exerciseType.toExerciseTypeString()
                }
            )

        }
    }

    private suspend fun processHeartRate() {
        processRecord<HeartRateRecord, HealthConnectTypedData>(typedDataCache) { record ->
            val recordStartTime = record.startTime.toDouble()
            val recordEndTime = record.endTime.toDouble()
            val recordOffset = record.startZoneOffset?.totalSeconds
                ?: record.endZoneOffset?.totalSeconds
            val recordMetadata = record.metadata.toHealthConnectMetadata()
            record.samples.map { sample ->
                healthConnectTypedData<HeartRateRecord> {
                    time = sample.time.toDouble()
                    startTime = recordStartTime
                    endTime = recordEndTime
                    timeZoneOffset = recordOffset
                    metadata = recordMetadata
                    unit = "bpm"
                    intValue = sample.beatsPerMinute.toInt()
                }
            }
        }
    }

    private suspend fun processSleepStage() {
        processRecord<SleepStageRecord, HealthConnectTypedData>(typedDataCache) { record ->
            listOf(
                healthConnectTypedData<StepsRecord> {
                    time = record.startTime.toDouble()
                    endTime = record.endTime.toDouble()
                    timeZoneOffset = record.startZoneOffset?.totalSeconds
                        ?: record.endZoneOffset?.totalSeconds
                    metadata = record.metadata.toHealthConnectMetadata()
                    intValue = record.stage
                    stringValue = record.stage.toSleepStageString()
                }
            )
        }
    }

    private suspend fun processSteps() {
        processRecord<StepsRecord, HealthConnectTypedData>(typedDataCache) { record ->
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
    }

    private suspend inline fun <reified T: Record, V: SpecificRecord> processRecord(
        cache: DataCache<ObservationKey, V>,
        convert: (T) -> List<V>,
    ) {
        if (T::class !in dataTypes) {
            return
        }
        val permission = HealthPermission.getReadPermission(T::class)
        if (permission !in grantedPermissions) {
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
                                    send(cache, it)
                                }
                            }
                        }
                    }
                }

                token = response.nextChangesToken
            } while (response.hasMore)

            if (token != originalToken) {
                changesTokens[T::class] = token
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
        recordingMethod = HealthConnectRecordingMethod.UNKNOWN
        // TODO: Available from version 1.1.0-alpha01
        // recordingMethod = this@toHealthConnectMetadata.recordingMethod.toRecordingMethod()

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

        private fun HealthConnectDevice.toHealthConnectDevicePreferences() = HealthConnectDevicePreferences.newBuilder().let {
            it.id = id
            if (manufacturer != null) {
                it.manufacturer = manufacturer
            }
            if (model != null) {
                it.model = model
            }
            it.type = type.ordinal
            it.build()
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

        private fun Int.toExerciseTypeString() = when (this) {
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "BADMINTON"
            ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> "BASEBALL"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "BASKETBALL"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "BIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "BIKING_STATIONARY"
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> "BOOT_CAMP"
            ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "BOXING"
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "CALISTHENICS"
            ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "CRICKET"
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "DANCING"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "ELLIPTICAL"
            ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "EXERCISE_CLASS"
            ExerciseSessionRecord.EXERCISE_TYPE_FENCING -> "FENCING"
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "FOOTBALL_AMERICAN"
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> "FOOTBALL_AUSTRALIAN"
            ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC -> "FRISBEE_DISC"
            ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "GOLF"
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> "GUIDED_BREATHING"
            ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> "GYMNASTICS"
            ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL -> "HANDBALL"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIGH_INTENSITY_INTERVAL_TRAINING"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "HIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY -> "ICE_HOCKEY"
            ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> "ICE_SKATING"
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "MARTIAL_ARTS"
            ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "PADDLING"
            ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING -> "PARAGLIDING"
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "PILATES"
            ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> "RACQUETBALL"
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "ROCK_CLIMBING"
            ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY -> "ROLLER_HOCKEY"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "ROWING"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "ROWING_MACHINE"
            ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> "RUGBY"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "RUNNING"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "RUNNING_TREADMILL"
            ExerciseSessionRecord.EXERCISE_TYPE_SAILING -> "SAILING"
            ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING -> "SCUBA_DIVING"
            ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "SKATING"
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "SKIING"
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "SNOWBOARDING"
            ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> "SNOWSHOEING"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "SOCCER"
            ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL -> "SOFTBALL"
            ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> "SQUASH"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "STAIR_CLIMBING"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "STAIR_CLIMBING_MACHINE"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "STRENGTH_TRAINING"
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "STRETCHING"
            ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "SURFING"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "SWIMMING_OPEN_WATER"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "SWIMMING_POOL"
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "TABLE_TENNIS"
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "TENNIS"
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "VOLLEYBALL"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "WALKING"
            ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO -> "WATER_POLO"
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "WEIGHTLIFTING"
            ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> "WHEELCHAIR"
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "OTHER_WORKOUT"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "YOGA"
            else -> null
        }

        private fun Int.toSleepStageString() = when (this) {
            SleepStageRecord.STAGE_TYPE_AWAKE -> "AWAKE"
            SleepStageRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
            SleepStageRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
            SleepStageRecord.STAGE_TYPE_LIGHT -> "LIGHT"
            SleepStageRecord.STAGE_TYPE_DEEP -> "DEEP"
            SleepStageRecord.STAGE_TYPE_REM -> "REM"
            SleepStageRecord.STAGE_TYPE_UNKNOWN -> "UNKNOWN"
            else -> null
        }

        data class LocalDevice(
            val manufacturer: String?,
            val model: String?,
            val type: Int
        )
    }
}

