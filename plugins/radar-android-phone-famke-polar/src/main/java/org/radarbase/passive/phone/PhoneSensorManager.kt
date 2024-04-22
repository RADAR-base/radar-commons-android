package org.radarbase.passive.phone

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
import android.util.SparseArray
import android.util.SparseIntArray
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarbase.android.util.SafeHandler
import android.util.Log
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.*
import org.slf4j.LoggerFactory
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
class PhoneSensorManager(
    phoneSensorService: PhoneSensorService,
    private val applicationContext: Context
    ) : AbstractSourceManager<PhoneSensorService, PhoneState>(phoneSensorService),
    SensorEventListener {

    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())

    private val mHandler = SafeHandler.getInstance("Phone sensors", THREAD_PRIORITY_BACKGROUND)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var api: PolarBleApi
    private var deviceId: String = "D6B33A2A"
    private var hrDisposable: Disposable? = null
//    private var applicationContext: Context // No longer storing it as a class property

    companion object {
        private const val TAG = "POLAR-PHONE"

    }
    init {
        name = service.getString(R.string.phoneServiceDisplayName)

    }

    @SuppressLint("WakelockTimeout")
    override fun start(acceptableIds: Set<String>) {

        register()
        mHandler.start()
        mHandler.execute {
            wakeLock = (service.getSystemService(POWER_SERVICE) as PowerManager?)?.let { pm ->
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.radarcns.phone:PhoneSensorManager")
                    .also { it.acquire() }
            }
            status = SourceStatusListener.Status.CONNECTED
        }

        send(lightTopic, PhoneLight(20.00, 20.00, 3.14f))

        Log.d(TAG, "Connecting to Polar $deviceId")
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
        api.setApiLogger { str: String -> Log.d("SDK", str) }
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
                Log.d(TAG, "feature ready $feature")

                when (feature) {
                    PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> {
                        streamHR()
                    }
                    else -> {}
                }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")) {
                    val msg = "Firmware: " + value.trim { it <= ' ' }
                    Log.d(TAG, "Firmware: " + identifier + " " + value.trim { it <= ' ' })
                }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                Log.d(TAG, "Battery level $identifier $level%")
                val batteryLevelText = "Battery level: $level%"
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
                .doOnSubscribe { Log.d(TAG, "Subscribed") }
                .doOnNext { Log.d(TAG, "Next item emitted: $it") }
                .doOnError { error -> Log.e(TAG, "Error in observable chain: $error") }
                .subscribe(
                    { hrData: PolarHrData ->
                        Log.d(TAG, "Received HR data. Sample count: ${hrData.samples.count()}")
                        Log.d(TAG, "anything" + hrData.samples.count())
                        for (sample in hrData.samples) {
                            Log.d(TAG, "HR ${sample.hr} RR ${sample.rrsMs}")


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


