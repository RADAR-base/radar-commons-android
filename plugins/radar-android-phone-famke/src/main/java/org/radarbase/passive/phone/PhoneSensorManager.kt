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
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.*
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class PhoneSensorManager(
    context: PhoneSensorService
) : AbstractSourceManager<PhoneSensorService, PhoneState>(context),
    SensorEventListener {

    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())

    private val mHandler = SafeHandler.getInstance("Phone sensors", THREAD_PRIORITY_BACKGROUND)
    private var wakeLock: PowerManager.WakeLock? = null

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

}
