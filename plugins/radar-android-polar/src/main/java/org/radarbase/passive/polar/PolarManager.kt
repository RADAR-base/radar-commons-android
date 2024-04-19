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
import android.widget.Toast
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

class PolarManager(
    polarService: PolarService,
    private var context: Context,
    private val handler: SafeHandler
) : AbstractSourceManager<PolarService, PolarState>(polarService)
//    PolarBleApi
//    PolarBleApiCallback,
//    SensorEventListener
{
    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())

//    private val handler = SafeHandler.getInstance("Polar sensors", THREAD_PRIORITY_BACKGROUND)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var api: PolarBleApi

    private var deviceId = "D6B33A2A"
    private var hrDisposable: Disposable? = null

    companion object {
        private const val TAG = "PolarActivity"
        private const val API_LOGGER_TAG = "API LOGGER"
        private const val PERMISSION_REQUEST_CODE = 1
    }

    init {
        name = service.getString(R.string.polarServiceDisplayName)
    }

    @SuppressLint("WakelockTimeout")
    override fun start(acceptableIds: Set<String>) {

        api = defaultImplementation(
            context,
            setOf(
                PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SDK_MODE,
                PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_H10_EXERCISE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
                PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
                PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_LED_ANIMATION
            )
        )

        // If there is need to log what is happening inside the SDK, it can be enabled like this:
        val enableSdkLogs = true
        if(enableSdkLogs) {
            api.setApiLogger { s: String -> Log.d(API_LOGGER_TAG, s) }
        }

        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected " + polarDeviceInfo.deviceId)
                logger.debug("Device connected " + polarDeviceInfo.deviceId)
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
        })

        try {
            api.connectToDevice(deviceId)
        } catch (a: PolarInvalidArgument) {
            a.printStackTrace()
        }
//        try {
//            logger.info("Polar searching for device.")
//            api.searchForDevice()
//                .observeOn(AndroidSchedulers.mainThread())
//                .subscribe(
//                    { polarDeviceInfo: PolarDeviceInfo ->
//                        Log.d(TAG, "polar device found id: " + polarDeviceInfo.deviceId + " address: " + polarDeviceInfo.address + " rssi: " + polarDeviceInfo.rssi + " name: " + polarDeviceInfo.name + " isConnectable: " + polarDeviceInfo.isConnectable)
//                    }
//                )
//        } catch (ex: Throwable) {
//            logger.info("No device found nearby")
//        }

        register()
        handler.start()
        handler.execute {
            logger.info("Authenticating Polar device")

            wakeLock = (service.getSystemService(POWER_SERVICE) as PowerManager?)?.let { pm ->
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.radarcns.polar:PolarManager")
                    .also { it.acquire() }
            }
            status = SourceStatusListener.Status.CONNECTED
        }

        logger.info("Hello world")
        send(lightTopic, PhoneLight(20.00, 20.00, 3.14f))

    }

    fun streamHR() {
        Log.d(TAG, "log Famke start streamHR")
        logger.debug(TAG, "log Famke start streamHR")
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
                            logger.debug(TAG, "HR ${sample.hr} RR ${sample.rrsMs}")
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

//    /**
//     * Class 'PhoneSensorManager' is not abstract and does not implement abstract member
//     * onSensorChanged(p0: SensorEvent!): Unit defined in android.hardware.SensorEventListener
//     */
//    override fun onSensorChanged(event: SensorEvent) {
//        // no action
//    }
//
//    /**
//     * Class 'PhoneSensorManager' is not abstract and does not implement abstract member
//     * public abstract fun onAccuracyChanged(p0: Sensor!, p1: Int): Unit defined in android.hardware.SensorEventListener
//     */
//    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
//        // no action
//    }

}
