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

package org.radarbase.passive.phone.usage

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceManager
import org.radarbase.android.device.DeviceService

import java.util.concurrent.TimeUnit

/**
 * A service that manages the phone sensor manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
class PhoneUsageService : DeviceService<BaseDeviceState>() {

    override val defaultState: BaseDeviceState
        get() = BaseDeviceState()

    override val isBluetoothConnectionRequired: Boolean
        get() = false

    override fun createDeviceManager(): PhoneUsageManager {
        return PhoneUsageManager(this)
    }

    override fun configureDeviceManager(manager: DeviceManager<BaseDeviceState>, configuration: RadarConfiguration) {
        val phoneManager = manager as PhoneUsageManager
        phoneManager.setUsageEventUpdateRate(
                configuration.getLong(PHONE_USAGE_INTERVAL, USAGE_EVENT_PERIOD_DEFAULT),
                TimeUnit.SECONDS)
    }

    companion object {
        private const val PHONE_USAGE_INTERVAL = "phone_usage_interval_seconds"
        internal const val USAGE_EVENT_PERIOD_DEFAULT = (60 * 60).toLong() // one hour
    }
}
