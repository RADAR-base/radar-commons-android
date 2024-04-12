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

class PhoneSensorManager(context: PhoneSensorService) : AbstractSourceManager<PhoneSensorService, PhoneState>(context), SensorEventListener {

    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())

    private val sensorSendStates = SparseArray<SensorSendState>()

    private val mHandler = SafeHandler.getInstance("Phone sensors", THREAD_PRIORITY_BACKGROUND)

    private var lastStepCount = -1
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

        send(lightTopic, PhoneLight(10.00, 10.00, 3.14f))

    }

    override fun onSensorChanged(event: SensorEvent) {
        // no action
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // no action
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PhoneSensorManager::class.java)

        // Sensors to register, together with the name of the sensor
        private val SENSOR_TYPES_TO_REGISTER = intArrayOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_STEP_COUNTER,
        )

        private fun Int.toSensorName(): String = when (this) {
            Sensor.TYPE_ACCELEROMETER  -> Sensor.STRING_TYPE_ACCELEROMETER
            Sensor.TYPE_LIGHT          -> Sensor.STRING_TYPE_LIGHT
            Sensor.TYPE_MAGNETIC_FIELD -> Sensor.STRING_TYPE_MAGNETIC_FIELD
            Sensor.TYPE_GYROSCOPE      -> Sensor.STRING_TYPE_GYROSCOPE
            Sensor.TYPE_STEP_COUNTER   -> Sensor.STRING_TYPE_STEP_COUNTER
            else -> "unknown"
        }

        private fun Int.toBatteryStatus(): BatteryStatus = when (this) {
            BATTERY_STATUS_UNKNOWN      -> BatteryStatus.UNKNOWN
            BATTERY_STATUS_CHARGING     -> BatteryStatus.CHARGING
            BATTERY_STATUS_DISCHARGING  -> BatteryStatus.DISCHARGING
            BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
            BATTERY_STATUS_FULL         -> BatteryStatus.FULL
            else -> BatteryStatus.UNKNOWN
        }

        private const val ACTIVITY_LAUNCH_WAKE = "org.radarbase.passive.phone.PhoneSensorManager.ACTIVITY_LAUNCH_WAKE"
        private const val REQUEST_CODE_PENDING_INTENT = 482480668

        private fun SparseIntArray.contentsEquals(other: SparseIntArray): Boolean {
            return size() == other.size()
                    && (0 until size()).all { keyAt(it) == other.keyAt(it) && valueAt(it) == other.valueAt(it) }
        }

        private inline fun <T> SparseArray<T>.computeIfAbsent(key: Int, compute: () -> T) = get(key)
            ?: compute().also { put(key, it) }

        data class SensorSendState(
            var lastSendTime: Long = 0,
            var postponedTime: Double? = null,
            var postponedEvent: SensorEvent? = null,
            var postponeFuture: SafeHandler.HandlerFuture? = null,
        ) {
            /**
             * Whether the previous interval has ended. The interval is measured as [delay]
             * milliseconds. The interval starts when the first event in an interval is processed.
             */
            fun mayStartNewInterval(delay: Int): Boolean = lastSendTime + delay <= now
            /**
             * Time from now when not only the current but also the next interval has ended.
             * The interval is measured as [delay] milliseconds.
             */
            private fun timeUntilNextIntervalEnds(delay: Int): Long = lastSendTime + 2 * delay - now

            /**
             * Call when an event is processed. This marks the start of an interval and will discard
             * any postponed events.
             */
            fun didProcessEvent() {
                lastSendTime = now
                postponedTime = null
                postponedEvent = null
                postponeFuture?.let {
                    it.cancel()
                    postponeFuture = null
                }
            }

            /**
             * Postpone sending [event] until the next interval ends. Only the last event
             * in the current interval will be sent and only if no events are sent in the interval
             * after it. This method may be called multiple times in an interval. The postponed
             * action will be run by [mHandler]. The interval is measured as [delay] milliseconds.
             */
            fun postponeEvent(
                time: Double,
                event: SensorEvent,
                mHandler: SafeHandler,
                delay: Int,
                process: (postponedTime: Double, postponedEvent: SensorEvent) -> Unit,
            ) {
                postponedTime = time
                postponedEvent = event
                if (postponeFuture == null) {
                    postponeFuture = mHandler.delay(timeUntilNextIntervalEnds(delay)) {
                        postponeFuture = null
                        process(
                            postponedTime ?: return@delay,
                            postponedEvent ?: return@delay,
                        )
                    }
                }
            }

            companion object {
                private val now: Long
                    get() = SystemClock.uptimeMillis()
            }
        }
    }
}

