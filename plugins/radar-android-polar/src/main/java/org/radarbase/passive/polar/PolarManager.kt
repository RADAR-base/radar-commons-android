package org.radarbase.passive.polar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager.*
import android.os.PowerManager
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.util.SparseIntArray
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarbase.android.util.SafeHandler
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.*
import org.radarcns.passive.polar.*
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.*
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.core.Observable
import java.util.*
class PolarManager(
    polarService: PolarService,
    private val applicationContext: Context
) : AbstractSourceManager<PolarService, PolarState>(polarService),
    SensorEventListener {

//    private val heartRateTopic: DataCache<ObservationKey, PhoneStepCount> = createCache("android_phone_step_count", PhoneStepCount())

//    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())
//    private val accelerationTopic: DataCache<ObservationKey, PolarAcceleration> = createCache("android_polar_acceleration", PolarAcceleration())
//    private val batteryLevelTopic: DataCache<ObservationKey, PolarBatteryLevel> = createCache("android_polar_battery_level", PolarBatteryLevel())
//    private val ecgTopic: DataCache<ObservationKey, PolarEcg> = createCache("android_polar_ecg", PolarEcg())
//    private val gyroscopeTopic: DataCache<ObservationKey, PolarGyroscope> = createCache("android_polar_gyroscope", PolarGyroscope())
    private val heartRateTopic: DataCache<ObservationKey, PolarHeartRate> = createCache("android_polar_heart_rate", PolarHeartRate())
//    private val magnetometerTopic: DataCache<ObservationKey, PolarMagnetometer> = createCache("android_polar_magnetometer", PolarMagnetometer())
//    private val ppIntervalTopic: DataCache<ObservationKey, PolarPpInterval> = createCache("android_polar_pp_interval", PolarPpInterval())
//    private val temperatureTopic: DataCache<ObservationKey, PolarTemperature> = createCache("android_polar_temperature", PolarTemperature())

    private val mHandler = SafeHandler.getInstance("Polar sensors", THREAD_PRIORITY_BACKGROUND)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var api: PolarBleApi
    private var deviceId: String = "Not yet found"

    private var autoConnectDisposable: Disposable? = null
    private var hrDisposable: Disposable? = null
    private var ecgDisposable: Disposable? = null
    private var accDisposable: Disposable? = null
    private var gyrDisposable: Disposable? = null
    private var magDisposable: Disposable? = null
    private var ppiDisposable: Disposable? = null
    private var ppgDisposable: Disposable? = null


    companion object {
        private const val TAG = "POLAR"

    }
    init {
        var noDeviceYet = "searching.."
        name = service.getString(R.string.polarServiceDisplayName, noDeviceYet)
    }

    @SuppressLint("WakelockTimeout")
    override fun start(acceptableIds: Set<String>) {

        register()
        mHandler.start()
        mHandler.execute {
            wakeLock = (service.getSystemService(POWER_SERVICE) as PowerManager?)?.let { pm ->
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.radarcns.polar:PolarManager")
                    .also { it.acquire() }
            }
            status = SourceStatusListener.Status.CONNECTED
        }

        Log.d(TAG, "Trying to connect to Polar $deviceId")
        api = defaultImplementation(
            applicationContext,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO)
        )
        api.setApiLogger { str: String -> Log.d("POLAR SDK", str) }
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected ${polarDeviceInfo.deviceId}")
                var deviceId = polarDeviceInfo.deviceId
                if (deviceId != null) {
                    name = service.getString(R.string.polarServiceDisplayName, deviceId)
                }

            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connecting ${polarDeviceInfo.deviceId}")
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device disconnected ${polarDeviceInfo.deviceId}")
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                Log.d(TAG, "Feature ready $feature")

                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        streamHR()
//                        streamEcg()
                        streamAcc()
//                        streamGyro()
//                        streamMag()
//                        streamPpg()
                        streamPpi()
                    }
                    else -> {}
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")) {
                    Log.d(TAG, "Firmware: " + identifier + " " + value.trim { it <= ' ' })
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
//                Log.d(TAG, "Battery level $identifier $level%")
                Log.d(TAG, "Battery level $level%" + getTime() + getDeviceTime())
//                send(batteryLevelTopic, PolarBatteryLevel(getTime(), getDeviceTime(), )


            }

            override fun hrNotificationReceived(identifier: String, data: PolarHrData.PolarHrSample) {
                //deprecated

            }

            override fun polarFtpFeatureReady(identifier: String) {
                //deprecated
            }

            override fun streamingFeaturesReady(identifier: String, features: Set<PolarBleApi.PolarDeviceDataType>) {
                //deprecated
            }

            override fun hrFeatureReady(identifier: String) {
                //deprecated
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
        }}

//            val isDisposed = scanDisposable?.isDisposed ?: true
//            if (isDisposed) {
//                scanDisposable = api.searchForDevice()
//                    .observeOn(AndroidSchedulers.mainThread())
//                    .subscribe(
//                        { polarDeviceInfo: PolarDeviceInfo ->
//                            var deviceId = polarDeviceInfo.deviceId
//                            Log.d(TAG, "polar device found id: $deviceId")
//                        })
//                if (deviceId != null) {
//                    api.connectToDevice(deviceId)
//                } else {
//                    Log.e(TAG, "No polar device found")
//                }
//            }
//        } catch (a: PolarInvalidArgument) {
//                // Handle PolarInvalidArgument exception if needed
//                a.printStackTrace()
//        }

    /**
     * Class 'PhoneSensorManager' is not abstract and does not implement abstract member
     * onSensorChanged(p0: SensorEvent!): Unit defined in android.hardware.SensorEventListener
     */
    override fun onSensorChanged(event: SensorEvent) {
        // no action
    }

    /**
     * Class 'PhoneSensorManager' is not abstract and does not implement abstract member
     * public abstract fun onAccuracyChanged(p0: Sensor!, p1: Int): Unit defined in android.hardware.SensorEventListener
     */
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // no action
    }

    fun getDeviceTime(): Single<Double> {
        return api.getLocalTime(deviceId)
            .observeOn(AndroidSchedulers.mainThread())
            .map { calendar ->
                val time = calendar.timeInMillis.toDouble()
                Log.d(TAG, "$time read from the device")
                time // Return the time value
            }
    }

    fun getTime(): Double {
        return (System.currentTimeMillis() / 1000).toDouble()
    }

    fun streamHR() {
        Log.d(TAG, "log Famke start streamHR")
        val isDisposed = hrDisposable?.isDisposed ?: true
        if (isDisposed) {
            Log.d(TAG, "log Famke start streamHR isDisposed is ${isDisposed}")
            hrDisposable = api.startHrStreaming(deviceId)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { Log.d(TAG, "Subscribed to HrStreaming") }
                .subscribe(
                    { hrData: PolarHrData ->
                        for (sample in hrData.samples) {
                            Log.d(TAG, "HeartRate data: HR ${sample.hr} RR ${sample.rrsMs} rrAvailable: ${sample.rrAvailable} contactStatus: ${sample.contactStatus} contactStatusSupported: ${sample.contactStatusSupported}")
                            Log.d(TAG, "time: ${System.currentTimeMillis() / 1000}")
                            Log.d(TAG, "time: ${getTime()}")
                            Log.d(TAG, "deviceTime: ${getDeviceTime()}")
//                            send(heartRateTopic, PhoneStepCount(getTime(), getTime(), sample.hr))

                            send(heartRateTopic, PolarHeartRate(getTime(), getTime(), sample.hr, sample.rrsMs, sample.rrAvailable, sample.contactStatus, sample.contactStatusSupported))

                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "HR stream failed. Reason $error")
                        hrDisposable = null
                    },
                    { Log.d(TAG, "HR stream complete") }
                )
        } else {
            // NOTE stops streaming if it is "running"
            hrDisposable?.dispose()
            Log.d(TAG, "HR stream disposed")
            hrDisposable = null
        }
    }

//    fun streamEcg() {
//        val isDisposed = ecgDisposable?.isDisposed ?: true
//        if (isDisposed) {
//            ecgDisposable = requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ECG)
//                .flatMap { settings: PolarSensorSetting ->
//                    api.startEcgStreaming(deviceId, settings)
//                }
//                .subscribe(
//                    { polarEcgData: PolarEcgData ->
//                        for (data in polarEcgData.samples) {
//                            Log.d(TAG, "    yV: ${data.voltage} timeStamp: ${data.timeStamp}")
//                        }
//                    },
//                    { error: Throwable ->
//                        Log.e(TAG, "ECG stream failed. Reason $error")
//                    },
//                    { Log.d(TAG, "ECG stream complete") }
//                )
//        } else {
//            // NOTE stops streaming if it is "running"
//            ecgDisposable?.dispose()
//        }
//    }

//    fun streamGyro() {
//        val isDisposed = gyrDisposable?.isDisposed ?: true
//        if (isDisposed) {
//            gyrDisposable =
//                requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.GYRO)
//                    .flatMap { settings: PolarSensorSetting ->
//                        api.startGyroStreaming(deviceId, settings)
//                    }
//                    .observeOn(AndroidSchedulers.mainThread())
//                    .subscribe(
//                        { polarGyroData: PolarGyroData ->
//                            for (data in polarGyroData.samples) {
//                                Log.d(TAG, "GYR    x: ${data.x} y: ${data.y} z: ${data.z} timeStamp: ${data.timeStamp}")
//                            }
//                        },
//                        { error: Throwable ->
//                            Log.e(TAG, "GYR stream failed. Reason $error")
//                        },
//                        { Log.d(TAG, "GYR stream complete") }
//                    )
//        } else {
//            // NOTE dispose will stop streaming if it is "running"
//            gyrDisposable?.dispose()
//        }
//    }

    fun streamAcc() {
        val isDisposed = accDisposable?.isDisposed ?: true
        if (isDisposed) {
            val settingMap = mapOf(
                PolarSensorSetting.SettingType.SAMPLE_RATE to 52,
                PolarSensorSetting.SettingType.RESOLUTION to 16,
                PolarSensorSetting.SettingType.RANGE to 8,
                PolarSensorSetting.SettingType.CHANNELS to 3
            )
            val sensorSetting = PolarSensorSetting(settingMap)

//            accDisposable = requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.ACC)
//                .flatMap { settings: PolarSensorSetting ->
//                    api.startAccStreaming(deviceId, settings)
//                    api.startAccStreaming(deviceId, settings)
//                }

            accDisposable = api.startAccStreaming(deviceId, sensorSetting)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { polarAccelerometerData: PolarAccelerometerData ->
                        for (data in polarAccelerometerData.samples) {
                            Log.d(TAG, "ACC    x: ${data.x} y: ${data.y} z: ${data.z} timeStamp: ${data.timeStamp}")
                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "ACC stream failed. Reason $error")
                    },
                    {
                        Log.d(TAG, "ACC stream complete")
                    }
                )
        } else {
            // NOTE dispose will stop streaming if it is "running"
            accDisposable?.dispose()
        }
    }

//    fun streamMag() {
//        val isDisposed = magDisposable?.isDisposed ?: true
//        if (isDisposed) {
//            magDisposable =
//                requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.MAGNETOMETER)
//                    .flatMap { settings: PolarSensorSetting ->
//                        api.startMagnetometerStreaming(deviceId, settings)
//                    }
//                    .observeOn(AndroidSchedulers.mainThread())
//                    .subscribe(
//                        { polarMagData: PolarMagnetometerData ->
//                            for (data in polarMagData.samples) {
//                                Log.d(TAG, "MAG    x: ${data.x} y: ${data.y} z: ${data.z} timeStamp: ${data.timeStamp}")
//                            }
//                        },
//                        { error: Throwable ->
//                            Log.e(TAG, "MAGNETOMETER stream failed. Reason $error")
//                        },
//                        { Log.d(TAG, "MAGNETOMETER stream complete") }
//                    )
//        } else {
//            // NOTE dispose will stop streaming if it is "running"
//            magDisposable!!.dispose()
//        }
//    }

//    fun streamPpg() {
//        val isDisposed = ppgDisposable?.isDisposed ?: true
//        if (isDisposed) {
//            ppgDisposable =
//                requestStreamSettings(deviceId, PolarBleApi.PolarDeviceDataType.PPG)
//                    .flatMap { settings: PolarSensorSetting ->
//                        api.startPpgStreaming(deviceId, settings)
//                    }
//                    .subscribe(
//                        { polarPpgData: PolarPpgData ->
//                            if (polarPpgData.type == PolarPpgData.PpgDataType.PPG3_AMBIENT1) {
//                                for (data in polarPpgData.samples) {
//                                    Log.d(TAG, "PPG    ppg0: ${data.channelSamples[0]} ppg1: ${data.channelSamples[1]} ppg2: ${data.channelSamples[2]} ambient: ${data.channelSamples[3]} timeStamp: ${data.timeStamp}")
//                                }
//                            }
//                        },
//                        { error: Throwable ->
//                            Log.e(TAG, "PPG stream failed. Reason $error")
//                        },
//                        { Log.d(TAG, "PPG stream complete") }
//                    )
//        } else {
//            // NOTE dispose will stop streaming if it is "running"
//            ppgDisposable?.dispose()
//        }
//    }

    fun streamPpi() {
        val isDisposed = ppiDisposable?.isDisposed ?: true
        if (isDisposed) {
            ppiDisposable = api.startPpiStreaming(deviceId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { ppiData: PolarPpiData ->
                        for (sample in ppiData.samples) {
                            Log.d(TAG, "PPI    ppi: ${sample.ppi} blocker: ${sample.blockerBit} errorEstimate: ${sample.errorEstimate}")
                        }
                    },
                    { error: Throwable ->
                        Log.e(TAG, "PPI stream failed. Reason $error")
                    },
                    { Log.d(TAG, "PPI stream complete") }
                )
        } else {
            // NOTE dispose will stop streaming if it is "running"
            ppiDisposable?.dispose()
        }
    }

//    private fun requestStreamSettings(identifier: String, feature: PolarBleApi.PolarDeviceDataType): Flowable<PolarSensorSetting> {
//        val availableSettings = api.requestStreamSettings(identifier, feature)
//        val allSettings = api.requestFullStreamSettings(identifier, feature)
//            .onErrorReturn { error: Throwable ->
//                Log.w(TAG, "Full stream settings are not available for feature $feature. REASON: $error")
//                PolarSensorSetting(emptyMap())
//            }
//
//        return Single.zip(availableSettings, allSettings) { available: PolarSensorSetting, all: PolarSensorSetting ->
//            if (available.settings.isEmpty()) {
//                throw Throwable("Settings are not available")
//            } else {
//                Log.d(TAG, "Feature $feature available settings ${available.settings}")
//                Log.d(TAG, "Feature $feature all settings ${all.settings}")
//                return@zip PolarSensorSetting(all.settings)
//            }
//        }
//            .toFlowable()
//            .observeOn(AndroidSchedulers.mainThread())
//    }

//    private fun requestStreamSettings(identifier: String, feature: PolarBleApi.PolarDeviceDataType): Flowable<PolarSensorSetting> {
//        val availableSettings = api.requestStreamSettings(identifier, feature)
//        val allSettings = api.requestFullStreamSettings(identifier, feature)
//            .onErrorReturn { error: Throwable ->
//                Log.w(TAG, "Full stream settings are not available for feature $feature. REASON: $error")
//                PolarSensorSetting(emptyMap())
//            }
//        return Single.zip(availableSettings, allSettings) { available: PolarSensorSetting, all: PolarSensorSetting ->
//            if (available.settings.isEmpty()) {
//                throw Throwable("Settings are not available")
//            } else {
//                Log.d(TAG, "Feature " + feature + " available settings " + available.settings)
//                Log.d(TAG, "Feature " + feature + " all settings " + all.settings)
//                return@zip android.util.Pair(available, all)
//            }
//        }
//            .observeOn(AndroidSchedulers.mainThread())
//            .toFlowable()
//            .flatMap { sensorSettings: android.util.Pair<PolarSensorSetting, PolarSensorSetting> ->
//                DialogUtility.showAllSettingsDialog(
//                    this@MainActivity,
//                    sensorSettings.first.settings,
//                    sensorSettings.second.settings
//                ).toFlowable()
//            }
//    }

}


