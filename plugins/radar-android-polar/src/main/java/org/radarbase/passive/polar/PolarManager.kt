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
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.*
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
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
    private val ppIntervalTopic: DataCache<ObservationKey, PolarPpInterval> = createCache("android_polar_pulse_to_pulse_interval", PolarPpInterval())
    private val ppgTopic: DataCache<ObservationKey, PolarPpg> = createCache("android_polar_ppg", PolarPpg())

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
    private var ppgDisposable: Disposable? = null
    private var timeDisposable: Disposable? = null

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
        Log.d(TAG, "Polar Device is $deviceId")

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

        Log.d(TAG, "Connecting to Polar API")
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
                deviceId = polarDeviceInfo.deviceId
                name = polarDeviceInfo.name

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

                    if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP) {
                        setDeviceTime(deviceId)
                    }

                    when (feature) {
                        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                            streamHR()
                            streamEcg()
                            streamAcc()
                            streamPpi()
                            streamPpg()
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
                var batteryLevel = level.toFloat() / 100.0f
                state.batteryLevel = batteryLevel
                Log.d(TAG, "Battery level $level%, which is $batteryLevel at " + currentTime)
                send(batteryLevelTopic, PolarBatteryLevel(name, currentTime, currentTime, batteryLevel))
            }

        })

        try {
            if (autoConnectDisposable != null) {
                autoConnectDisposable?.dispose()
            }
            autoConnectDisposable = api.autoConnectToDevice(-60, "180D", null)
                .subscribe(
                    { Log.d(TAG, "auto connect search complete") },
                    { throwable: Throwable ->
                        Log.e(TAG, "" + throwable.toString())
                    }
                )
        } catch (e: Exception) {
            Log.e(TAG, "Could not find polar device")
        }
    }


    fun disconnectToPolarSDK(deviceId: String?) {
        try {
            api.disconnectFromDevice(deviceId!!)
            api.shutDown()
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred during shutdown: ${e.message}")
        }
    }

    fun setDeviceTime(deviceId: String?) {
        deviceId?.let { id ->
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
            calendar.time = Date()
            api.setLocalTime(id, calendar)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        val timeSetString = "Time ${calendar.time} set to device"
                        Log.d(TAG, timeSetString)
                    },
                    { error: Throwable -> Log.e(TAG, "Set time failed: $error") }
                )
        } ?: run {
            Log.e(TAG, "Device ID is null. Cannot set device time.")
        }
    }

    fun getTimeNano(): Double {
        var nano = (System.currentTimeMillis() * 1_000_000L).toDouble()
        return nano/1000_000_000L
    }

    fun streamHR() {
        Log.d(TAG, "start streamHR for ${deviceId}")
        val isDisposed = hrDisposable?.isDisposed ?: true
        if (isDisposed) {
            hrDisposable = deviceId?.let {
                api.startHrStreaming(it)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSubscribe { Log.d(TAG, "Subscribed to HrStreaming for ${deviceId}") }
                    .subscribe(
                        { hrData: PolarHrData ->
                            for (sample in hrData.samples) {
                                Log.d(TAG, "HeartRate data for ${name}, ${deviceId}: HR ${sample.hr} timeStamp: ${getTimeNano()} currentTime: ${currentTime} R ${sample.rrsMs} rrAvailable: ${sample.rrAvailable} contactStatus: ${sample.contactStatus} contactStatusSupported: ${sample.contactStatusSupported}")
                                send(
                                    heartRateTopic,
                                    PolarHeartRate(
                                        name,
                                        getTimeNano(),
                                        currentTime,
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
            hrDisposable?.dispose()
            Log.d(TAG, "HR stream disposed")
            hrDisposable = null
        }
    }

    fun streamEcg() {
        Log.d(TAG, "start streamECG for ${deviceId}")
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
                                Log.d(TAG, "ECG yV: ${data.voltage} timeStamp: ${PolarUtils.convertEpochPolarToUnixEpoch(data.timeStamp)} currentTime: ${currentTime} PolarTimeStamp: ${data.timeStamp}")
                                send(
                                    ecgTopic,
                                    PolarEcg(
                                        name,
                                        PolarUtils.convertEpochPolarToUnixEpoch(data.timeStamp),
                                        currentTime,
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
                                Log.d(TAG, "ACC    x: ${data.x} y: ${data.y} z: ${data.z} timeStamp: ${PolarUtils.convertEpochPolarToUnixEpoch(data.timeStamp)} currentTime: ${currentTime} PolarTimeStamp: ${data.timeStamp}")
                                send(
                                    accelerationTopic,
                                    PolarAcceleration(
                                        name,
                                        PolarUtils.convertEpochPolarToUnixEpoch(data.timeStamp),
                                        currentTime,
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
            accDisposable?.dispose()
        }
    }

    fun streamPpg() {
        Log.d(TAG, "start streamPpg for ${deviceId}")
        val isDisposed = ppgDisposable?.isDisposed ?: true
        if (isDisposed) {
            val settingMap = mapOf(
                PolarSensorSetting.SettingType.SAMPLE_RATE to 55,
                PolarSensorSetting.SettingType.RESOLUTION to 22,
                PolarSensorSetting.SettingType.CHANNELS to 4
            )
            val ppgSettings = PolarSensorSetting(settingMap)
            deviceId?.let { deviceId ->
                ppgDisposable = api.startPpgStreaming(deviceId, ppgSettings)
                    .subscribe(
                        { polarPpgData: PolarPpgData ->
                            if (polarPpgData.type == PolarPpgData.PpgDataType.PPG3_AMBIENT1) {
                                for (data in polarPpgData.samples) {
                                    Log.d(TAG, "PPG    ppg0: ${data.channelSamples[0]} ppg1: ${data.channelSamples[1]} ppg2: ${data.channelSamples[2]} ambient: ${data.channelSamples[3]} timeStamp: ${PolarUtils.convertEpochPolarToUnixEpoch(data.timeStamp)} currentTime: ${currentTime} PolarTimeStamp: ${data.timeStamp}")
                                    send(
                                        ppgTopic,
                                        PolarPpg(
                                            name,
                                            PolarUtils.convertEpochPolarToUnixEpoch(data.timeStamp),
                                            currentTime,
                                            data.channelSamples[0],
                                            data.channelSamples[1],
                                            data.channelSamples[2],
                                            data.channelSamples[3]
                                        )
                                    )
                                }
                            }
                        },
                        { error: Throwable ->
                            Log.e(TAG, "ECG stream failed. Reason $error")
                        },
                        { Log.d(TAG, "ECG stream complete") }
                    )
            }
        } else {
            ecgDisposable?.dispose()
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
                                Log.d(TAG, "PPI    ppi: ${sample.ppi} blocker: ${sample.blockerBit} errorEstimate: ${sample.errorEstimate} currentTime: ${currentTime}")
                                send(
                                    ppIntervalTopic,
                                    PolarPpInterval(
                                        name,
                                        currentTime,
                                        currentTime,
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
            ppiDisposable?.dispose()
        }
    }

}


