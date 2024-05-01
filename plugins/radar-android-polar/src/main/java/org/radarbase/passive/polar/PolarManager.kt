package org.radarbase.passive.polar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.os.PowerManager
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.model.*
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.SafeHandler
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.polar.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

class PolarManager(
    polarService: PolarService,
    private val applicationContext: Context
) : AbstractSourceManager<PolarService, PolarState>(polarService) {

    private val accelerationTopic: DataCache<ObservationKey, PolarAcceleration> = createCache("android_polar_acceleration", PolarAcceleration())
    private val batteryLevelTopic: DataCache<ObservationKey, PolarBatteryLevel> = createCache("android_polar_battery_level", PolarBatteryLevel())
    private val ecgTopic: DataCache<ObservationKey, PolarEcg> = createCache("android_polar_ecg", PolarEcg())
    private val heartRateTopic: DataCache<ObservationKey, PolarHeartRate> = createCache("android_polar_heart_rate", PolarHeartRate())
    private val ppIntervalTopic: DataCache<ObservationKey, PolarPpInterval> = createCache("android_polar_pp_interval", PolarPpInterval())

    private val mHandler = SafeHandler.getInstance("Polar sensors", THREAD_PRIORITY_BACKGROUND)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var api: PolarBleApi
    private var deviceId: String? = null
    private var isDeviceConnected: Boolean = false

    private var autoConnectDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null
    private var ecgDisposable: Disposable? = null
    private var accDisposable: Disposable? = null
    private var ppiDisposable: Disposable? = null
    companion object {
        private const val TAG = "POLAR"

    }

    init {
        status = SourceStatusListener.Status.DISCONNECTED // red icon
        name = service.getString(R.string.polarDisplayName)
    }

    @SuppressLint("WakelockTimeout")
    override fun start(acceptableIds: Set<String>) {

        status = SourceStatusListener.Status.READY // blue loading
        Log.d(TAG, "RB Device name is currently $deviceId")

        disconnectToPolarSDK(deviceId)
        connectToPolarSDK()

        register()
        mHandler.start()
        mHandler.execute {
            wakeLock = (service.getSystemService(POWER_SERVICE) as PowerManager?)?.let { pm ->
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.radarcns.polar:PolarManager")
                    .also { it.acquire() }
            }
        }

    }
    fun connectToPolarSDK() {
        api = defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO
            )
        )
        api.setApiLogger { str: String -> Log.d("P-SDK", str) }
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $powered")
                if (powered == false) {
                    status = SourceStatusListener.Status.DISCONNECTED // red circle
                } else {
                    status = SourceStatusListener.Status.READY // blue loading
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected ${polarDeviceInfo.deviceId}")
                Log.d(TAG, "RB Does it come here again?")
                deviceId = polarDeviceInfo.deviceId
                name = service.getString(R.string.polarDeviceName, deviceId)

                if (deviceId != null) {
                    isDeviceConnected = true
                    status = SourceStatusListener.Status.CONNECTED // green circle
                }
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                status = SourceStatusListener.Status.CONNECTING // green dots
                Log.d(TAG, "Device connecting ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device disconnected ${polarDeviceInfo.deviceId}")
                isDeviceConnected = false
                status = SourceStatusListener.Status.DISCONNECTED // red circle

            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {

                if (isDeviceConnected) {
                    Log.d(TAG, "Feature ready $feature for $deviceId")


                    when (feature) {
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                            streamHR()
                            streamEcg()
                            streamAcc()
//                            streamPpi()
                        }

                        else -> {
                            Log.d(TAG, "No feature was ready")
                        }
                    }
                } else {
                    Log.d(TAG, "No device was connected")
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")) {
                    Log.d(TAG, "Firmware: " + identifier + " " + value.trim { it <= ' ' })
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                var batteryLevel = (level/100).toFloat()
                state.batteryLevel = batteryLevel
                Log.d(TAG, "Battery level $level%, which is $batteryLevel at " + getTime())
                send(batteryLevelTopic, PolarBatteryLevel(getTime(), getTime(), batteryLevel))
            }

        })

        try {
            if (autoConnectDisposable != null) {
                autoConnectDisposable?.dispose()
            }
            autoConnectDisposable = api.autoConnectToDevice(-60, "180D", null)
                .subscribe(
                    { Log.d(TAG, "auto connect search complete") },
                    { throwable: Throwable -> Log.e(TAG, "" + throwable.toString()) }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Could not find polar device")
        }
    }

    override fun disconnect() {
        super.disconnect()
        api.disconnectFromDevice(deviceId!!)
        api.shutDown()
    }

    fun disconnectToPolarSDK(deviceId: String?) {
        try {
            api.disconnectFromDevice(deviceId!!)
            api.shutDown()
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred during shutdown: ${e.message}")
        }
    }

    fun getTime(): Double {
        return (System.currentTimeMillis() / 1000).toDouble()
    }

    // Since in Polar sensors (H10, H9, VeritySense and OH1) the epoch time is chosen to be 2000-01-01T00:00:00Z, this function will convert
    // this to the traditional Unix epoch of 1970-01-01T00:00:00Z
    fun epoch2000NanosTo1970Seconds(epochNanos: Long): Double {
        val epochSeconds = epochNanos / 1_000_000_000.0 // Convert nanoseconds to seconds
        val epochStart = LocalDateTime.of(2000, 1, 1, 0, 0, 0).toEpochSecond(ZoneOffset.UTC)
        return epochSeconds + epochStart
    }

    fun streamHR() {
        Log.d(TAG, "log Famke start streamHR for ${deviceId}")
        val isDisposed = hrDisposable?.isDisposed ?: true
        if (isDisposed) {
            Log.d(TAG, "log Famke start streamHR isDisposed is ${isDisposed}")
            hrDisposable = deviceId?.let {
                api.startHrStreaming(it)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe { Log.d(TAG, "Subscribed to HrStreaming for ${deviceId}") }
                    .subscribe(
                        { hrData: PolarHrData ->
                            for (sample in hrData.samples) {
                                Log.d(TAG, "HeartRate data for ${deviceId}: HR ${sample.hr} time ${getTime()} R ${sample.rrsMs} rrAvailable: ${sample.rrAvailable} contactStatus: ${sample.contactStatus} contactStatusSupported: ${sample.contactStatusSupported}")
                                send(
                                    heartRateTopic,
                                    PolarHeartRate(
                                        getTime(),
                                        getTime(),
                                        sample.hr,
                                        sample.rrsMs,
                                        sample.rrAvailable,
                                        sample.contactStatus,
                                        sample.contactStatusSupported
                                    )
                                )

                            }
                        },
                        { error: Throwable ->
                            Log.e(TAG, "HR stream failed for ${deviceId}. Reason $error")
                            hrDisposable = null
                        },
                        { Log.d(TAG, "HR stream for ${deviceId} complete") }
                    )
            }
        } else {
            // NOTE stops streaming if it is "running"
            hrDisposable?.dispose()
            Log.d(TAG, "HR stream disposed")
            hrDisposable = null
        }
    }

    fun streamEcg() {
        val isDisposed = ecgDisposable?.isDisposed ?: true
        if (isDisposed) {
            val settingMap = mapOf(
                PolarSensorSetting.SettingType.SAMPLE_RATE to 130,
                PolarSensorSetting.SettingType.RESOLUTION to 14
            )
            val ecgSettings = PolarSensorSetting(settingMap)
            deviceId?.let { deviceId ->
                ecgDisposable = api.startEcgStreaming(deviceId, ecgSettings)
                    .subscribe(
                        { polarEcgData: PolarEcgData ->
                            for (data in polarEcgData.samples) {
                                Log.d(TAG, "ECG yV: ${data.voltage} timeStamp: ${data.timeStamp} currentTime: ${epoch2000NanosTo1970Seconds(data.timeStamp)}")
                                send(
                                    ecgTopic,
                                    PolarEcg(
                                        epoch2000NanosTo1970Seconds(data.timeStamp),
                                        getTime(),
                                        data.voltage
                                    )
                                )
                            }
                        },
                        { error: Throwable ->
                            Log.e(TAG, "ECG stream failed. Reason $error")
                        },
                        { Log.d(TAG, "ECG stream complete") }
                    )
            }
        } else {
            // NOTE stops streaming if it is "running"
            ecgDisposable?.dispose()
        }
    }
    fun streamAcc() {
        val isDisposed = accDisposable?.isDisposed ?: true
        if (isDisposed) {
            val settingMap = mapOf(
                PolarSensorSetting.SettingType.SAMPLE_RATE to 25, // [50, 100, 200, 25]
                PolarSensorSetting.SettingType.RESOLUTION to 16, // [16]
                PolarSensorSetting.SettingType.RANGE to 2 // [2, 4, 8]
            )
            val accSettings = PolarSensorSetting(settingMap)
            deviceId?.let { deviceId ->
                accDisposable = api.startAccStreaming(deviceId, accSettings)
                    .subscribe(
                        { polarAccelerometerData: PolarAccelerometerData ->
                            for (data in polarAccelerometerData.samples) {
                                Log.d(TAG, "ACC    x: ${data.x} y: ${data.y} z: ${data.z} timeStamp: ${data.timeStamp} time: ${getTime()}")
                                send(
                                    accelerationTopic,
                                    PolarAcceleration(
                                        getTime(),
                                        getTime(),
                                        data.x,
                                        data.y,
                                        data.z
                                    )
                                )
                            }
                        },
                        { error: Throwable ->
                            Log.e(TAG, "ACC stream failed. Reason $error")
                        },
                        {
                            Log.d(TAG, "ACC stream complete")
                        }
                    )
            }
        } else {
            // NOTE dispose will stop streaming if it is "running"
            accDisposable?.dispose()
        }
    }

    fun streamPpi() {
        val isDisposed = ppiDisposable?.isDisposed ?: true
        if (isDisposed) {
            ppiDisposable = deviceId?.let {
                api.startPpiStreaming(it)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { ppiData: PolarPpiData ->
                            for (sample in ppiData.samples) {
                                Log.d(TAG, "PPI    ppi: ${sample.ppi} blocker: ${sample.blockerBit} errorEstimate: ${sample.errorEstimate}")
                                send(
                                    ppIntervalTopic,
                                    PolarPpInterval(
                                        getTime(),
                                        getTime(),
                                        sample.blockerBit,
                                        sample.errorEstimate,
                                        sample.hr,
                                        sample.ppi,
                                        sample.skinContactStatus,
                                        sample.skinContactSupported
                                    )
                                )
                            }
                        },
                        { error: Throwable ->
                            Log.e(TAG, "PPI stream failed. Reason $error")
                        },
                        { Log.d(TAG, "PPI stream complete") }
                    )
            }
        } else {
            // NOTE dispose will stop streaming if it is "running"
            ppiDisposable?.dispose()
        }
    }

}


