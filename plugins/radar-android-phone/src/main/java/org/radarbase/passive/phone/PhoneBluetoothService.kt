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

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService

import java.util.concurrent.TimeUnit

class PhoneBluetoothService : SourceService<BaseSourceState>() {

    override val defaultState: BaseSourceState
        get() = BaseSourceState()

    override val isBluetoothConnectionRequired: Boolean = false

    override fun createSourceManager(): PhoneBluetoothManager {
        return PhoneBluetoothManager(this)
    }

    override fun configureSourceManager(manager: SourceManager<BaseSourceState>, configuration: RadarConfiguration) {
        val phoneManager = manager as PhoneBluetoothManager
        phoneManager.setCheckInterval(
                configuration.getLong(PHONE_BLUETOOTH_DEVICES_SCAN_INTERVAL,
                        BLUETOOTH_DEVICES_SCAN_INTERVAL_DEFAULT),
                TimeUnit.SECONDS)
    }

    companion object {
        private const val PHONE_BLUETOOTH_DEVICES_SCAN_INTERVAL = "bluetooth_devices_scan_interval_seconds"
        val BLUETOOTH_DEVICES_SCAN_INTERVAL_DEFAULT = TimeUnit.HOURS.toSeconds(1)
    }
}
