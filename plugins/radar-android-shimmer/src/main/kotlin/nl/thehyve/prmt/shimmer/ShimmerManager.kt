package nl.thehyve.prmt.shimmer

import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED
import android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Message
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import com.shimmerresearch.android.Shimmer
import com.shimmerresearch.android.Shimmer.MESSAGE_PROGRESS_REPORT
import com.shimmerresearch.bluetooth.BluetoothProgressReportPerCmd
import com.shimmerresearch.bluetooth.ShimmerBluetooth.*
import com.shimmerresearch.driver.CallbackObject
import com.shimmerresearch.driver.Configuration.Shimmer3.ObjectClusterSensorName.*
import com.shimmerresearch.driver.ObjectCluster
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.BluetoothStateReceiver
import org.radarbase.android.util.BluetoothStateReceiver.Companion.bluetoothAdapter
import org.radarbase.android.util.SafeHandler
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.shimmer.Shimmer3Acceleration
import org.radarcns.passive.shimmer.Shimmer3AxisAngle
import org.radarcns.passive.shimmer.Shimmer3Barometric
import org.radarcns.passive.shimmer.Shimmer3BatteryLevel
import org.radarcns.passive.shimmer.Shimmer3Gyroscope
import org.radarcns.passive.shimmer.Shimmer3MagneticField
import org.radarcns.passive.shimmer.Shimmer3OrientationModel
import org.radarcns.passive.shimmer.Shimmer3Quaternion
import org.radarcns.passive.shimmer.Shimmer3Sensor
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.reflect.KClass

//
// Flow for selecting the target device
//
// Rules: multiple services cannot

class ShimmerManager(
    service: ShimmerService,
    private val kClass: KClass<out ShimmerService>
) : AbstractSourceManager<ShimmerService, ShimmerSourceState>(service)
{
    private var targetService: Pair<Long, KClass<out ShimmerService>>? = null
    var shimmerState: BT_STATE = BT_STATE.DISCONNECTED

    private val handler = SafeHandler(
        name = "${kClass.simpleName}-manager",
        priority = THREAD_PRIORITY_BACKGROUND,
    )

    private lateinit var shimmer: Shimmer

    private var firstTimestamp: Double? = null
    private var firstTime: Long? = null

    var samplingRate: Double = 51.2
    var enabledSensors: Set<ShimmerSensor> = emptySet()
    var address: String? = null

    private val axisAngleTopic = createCache("android_shimmer_axis_angle", Shimmer3AxisAngle())
    private val accelerationTopic = createCache("android_shimmer_acceleration", Shimmer3Acceleration())
    private val barometricTopic = createCache("android_shimmer_barometric", Shimmer3Barometric())
    private val gyroscopeTopic = createCache("android_shimmer_gyroscope", Shimmer3Gyroscope())
    private val magneticFieldTopic = createCache("android_shimmer_magnetic_field", Shimmer3MagneticField())
    private val quaternionTopic = createCache("android_shimmer_quaternion", Shimmer3Quaternion())
    private val batteryLevelTopic = createCache("android_shimmer_quaternion", Shimmer3BatteryLevel())

    private val mBluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (status == SourceStatusListener.Status.CONNECTED) {
                return
            }
            when (intent.action) {
                ACTION_DISCOVERY_STARTED, ACTION_DISCOVERY_FINISHED -> {
                    disconnect()
                }
            }
        }
    }

    init {
        name = service.getString(R.string.shimmerDisplayName)
    }

    override fun start(acceptableIds: Set<String>) {
        val address = acceptableIds.firstOrNull()
            ?: service.acceptableSources.firstNotNullOfOrNull { it.expectedSourceName ?: it.attributes["physicalId"] }
            ?: run {
                status = SourceStatusListener.Status.UNAVAILABLE
                return
            }

        val targetService = checkNotNull(ShimmerSourceState.activeDevices.compute(address) { _, clsIdx ->
            if (clsIdx == null) {
                Pair(
                    ShimmerSourceState.currentManagerIdx.incrementAndGet(),
                    kClass,
                )
            } else {
                val (_, cls) = clsIdx
                if (cls != kClass) {
                    clsIdx
                } else {
                    Pair(
                        ShimmerSourceState.currentManagerIdx.incrementAndGet(),
                        kClass,
                    )
                }
            }
        })
        if (targetService.second != kClass) {
            status = SourceStatusListener.Status.UNAVAILABLE
            return
        }

        this.targetService = targetService
        this.address = address

        if (!checkBluetoothPermission()) return

        service.registerReceiver(mBluetoothReceiver, IntentFilter().apply {
            addAction(ACTION_DISCOVERY_FINISHED)
            addAction(ACTION_DISCOVERY_STARTED)
        })

        val adapter = service.bluetoothAdapter ?: run {
            status = SourceStatusListener.Status.UNAVAILABLE
            return
        }
        if (adapter.isDiscovering) {
            status = SourceStatusListener.Status.DISCONNECTED
            return
        }

        status = SourceStatusListener.Status.READY

        handler.start()
        handler.execute {
            val enabledSensorsArray = enabledSensors.map { it.id }.toTypedArray()
            val name = shimmerName(address)
            shimmer = Shimmer(
                handler.messageHandler { msg ->
                    handleShimmerMessage(msg)
                    true
                },
                name,
                samplingRate,
                1, //
                4,
                enabledSensorsArray,
                1,
                0,
                1,
                1,
            )
            if (adapter.isDiscovering) {
                disconnect()
                return@execute
            }
            shimmer.setDefaultShimmerConfiguration()
            shimmer.connect(address, "default")
            status = SourceStatusListener.Status.CONNECTING
            logger.info("Scanning for Shimmer device {} ({}) from {}", name, address, service.javaClass.simpleName)
        }
    }

    private fun onNoBluetooth() {
        status = SourceStatusListener.Status.DISCONNECTED
    }

    fun checkBluetoothPermission(): Boolean {
        val hasPermissions = BluetoothStateReceiver.bluetoothPermissionList.all {
            service.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

        return if (hasPermissions) {
            true
        } else {
            onNoBluetooth()
            false
        }
    }


    private fun handleShimmerMessage(msg: Message) {
        when (msg.what) {
            MSG_IDENTIFIER_DATA_PACKET -> (msg.obj as? ObjectCluster)?.handleDataMessage()
            Shimmer.MESSAGE_TOAST -> logger.info("Shimmer message: {}", msg.data.getString(Shimmer.TOAST))
            MSG_IDENTIFIER_STATE_CHANGE -> (msg.obj as? ObjectCluster)?.handleStateChange()
            MESSAGE_PROGRESS_REPORT -> (msg.obj as? BluetoothProgressReportPerCmd)?.handleProgressUpdate()
            MSG_IDENTIFIER_NOTIFICATION_MESSAGE -> {
                val callback = msg.obj as? CallbackObject ?: return
                if (callback.mIndicator == NOTIFICATION_SHIMMER_FULLY_INITIALIZED) {
                    handler.execute {
                        with(shimmer) {
                            logger.info(
                                "Starting streaming with device state {} {} {} {} {}",
                                state,
                                isConnected,
                                bluetoothRadioState,
                                isSupportedBluetooth,
                                battStatusDetails.estimatedBatteryLevel
                            )
                            //Disable PC timestamps for better performance. Disabling this takes the timestamps on every full packet received instead of on every byte received.
                            enablePCTimeStamps(false)
                            // Enable the arrays data structure. Note that enabling this will disable the Multimap/FormatCluster data structureucture. Note that enabling this will disable the Multimap/FormatCluster data structure
                            enableArraysDataStructure(true)
                            startStreaming()
                        }
                    }
                }
            }
            else -> logger.info("Got unknown Shimmer message type {}", msg.what.toMessageIdentifier())
        }
    }

    private fun BluetoothProgressReportPerCmd.handleProgressUpdate() = handler.execute {
        logger.info("Bluetooth progress: cmd completed={}, cmd in buffer={}", mCommandCompleted, mNumberofRemainingCMDsInBuffer)
        with(shimmer) {
            logger.info(
                "Device state {} {} {} {} {}",
                state,
                isConnected,
                bluetoothRadioState,
                isSupportedBluetooth,
                battStatusDetails.estimatedBatteryLevel
            )
        }
    }

    private fun ObjectCluster.asString(): String = buildString {
        append("ObjectCluster{")
        append("macaddress=")
        append(macAddress)
        append(", mState=")
        append(mState)
        append(", sensorList=")
        append(mSensorDataList)
        append(", sysTimestamp=")
        append(mSystemTimeStamp)
        append(", timestamp=")
        append(timestampMilliSecs)
        append('}')
    }

    private fun ObjectCluster.handleStateChange() = handler.execute {
        logger.info("State metadata: {}", asString())
        with(shimmer) {
            logger.info(
                "Device state {} {} {} {} {}",
                state,
                isConnected,
                bluetoothRadioState,
                isSupportedBluetooth,
                battStatusDetails.estimatedBatteryLevel
            )
        }

        if (mState == shimmerState) {
            return@execute
        }

        shimmerState = mState

        when (shimmerState) {
            BT_STATE.CONNECTED -> {
                register(
                    physicalId = macAddress,
                    name = shimmerName,
                )
            }
            BT_STATE.STREAMING -> status = SourceStatusListener.Status.CONNECTED
            BT_STATE.DISCONNECTED -> disconnect()
            else -> logger.debug("Shimmer state {}", state)
        }
    }

    private val ObjectCluster.calibratedData: Map<ShimmerChannel, Double>
        get() = EnumMap<ShimmerChannel, Double>(ShimmerChannel::class.java).apply {
            sensorDataArray.mSensorNames.forEachIndexed { index, s ->
                s ?: return@forEachIndexed
                val channel = ShimmerChannel.findByLabel(s) ?: return@forEachIndexed
                put(channel, sensorDataArray.mCalData[index])
            }
        }

    private fun ObjectCluster.handleDataMessage() = handler.execute {
        val data = calibratedData
        logger.info("Got data for {}", data.keys)

        val now = currentTime
        val itemTime = if (firstTimestamp == null) {
            firstTimestamp = timestampMilliSecs
            firstTime = System.currentTimeMillis()
            firstTime!!.toDouble()
        } else {
            firstTime!! + timestampMilliSecs - firstTimestamp!!
        } / 1000.0

        batteryClusters.firstNotNullOfOrNull { data[it] }
            ?.let {
                state.batteryLevel = it.toFloat()
                send(batteryLevelTopic, Shimmer3BatteryLevel(itemTime, now, state.batteryLevel))
            }

        accelerationClusters.forEach { (sensor, cluster) ->
            cluster.sendData3(data, accelerationTopic) { x, y, z ->
                Shimmer3Acceleration(
                    itemTime,
                    now,
                    sensor,
                    x.toFloat(),
                    y.toFloat(),
                    z.toFloat(),
                )
            }
        }

        axisAngleClusters.forEach { (orientation, cluster) ->
            cluster.sendData4(data, axisAngleTopic) { angle, x, y, z ->
                Shimmer3AxisAngle(
                    itemTime,
                    now,
                    orientation,
                    angle.toFloat(),
                    x.toFloat(),
                    y.toFloat(),
                    z.toFloat(),
                )
            }
        }

        gyroClusters.forEach { (sensor, cluster) ->
            cluster.sendData3(data, gyroscopeTopic) { x, y, z ->
                Shimmer3Gyroscope(
                    itemTime,
                    now,
                    sensor,
                    x.toFloat(),
                    y.toFloat(),
                    z.toFloat(),
                )
            }
        }

        magnetoMeterClusters.forEach { (sensor, cluster) ->
            cluster.sendData3(data, magneticFieldTopic) { x, y, z ->
                Shimmer3MagneticField(
                    itemTime,
                    now,
                    sensor,
                    x.toFloat(),
                    y.toFloat(),
                    z.toFloat(),
                )
            }
        }

        barometricClusters.forEach { (sensor, cluster) ->
            cluster.sendData2(data, barometricTopic) { temperature, pressure ->
                Shimmer3Barometric(
                    itemTime,
                    now,
                    sensor,
                    pressure.toFloat(),
                    temperature.toFloat(),
                )
            }
        }

        quaternionClusters.forEach { (orientation, sensorClusters) ->
            sensorClusters.forEach { (sensor, cluster) ->
                cluster.sendData4(data, quaternionTopic) { w, x, y, z ->
                    Shimmer3Quaternion(
                        itemTime,
                        now,
                        sensor,
                        orientation,
                        w.toFloat(),
                        x.toFloat(),
                        y.toFloat(),
                        z.toFloat(),
                    )
                }
            }
        }
    }

    override fun onClose() {
        val address = address
        val targetService = targetService
        if (address != null && targetService != null) {
            ShimmerSourceState.activeDevices.remove(address, targetService)
        }

        try {
            service.unregisterReceiver(mBluetoothReceiver)
        } catch (ex: Exception) {
            logger.debug("Failed to unregister bluetooth receiver")
        }

        handler.stop {
            with(shimmer) {
                try {
                    stopStreaming()
                    disconnect()
                } catch (ex: Exception) {
                    logger.error("Failed to disconnect from Shimmer device", ex)
                }
            }
        }
    }

    private fun shimmerName(address: String): String =
        "Shimmer-${address.substring(12 .. 13)}${address.substring(15 .. 16)}"


    private inline fun <T: SpecificRecord> Array<ShimmerChannel>.sendData2(
        data: Map<ShimmerChannel, Double>,
        topic: DataCache<ObservationKey, T>,
        block: (Double, Double) -> T,
    ) {
        send(
            topic,
            block(
                data[this[0]] ?: return,
                data[this[1]] ?: return,
            ),
        )
    }

    private inline fun <T: SpecificRecord> Array<ShimmerChannel>.sendData3(
        data: Map<ShimmerChannel, Double>,
        topic: DataCache<ObservationKey, T>,
        block: (Double, Double, Double) -> T,
    ) {
        send(
            topic,
            block(
                data[this[0]] ?: return,
                data[this[1]] ?: return,
                data[this[2]] ?: return,
            )
        )
    }

    private inline fun <T: SpecificRecord> Array<ShimmerChannel>.sendData4(
        data: Map<ShimmerChannel, Double>,
        topic: DataCache<ObservationKey, T>,
        block: (Double, Double, Double, Double) -> T,
    ) {
        send(
            topic,
            block(
                data[this[0]] ?: return,
                data[this[1]] ?: return,
                data[this[2]] ?: return,
                data[this[3]] ?: return,
            )
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ShimmerManager::class.java)

        private val axisAngleClusters = enumMapOf(
            Shimmer3OrientationModel.SIX_DOF to arrayOf(
                ShimmerChannel.AXIS_ANGLE_6DOF_A,
                ShimmerChannel.AXIS_ANGLE_6DOF_X,
                ShimmerChannel.AXIS_ANGLE_6DOF_Y,
                ShimmerChannel.AXIS_ANGLE_6DOF_Z
            ),
            Shimmer3OrientationModel.NINE_DOF to arrayOf(
                ShimmerChannel.AXIS_ANGLE_9DOF_A,
                ShimmerChannel.AXIS_ANGLE_9DOF_X,
                ShimmerChannel.AXIS_ANGLE_9DOF_Y,
                ShimmerChannel.AXIS_ANGLE_9DOF_Z
            ),
        ).toEnumMap()

        private val accelerationClusters = enumMapOf(
            Shimmer3Sensor.LN to arrayOf(
                ShimmerChannel.ACCEL_LN_X,
                ShimmerChannel.ACCEL_LN_Y,
                ShimmerChannel.ACCEL_LN_Z,
            ),
            Shimmer3Sensor.WR to arrayOf(
                ShimmerChannel.ACCEL_WR_X,
                ShimmerChannel.ACCEL_WR_Y,
                ShimmerChannel.ACCEL_WR_Z,
            ),
            Shimmer3Sensor.MPU to arrayOf(
                ShimmerChannel.ACCEL_MPU_X,
                ShimmerChannel.ACCEL_MPU_Y,
                ShimmerChannel.ACCEL_MPU_Z,
            ),
            Shimmer3Sensor.MPL to arrayOf(
                ShimmerChannel.ACCEL_MPU_MPL_X,
                ShimmerChannel.ACCEL_MPU_MPL_Y,
                ShimmerChannel.ACCEL_MPU_MPL_Z,
            ),
        )
        private val gyroClusters = enumMapOf(
            Shimmer3Sensor.MPL to arrayOf(
                ShimmerChannel.GYRO_MPU_MPL_X,
                ShimmerChannel.GYRO_MPU_MPL_Y,
                ShimmerChannel.GYRO_MPU_MPL_Z,
            ),
            Shimmer3Sensor.DEFAULT to arrayOf(
                ShimmerChannel.GYRO_X,
                ShimmerChannel.GYRO_Y,
                ShimmerChannel.GYRO_Z,
            ),
        )
        private val principalAxisClusters = enumMapOf(
            Shimmer3OrientationModel.SIX_DOF to arrayOf(
                ShimmerChannel.EULER_6DOF_YAW,
                ShimmerChannel.EULER_6DOF_PITCH,
                ShimmerChannel.EULER_6DOF_ROLL,
            ),
            Shimmer3OrientationModel.NINE_DOF to arrayOf(
                ShimmerChannel.EULER_9DOF_YAW,
                ShimmerChannel.EULER_9DOF_PITCH,
                ShimmerChannel.EULER_9DOF_ROLL,
            ),
        )
        private val quaternionClusters = enumMapOf(
            Shimmer3OrientationModel.SIX_DOF to enumMapOf(
                Shimmer3Sensor.MPL to arrayOf(
                    ShimmerChannel.QUAT_MPL_6DOF_W,
                    ShimmerChannel.QUAT_MPL_6DOF_X,
                    ShimmerChannel.QUAT_MPL_6DOF_Y,
                    ShimmerChannel.QUAT_MPL_6DOF_Z,
                ),

                Shimmer3Sensor.DMP to arrayOf(
                    ShimmerChannel.QUAT_DMP_6DOF_W,
                    ShimmerChannel.QUAT_DMP_6DOF_X,
                    ShimmerChannel.QUAT_DMP_6DOF_Y,
                    ShimmerChannel.QUAT_DMP_6DOF_Z,
                ),
                Shimmer3Sensor.MADGEWICK to arrayOf(
                    ShimmerChannel.QUAT_MADGE_6DOF_W,
                    ShimmerChannel.QUAT_MADGE_6DOF_X,
                    ShimmerChannel.QUAT_MADGE_6DOF_Y,
                    ShimmerChannel.QUAT_MADGE_6DOF_Z,
                ),
            ),
            Shimmer3OrientationModel.NINE_DOF to mapOf(
                Shimmer3Sensor.MPL to arrayOf(
                    ShimmerChannel.QUAT_MPL_9DOF_W,
                    ShimmerChannel.QUAT_MPL_9DOF_X,
                    ShimmerChannel.QUAT_MPL_9DOF_Y,
                    ShimmerChannel.QUAT_MPL_9DOF_Z,
                ),
                Shimmer3Sensor.MADGEWICK to arrayOf(
                    ShimmerChannel.QUAT_MADGE_9DOF_W,
                    ShimmerChannel.QUAT_MADGE_9DOF_X,
                    ShimmerChannel.QUAT_MADGE_9DOF_Y,
                    ShimmerChannel.QUAT_MADGE_9DOF_Z,
                ),
            )
        )
        private val eulerClusters = enumMapOf(
            Shimmer3OrientationModel.SIX_DOF to arrayOf(
                ShimmerChannel.EULER_MPL_6DOF_X,
                ShimmerChannel.EULER_MPL_6DOF_Y,
                ShimmerChannel.EULER_MPL_6DOF_Z,
            ),
            Shimmer3OrientationModel.NINE_DOF to arrayOf(
                ShimmerChannel.EULER_MPL_9DOF_X,
                ShimmerChannel.EULER_MPL_9DOF_Y,
                ShimmerChannel.EULER_MPL_9DOF_Z,
            )
        )

        inline fun <reified K: Enum<K>, V> Map<K, V>.toEnumMap() = EnumMap<K, V>(K::class.java).apply {
            putAll(this@toEnumMap)
        }

        inline fun <reified K: Enum<K>, V> enumMapOf(vararg pairs: Pair<K, V>): EnumMap<K, V> =
            EnumMap<K, V>(K::class.java).apply {
                pairs.forEach { (k, v) -> put(k, v) }
            }

        private val mplClusters = arrayOf(
            MPL_HEADING,
            MPL_TEMPERATURE,
            MPL_PEDOM_CNT,
            MPL_PEDOM_TIME,
        )

        private val batteryClusters = arrayOf(
            ShimmerChannel.BATT_PERCENTAGE,
        )
        private val extExpCluster = arrayOf(
            EXT_EXP_ADC_A7,
            EXT_EXP_ADC_A6,
            EXT_EXP_ADC_A15,
        )
        private val intExpCluster = arrayOf(
            INT_EXP_ADC_A1,
            INT_EXP_ADC_A12,
            INT_EXP_ADC_A13,
            INT_EXP_ADC_A14,
        )
        private val bridgeAmpCluster = arrayOf(
            BRIDGE_AMP_HIGH,
            BRIDGE_AMP_LOW,
            RESISTANCE_AMP,
        )
        private val skinTemperatureCluster = arrayOf(
            SKIN_TEMPERATURE_PROBE,
        )
        private val metadataCluster = arrayOf(
            FREQUENCY,
        )
        private val miscClusters = arrayOf(
            TAPDIRANDTAPCNT,
            MOTIONANDORIENT,
            EVENT_MARKER,
        )
        private val magnetoMeterClusters = mapOf(
            Shimmer3Sensor.DEFAULT to arrayOf(
                ShimmerChannel.MAG_X,
                ShimmerChannel.MAG_Y,
                ShimmerChannel.MAG_Z,
            ),
            Shimmer3Sensor.MPU to arrayOf(
                ShimmerChannel.MAG_MPU_X,
                ShimmerChannel.MAG_MPU_Y,
                ShimmerChannel.MAG_MPU_Z,
            ),
            Shimmer3Sensor.MPL to arrayOf(
                ShimmerChannel.MAG_MPU_MPL_X,
                ShimmerChannel.MAG_MPU_MPL_Y,
                ShimmerChannel.MAG_MPU_MPL_Z,
            ),
        )

        private val barometricClusters = mapOf(
            Shimmer3Sensor.BMP180 to arrayOf(
                ShimmerChannel.TEMPERATURE_BMP180,
                ShimmerChannel.PRESSURE_BMP180,
            ),
            Shimmer3Sensor.BMP280 to arrayOf(
                ShimmerChannel.TEMPERATURE_BMP280,
                ShimmerChannel.PRESSURE_BMP280,
            ),
        )

        enum class ShimmerChannel(val label: String) {
            BATT_PERCENTAGE("Batt_Percentage"),
            AXIS_ANGLE_6DOF_A("Axis_Angle_6DOF_A"),
            AXIS_ANGLE_6DOF_X("Axis_Angle_6DOF_X"),
            AXIS_ANGLE_6DOF_Y("Axis_Angle_6DOF_Y"),
            AXIS_ANGLE_6DOF_Z("Axis_Angle_6DOF_Z"),
            AXIS_ANGLE_9DOF_A("Axis_Angle_9DOF_A"),
            AXIS_ANGLE_9DOF_X("Axis_Angle_9DOF_X"),
            AXIS_ANGLE_9DOF_Y("Axis_Angle_9DOF_Y"),
            AXIS_ANGLE_9DOF_Z("Axis_Angle_9DOF_Z"),
            ACCEL_LN_X("Accel_LN_X"),
            ACCEL_LN_Y("Accel_LN_Y"),
            ACCEL_LN_Z("Accel_LN_Z"),
            ACCEL_WR_X("Accel_WR_X"),
            ACCEL_WR_Y("Accel_WR_Y"),
            ACCEL_WR_Z("Accel_WR_Z"),
            ACCEL_MPU_X("Accel_MPU_X"),
            ACCEL_MPU_Y("Accel_MPU_Y"),
            ACCEL_MPU_Z("Accel_MPU_Z"),
            ACCEL_MPU_MPL_X("Accel_MPU_MPL_X"),
            ACCEL_MPU_MPL_Y("Accel_MPU_MPL_Y"),
            ACCEL_MPU_MPL_Z("Accel_MPU_MPL_Z"),
            GYRO_MPU_MPL_X("Gyro_MPU_MPL_X"),
            GYRO_MPU_MPL_Y("Gyro_MPU_MPL_Y"),
            GYRO_MPU_MPL_Z("Gyro_MPU_MPL_Z"),
            GYRO_X("Gyro_X"),
            GYRO_Y("Gyro_Y"),
            GYRO_Z("Gyro_Z"),
            EULER_6DOF_YAW("Euler_6DOF_Yaw"),
            EULER_6DOF_PITCH("Euler_6DOF_Pitch"),
            EULER_6DOF_ROLL("Euler_6DOF_Roll"),
            EULER_9DOF_YAW("Euler_9DOF_Yaw"),
            EULER_9DOF_PITCH("Euler_9DOF_Pitch"),
            EULER_9DOF_ROLL("Euler_9DOF_Roll"),
            QUAT_MPL_6DOF_W("Quat_MPL_6DOF_W"),
            QUAT_MPL_6DOF_X("Quat_MPL_6DOF_X"),
            QUAT_MPL_6DOF_Y("Quat_MPL_6DOF_Y"),
            QUAT_MPL_6DOF_Z("Quat_MPL_6DOF_Z"),
            QUAT_MPL_9DOF_W("Quat_MPL_9DOF_W"),
            QUAT_MPL_9DOF_X("Quat_MPL_9DOF_X"),
            QUAT_MPL_9DOF_Y("Quat_MPL_9DOF_Y"),
            QUAT_MPL_9DOF_Z("Quat_MPL_9DOF_Z"),
            QUAT_DMP_6DOF_W("Quat_DMP_6DOF_W"),
            QUAT_DMP_6DOF_X("Quat_DMP_6DOF_X"),
            QUAT_DMP_6DOF_Y("Quat_DMP_6DOF_Y"),
            QUAT_DMP_6DOF_Z("Quat_DMP_6DOF_Z"),
            QUAT_MADGE_6DOF_W("Quat_Madge_6DOF_W"),
            QUAT_MADGE_6DOF_X("Quat_Madge_6DOF_X"),
            QUAT_MADGE_6DOF_Y("Quat_Madge_6DOF_Y"),
            QUAT_MADGE_6DOF_Z("Quat_Madge_6DOF_Z"),
            QUAT_MADGE_9DOF_W("Quat_Madge_9DOF_W"),
            QUAT_MADGE_9DOF_X("Quat_Madge_9DOF_X"),
            QUAT_MADGE_9DOF_Y("Quat_Madge_9DOF_Y"),
            QUAT_MADGE_9DOF_Z("Quat_Madge_9DOF_Z"),
            EULER_MPL_6DOF_X("Euler_MPL_6DOF_X"),
            EULER_MPL_6DOF_Y("Euler_MPL_6DOF_Y"),
            EULER_MPL_6DOF_Z("Euler_MPL_6DOF_Z"),
            EULER_MPL_9DOF_X("Euler_MPL_9DOF_X"),
            EULER_MPL_9DOF_Y("Euler_MPL_9DOF_Y"),
            EULER_MPL_9DOF_Z("Euler_MPL_9DOF_Z"),
            TEMPERATURE_BMP180("Temperature_BMP180"),
            PRESSURE_BMP180("Temperature_BMP180"),
            TEMPERATURE_BMP280("Temperature_BMP280"),
            PRESSURE_BMP280("Temperature_BMP280"),
            MAG_X("Mag_X"),
            MAG_Y("Mag_Y"),
            MAG_Z("Mag_Z"),
            MAG_MPU_X("Mag_MPU_X"),
            MAG_MPU_Y("Mag_MPU_Y"),
            MAG_MPU_Z("Mag_MPU_Z"),
            MAG_MPU_MPL_X("Mag_MPU_MPL_X"),
            MAG_MPU_MPL_Y("Mag_MPU_MPL_Y"),
            MAG_MPU_MPL_Z("Mag_MPU_MPL_Z"),
            ;

            companion object {
                private val channelLabels = buildMap(ShimmerChannel.values().size) {
                    ShimmerChannel.values().forEach { put(it.label, it) }
                }
                fun findByLabel(label: String): ShimmerChannel? = channelLabels[label]
            }
        }

        fun Int.toMessageIdentifier(): String = when (this) {
            MSG_IDENTIFIER_DATA_PACKET -> "MSG_IDENTIFIER_DATA_PACKET"
            MSG_IDENTIFIER_DEVICE_PAIRED -> "MSG_IDENTIFIER_DEVICE_PAIRED"
            MSG_IDENTIFIER_DEVICE_UNPAIRED -> "MSG_IDENTIFIER_DEVICE_UNPAIRED"
            MSG_IDENTIFIER_NOTIFICATION_MESSAGE -> "MSG_IDENTIFIER_NOTIFICATION_MESSAGE"
            MSG_IDENTIFIER_PACKET_RECEPTION_RATE_OVERALL -> "MSG_IDENTIFIER_PACKET_RECEPTION_RATE_OVERALL"
            MSG_IDENTIFIER_PACKET_RECEPTION_RATE_CURRENT -> "MSG_IDENTIFIER_PACKET_RECEPTION_RATE_CURRENT"
            MSG_IDENTIFIER_PROGRESS_REPORT_ALL -> "MSG_IDENTIFIER_PROGRESS_REPORT_ALL"
            MSG_IDENTIFIER_PROGRESS_REPORT_PER_DEVICE -> "MSG_IDENTIFIER_PROGRESS_REPORT_PER_DEVICE"
            MSG_IDENTIFIER_SHIMMER_DOCKED_STATE_CHANGE -> "MSG_IDENTIFIER_SHIMMER_DOCKED_STATE_CHANGE"
            MSG_IDENTIFIER_PROGRESS_BT_PAIR_UNPAIR_ALL -> "MSG_IDENTIFIER_PROGRESS_BT_PAIR_UNPAIR_ALL"
            MSG_IDENTIFIER_PROGRESS_BT_PAIR_UNPAIR_PER_DEVICE -> "MSG_IDENTIFIER_PROGRESS_BT_PAIR_UNPAIR_PER_DEVICE"
            MSG_IDENTIFIER_PROGRESS_BT_DISCOVERY_RESULTS -> "MSG_IDENTIFIER_PROGRESS_BT_DISCOVERY_RESULTS"
            MSG_IDENTIFIER_STATE_CHANGE -> "MSG_IDENTIFIER_STATE_CHANGE"
            MSG_IDENTIFIER_DEVICE_ERROR -> "MSG_IDENTIFIER_DEVICE_ERROR"
            MESSAGE_PROGRESS_REPORT -> "MESSAGE_PROGRESS_REPORT"
            else -> "UNKNOWN"
        }
    }
}
