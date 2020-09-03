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

import android.hardware.Sensor
import android.util.SparseIntArray
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * A service that manages the phone sensor manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
class PhoneSensorService : SourceService<PhoneState>() {
    private val sensorDelays: SparseIntArray = SparseIntArray(5)

    override val defaultState: PhoneState
        get() = PhoneState()

    override fun createSourceManager() = PhoneSensorManager(this)

    override fun configureSourceManager(manager: SourceManager<PhoneState>, config: SingleRadarConfiguration) {
        manager as PhoneSensorManager

        val defaultInterval = config.getInt(PHONE_SENSOR_INTERVAL, PHONE_SENSOR_INTERVAL_DEFAULT)

        sensorDelays.apply {
            put(Sensor.TYPE_ACCELEROMETER, config.getInt(PHONE_SENSOR_ACCELERATION_INTERVAL, defaultInterval))
            put(Sensor.TYPE_MAGNETIC_FIELD, config.getInt(PHONE_SENSOR_MAGNETIC_FIELD_INTERVAL, defaultInterval))
            put(Sensor.TYPE_GYROSCOPE, config.getInt(PHONE_SENSOR_GYROSCOPE_INTERVAL, defaultInterval))
            put(Sensor.TYPE_LIGHT, config.getInt(PHONE_SENSOR_LIGHT_INTERVAL, defaultInterval))
            put(Sensor.TYPE_STEP_COUNTER, config.getInt(PHONE_SENSOR_STEP_COUNT_INTERVAL, defaultInterval))
        }

        manager.setSensorDelays(sensorDelays)
        manager.setBatteryUpdateInterval(
                config.getLong(PHONE_SENSOR_BATTERY_INTERVAL_SECONDS, PHONE_SENSOR_BATTERY_INTERVAL_DEFAULT_SECONDS),
                TimeUnit.SECONDS)

    }

    companion object {
        internal const val PHONE_SENSOR_INTERVAL_DEFAULT = 200
        internal const val PHONE_SENSOR_BATTERY_INTERVAL_DEFAULT_SECONDS = 600L
        internal const val PHONE_SENSOR_INTERVAL = "phone_sensor_default_interval"
        internal const val PHONE_SENSOR_GYROSCOPE_INTERVAL = "phone_sensor_gyroscope_interval"
        internal const val PHONE_SENSOR_MAGNETIC_FIELD_INTERVAL = "phone_sensor_magneticfield_interval"
        internal const val PHONE_SENSOR_STEP_COUNT_INTERVAL = "phone_sensor_steps_interval"
        internal const val PHONE_SENSOR_ACCELERATION_INTERVAL = "phone_sensor_acceleration_interval"
        internal const val PHONE_SENSOR_LIGHT_INTERVAL = "phone_sensor_light_interval"
        internal const val PHONE_SENSOR_BATTERY_INTERVAL_SECONDS = "phone_sensor_battery_interval_seconds"
    }
}
