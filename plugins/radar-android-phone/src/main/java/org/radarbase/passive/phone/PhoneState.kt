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

import org.radarbase.android.source.BaseSourceState

/**
 * The status on a single point in time
 */
class PhoneState : BaseSourceState() {
    override val acceleration = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    @set:Synchronized
    override var batteryLevel = Float.NaN

    override val hasAcceleration: Boolean = true

    @Synchronized
    fun setAcceleration(x: Float, y: Float, z: Float) {
        this.acceleration[0] = x
        this.acceleration[1] = y
        this.acceleration[2] = z
    }
}
