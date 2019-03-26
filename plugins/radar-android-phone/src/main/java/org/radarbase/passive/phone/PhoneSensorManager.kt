/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import android.os.BatteryManager
import android.os.BatteryManager.*
import android.os.PowerManager
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.util.SparseArray
import android.util.SparseIntArray
import org.radarbase.android.data.DataCache
import org.radarbase.android.device.AbstractDeviceManager
import org.radarbase.android.device.DeviceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarbase.android.util.SafeHandler
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.*
import org.radarbase.passive.phone.PhoneSensorService.Companion.PHONE_SENSOR_INTERVAL_DEFAULT
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class PhoneSensorManager(context: PhoneSensorService) : AbstractDeviceManager<PhoneSensorService, PhoneState>(context), SensorEventListener {

    private val accelerationTopic: DataCache<ObservationKey, PhoneAcceleration> = createCache("android_phone_acceleration", PhoneAcceleration())
    private val lightTopic: DataCache<ObservationKey, PhoneLight> = createCache("android_phone_light", PhoneLight())
    private val stepCountTopic: DataCache<ObservationKey, PhoneStepCount> = createCache("android_phone_step_count", PhoneStepCount())
    private val gyroscopeTopic: DataCache<ObservationKey, PhoneGyroscope> = createCache("android_phone_gyroscope", PhoneGyroscope())
    private val magneticFieldTopic: DataCache<ObservationKey, PhoneMagneticField> = createCache("android_phone_magnetic_field", PhoneMagneticField())
    private val batteryTopic: DataCache<ObservationKey, PhoneBatteryLevel> = createCache("android_phone_battery_level", PhoneBatteryLevel())
    private val sensorDelays: SparseIntArray = SparseIntArray()

    private val mHandler: SafeHandler = SafeHandler("Phone sensors", THREAD_PRIORITY_BACKGROUND)

    private val sensorManager: SensorManager? = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val batteryProcessor: OfflineProcessor
    private var lastStepCount = -1
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        batteryProcessor = OfflineProcessor(context) {
            process = listOf(this@PhoneSensorManager::processBatteryStatus)
            requestCode = REQUEST_CODE_PENDING_INTENT
            requestName = ACTIVITY_LAUNCH_WAKE
            wake = true
        }

        if (sensorManager == null) {
            updateStatus(DeviceStatusListener.Status.DISCONNECTED)
        } else {
            updateStatus(DeviceStatusListener.Status.READY)
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun start(acceptableIds: Set<String>) {
        register()
        mHandler.start()
        mHandler.execute {
            wakeLock = (service.getSystemService(POWER_SERVICE) as PowerManager?)?.let {pm ->
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "org.radarcns.phone:PhoneSensorManager")
                        .also { it.acquire() }
            }
            registerSensors()
            updateStatus(DeviceStatusListener.Status.CONNECTED)
        }

        batteryProcessor.start {
            batteryProcessor.trigger()
        }
    }

    fun setSensorDelays(sensorDelays: SparseIntArray) {
        mHandler.execute {
            if (this.sensorDelays == sensorDelays) {
                return@execute
            }

            this.sensorDelays.clear()
            for (i in 0 until sensorDelays.size()) {
                this.sensorDelays.put(sensorDelays.keyAt(i), sensorDelays.valueAt(i))
            }
            if (state.status == DeviceStatusListener.Status.CONNECTED) {
                sensorManager!!.unregisterListener(this)
                registerSensors()
            }
        }
    }

    fun setBatteryUpdateInterval(period: Long, batteryIntervalUnit: TimeUnit) {
        batteryProcessor.interval(period, batteryIntervalUnit)
    }

    /**
     * Register all sensors supplied in SENSOR_TYPES_TO_REGISTER constant.
     */
    private fun registerSensors() {
        mHandler.executeReentrant {
            // At time of writing this is: Accelerometer, Light, Gyroscope, Magnetic Field and Step Counter
            for (sensorType in SENSOR_TYPES_TO_REGISTER) {
                val sensor = sensorManager!!.getDefaultSensor(sensorType)
                if (sensor != null) {
                    // delay from milliseconds to microseconds
                    val delay = TimeUnit.MILLISECONDS.toMicros(sensorDelays.get(sensorType, PHONE_SENSOR_INTERVAL_DEFAULT).toLong())
                    if (delay > 0) {
                        sensorManager.registerListener(this, sensor, delay.toInt())
                    }
                } else {
                    logger.warn("The sensor '{}' could not be found", SENSOR_NAMES.get(sensorType, "unknown"))
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        mHandler.execute {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> processAcceleration(event)
                Sensor.TYPE_LIGHT -> processLight(event)
                Sensor.TYPE_GYROSCOPE -> processGyroscope(event)
                Sensor.TYPE_MAGNETIC_FIELD -> processMagneticField(event)
                Sensor.TYPE_STEP_COUNTER -> processStep(event)
                else -> logger.debug("Phone registered unknown sensor change: '{}'", event.sensor.type)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // no action
    }

    private fun processAcceleration(event: SensorEvent) {
        // x,y,z are in m/s2
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        state.setAcceleration(x, y, z)

        val time = currentTime

        send(accelerationTopic, PhoneAcceleration(time, time, x, y, z))
    }

    private fun processLight(event: SensorEvent) {
        val lightValue = event.values[0]

        val time = currentTime

        send(lightTopic, PhoneLight(time, time, lightValue))
    }

    private fun processGyroscope(event: SensorEvent) {
        // Not normalized axis of rotation in rad/s
        val axisX = event.values[0]
        val axisY = event.values[1]
        val axisZ = event.values[2]

        val time = currentTime

        send(gyroscopeTopic, PhoneGyroscope(time, time, axisX, axisY, axisZ))
    }

    private fun processMagneticField(event: SensorEvent) {
        // Magnetic field in microTesla
        val axisX = event.values[0]
        val axisY = event.values[1]
        val axisZ = event.values[2]

        val time = currentTime

        send(magneticFieldTopic, PhoneMagneticField(time, time, axisX, axisY, axisZ))
    }

    private fun processStep(event: SensorEvent) {
        // Number of step since listening or since reboot
        val stepCount = event.values[0].toInt()

        val time = currentTime

        // Send how many steps have been taken since the last time this function was triggered
        // Note: normally processStep() is called for every new step and the stepsSinceLastUpdate is 1
        val stepsSinceLastUpdate: Int
        if (lastStepCount == -1 || lastStepCount > stepCount) {
            stepsSinceLastUpdate = 1
        } else {
            stepsSinceLastUpdate = stepCount - lastStepCount
        }
        lastStepCount = stepCount
        send(stepCountTopic, PhoneStepCount(time, time, stepsSinceLastUpdate))

        logger.info("Steps taken: {}", stepsSinceLastUpdate)
    }

    private fun processBatteryStatus() {
        // Get last broadcast battery change intent
        val intent = service.registerReceiver(null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        val batteryPct = level / scale.toFloat()

        val isPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BATTERY_STATUS_UNKNOWN)
        val batteryStatus = BATTERY_TYPES.get(status, BatteryStatus.UNKNOWN)

        state.batteryLevel = batteryPct

        val time = currentTime
        send(batteryTopic, PhoneBatteryLevel(time, time, batteryPct, isPlugged, batteryStatus))
    }

    @Throws(IOException::class)
    override fun close() {
        batteryProcessor.close()

        mHandler.stop {
            sensorManager?.unregisterListener(this)
            wakeLock?.release()
        }
        super.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PhoneSensorManager::class.java)

        // Sensors to register, together with the name of the sensor
        private val SENSOR_TYPES_TO_REGISTER = intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LIGHT, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_STEP_COUNTER)

        // Names of the sensor (for warning message if unable to register)
        private val SENSOR_NAMES = SparseArray<String>(5)

        init {
            SENSOR_NAMES.append(Sensor.TYPE_ACCELEROMETER, Sensor.STRING_TYPE_ACCELEROMETER)
            SENSOR_NAMES.append(Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT)
            SENSOR_NAMES.append(Sensor.TYPE_MAGNETIC_FIELD, Sensor.STRING_TYPE_MAGNETIC_FIELD)
            SENSOR_NAMES.append(Sensor.TYPE_GYROSCOPE, Sensor.STRING_TYPE_GYROSCOPE)
            SENSOR_NAMES.append(Sensor.TYPE_STEP_COUNTER, Sensor.STRING_TYPE_STEP_COUNTER)
        }

        private val BATTERY_TYPES = SparseArray<BatteryStatus>(5)

        init {
            BATTERY_TYPES.append(BATTERY_STATUS_UNKNOWN, BatteryStatus.UNKNOWN)
            BATTERY_TYPES.append(BATTERY_STATUS_CHARGING, BatteryStatus.CHARGING)
            BATTERY_TYPES.append(BATTERY_STATUS_DISCHARGING, BatteryStatus.DISCHARGING)
            BATTERY_TYPES.append(BATTERY_STATUS_NOT_CHARGING, BatteryStatus.NOT_CHARGING)
            BATTERY_TYPES.append(BATTERY_STATUS_FULL, BatteryStatus.FULL)
        }

        private val ACTIVITY_LAUNCH_WAKE = "org.radarbase.passive.phone.PhoneSensorManager.ACTIVITY_LAUNCH_WAKE"
        private val REQUEST_CODE_PENDING_INTENT = 482480668
    }
}
