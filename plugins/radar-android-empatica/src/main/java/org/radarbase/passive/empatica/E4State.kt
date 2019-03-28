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

package org.radarbase.passive.empatica

import com.empatica.empalink.config.EmpaSensorType
import org.radarbase.android.source.BaseSourceState
import java.util.*

/**
 * The status on a single point in time of an Empatica E4 device.
 */
class E4State : BaseSourceState() {
    @set:Synchronized
    var bloodVolumePulse = Float.NaN

    @set:Synchronized
    var electroDermalActivity = Float.NaN

    @set:Synchronized
    var interBeatInterval = Float.NaN

    override val hasAcceleration: Boolean = true
    override var acceleration = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    @Synchronized
    fun setAcceleration(x: Float, y: Float, z: Float) {
        this.acceleration[0] = x
        this.acceleration[1] = y
        this.acceleration[2] = z
    }

    @set:Synchronized
    override var batteryLevel: Float = Float.NaN

    override val hasHeartRate: Boolean = true
    override val heartRate: Float
        get() = 60 / interBeatInterval

    override val hasTemperature: Boolean = true
    @set:Synchronized
    override var temperature = Float.NaN

    private val sensorStatus: MutableMap<EmpaSensorType, String> = EnumMap<EmpaSensorType, String>(EmpaSensorType::class.java)
    @Synchronized
    fun setSensorStatus(type: EmpaSensorType, status: String) {
        sensorStatus[type] = status
    }
}
