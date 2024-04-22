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
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl.defaultImplementation
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import java.util.*
class PolarManager(
    polarService: PolarService,
    private val applicationContext: Context
) : AbstractSourceManager<PolarService, PolarState>(polarService),
    SensorEventListener {

    private val heartRateTopic: DataCache<ObservationKey, PhoneStepCount> = createCache("android_phone_step_count", PhoneStepCount())

//    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())
//    private val accelerationTopic: DataCache<ObservationKey, PolarAcceleration> = createCache("polar_acceleration", PolarAcceleration())
//    private val batteryLevelTopic: DataCache<ObservationKey, PolarBatteryLevel> = createCache("polar_battery_level", PolarBatteryLevel())
//    private val ecgTopic: DataCache<ObservationKey, PolarEcg> = createCache("polar_ecg", PolarEcg())
//    private val gyroscopeTopic: DataCache<ObservationKey, PolarGyroscope> = createCache("polar_gyroscope", PolarGyroscope())
//    private val heartRateTopic: DataCache<ObservationKey, PolarHeartRate> = createCache("polar_heart_rate", PolarHeartRate())
//    private val magnetometerTopic: DataCache<ObservationKey, PolarMagnetometer> = createCache("polar_magnetometer", PolarMagnetometer())
//    private val ppIntervalTopic: DataCache<ObservationKey, PolarPpInterval> = createCache("polar_pp_interval", PolarPpInterval())
//    private val temperatureTopic: DataCache<ObservationKey, PolarTemperature> = createCache("polar_temperature", PolarTemperature())

    private val mHandler = SafeHandler.getInstance("Polar sensors", THREAD_PRIORITY_BACKGROUND)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var api: PolarBleApi
    private var deviceId: String = "D6B33A2A"
    private var hrDisposable: Disposable? = null

    companion object {
        private const val TAG = "POLAR"

    }
    init {
        name = service.getString(R.string.polarServiceDisplayName)

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
                Log.d(TAG, "Battery level $identifier $level%")
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
            api.connectToDevice(deviceId)
        } catch (a: PolarInvalidArgument) {
            a.printStackTrace()
        }

    }

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
                            var time = (System.currentTimeMillis() / 1000).toDouble()
                            Log.d(TAG, "time: ${time}")
                            send(heartRateTopic, PhoneStepCount(time, time, sample.hr))

//                            send(heartRateTopic, PolarHeartRate(sample.hr, sample.rrsMs, time, sample.rrAvailable, sample.contactStatus, sample.contactStatusSupported))
//                            send(heartRateTopic, PolarHeartRate(sample.hr, sample.rrsMs, time, sample.rrAvailable, sample.contactStatus, sample.contactStatusSupported))

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

}


