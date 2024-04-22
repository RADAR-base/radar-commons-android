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
import android.util.Log
import android.widget.Toast
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
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

class PhoneSensorManager(
    phoneSensorService: PhoneSensorService
    ) : AbstractSourceManager<PhoneSensorService, PhoneState>(phoneSensorService),
    SensorEventListener {

    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())

    private val mHandler = SafeHandler.getInstance("Phone sensors", THREAD_PRIORITY_BACKGROUND)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var api: PolarBleApi
    private val deviceId = "D6B33A2A"
    private var hrDisposable: Disposable? = null

    companion object {
        private const val TAG = "POLAR"
    }

    init {
        name = service.getString(R.string.phoneServiceDisplayName)
    }

    @SuppressLint("WakelockTimeout")
    override fun start(acceptableIds: Set<String>) {
        logger.info("Starting Polar")

        // Acquire a wake lock to ensure sensors run even if the device is asleep
        wakeLock = (context.getSystemService(POWER_SERVICE) as PowerManager?)?.let { pm ->
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.radarcns.phone:PhoneSensorManager")
                .apply { acquire() }
        }

        // Initialize the Polar BLE API
        api = defaultImplementation(
            applicationContext,
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

        // Optional: Enable SDK logs for debugging
        val enableSdkLogs = true
        if (enableSdkLogs) {
            api.setApiLogger { s: String -> Log.d(TAG, s) }
        }

        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                Log.d(TAG, "BluetoothStateChanged $powered")
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                Log.d(TAG, "Device connected ${polarDeviceInfo.deviceId}")
                logger.debug("Device connected ${polarDeviceInfo.deviceId}")
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
        }

        try {
            api.connectToDevice(deviceId)
        } catch (e: PolarInvalidArgument) {
            Log.e(TAG, "Failed to connect to device: ${e.message}")
            e.printStackTrace()
        }


        // Set status to connected
        status = SourceStatusListener.Status.CONNECTED
    }



    private fun streamHR() {
        Log.d(TAG, "Starting HR stream")
        hrDisposable?.dispose() // Dispose previous disposable if exists

        hrDisposable = api.startHrStreaming(deviceId)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { hrData: PolarHrData ->
                    Log.d(TAG, "Received HR data. Sample count: ${hrData.samples.count()}")
                    for (sample in hrData.samples) {
                        logger.debug(TAG, "HR ${sample.hr} RR ${sample.rrsMs}")
                        Log.d(TAG, "HR ${sample.hr} RR ${sample.rrsMs}")
                    }
                },
                { error: Throwable ->
                    Log.e(TAG, "HR stream failed. Reason: $error")
                    hrDisposable = null
                },
                { Log.d(TAG, "HR stream complete") }
            )
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Handle sensor data if needed
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // Handle accuracy changes if needed
    }


}
