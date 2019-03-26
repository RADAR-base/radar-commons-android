/*
 * Copyright 2018 The Hyve
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

package org.radarbase.passive.ppg

import android.util.Size
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.device.DeviceManager
import org.radarbase.android.device.DeviceService

class PhonePpgService : DeviceService<PhonePpgState>() {

    override val defaultState: PhonePpgState
        get() = PhonePpgState()

    override fun createDeviceManager(): PhonePpgManager = PhonePpgManager(this)

    override fun configureDeviceManager(manager: DeviceManager<PhonePpgState>, configuration: RadarConfiguration) {
        (manager as PhonePpgManager).configure(
                configuration.getLong(PPG_MEASUREMENT_TIME_NAME, PPG_MEASUREMENT_TIME_DEFAULT),
                Size(configuration.getInt(PPG_MEASUREMENT_WIDTH_NAME, PPG_MEASUREMENT_WIDTH_DEFAULT),
                        configuration.getInt(PPG_MEASUREMENT_HEIGHT_NAME, PPG_MEASUREMENT_HEIGHT_DEFAULT))
        )
    }

    companion object {
        const val PPG_MEASUREMENT_TIME_NAME = "phone_ppg_measurement_seconds"
        private const val PPG_MEASUREMENT_WIDTH_NAME = "phone_ppg_measurement_width"
        private const val PPG_MEASUREMENT_HEIGHT_NAME = "phone_ppg_measurement_height"

        const val PPG_MEASUREMENT_TIME_DEFAULT = 60L
        private const val PPG_MEASUREMENT_WIDTH_DEFAULT = 200
        private const val PPG_MEASUREMENT_HEIGHT_DEFAULT = 200
    }
}
