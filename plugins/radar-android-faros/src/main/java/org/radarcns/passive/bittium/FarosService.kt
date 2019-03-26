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

package org.radarcns.passive.bittium

import android.os.Process.THREAD_PRIORITY_FOREGROUND
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.device.DeviceManager
import org.radarbase.android.device.DeviceService
import org.radarbase.android.util.SafeHandler
import org.radarcns.bittium.faros.FarosSdkFactory

/**
 * A service that manages a FarosDeviceManager and a TableDataHandler to send store the data of a
 * Faros bluetooth connection
 */
class FarosService : DeviceService<FarosDeviceStatus>() {
    private lateinit var farosFactory: FarosSdkFactory
    private lateinit var handler: SafeHandler

    override val defaultState: FarosDeviceStatus
        get() = FarosDeviceStatus()

    override fun onCreate() {
        super.onCreate()
        handler = SafeHandler("Faros", THREAD_PRIORITY_FOREGROUND)
        farosFactory = FarosSdkFactory()
    }

    override fun createDeviceManager() = FarosDeviceManager(this, farosFactory, handler)

    override fun configureDeviceManager(manager: DeviceManager<FarosDeviceStatus>, configuration: RadarConfiguration) {
        (manager as FarosDeviceManager).applySettings(farosFactory.defaultSettingsBuilder()
                .accelerometerRate(configuration.getInt(ACC_RATE, ACC_RATE_DEFAULT))
                .accelerometerResolution(configuration.getFloat(ACC_RESOLUTION, ACC_RESOLUTION_DEFAULT))
                .ecgRate(configuration.getInt(ECG_RATE, ECG_RATE_DEFAULT))
                .ecgResolution(configuration.getFloat(ECG_RESOLUTION, ECG_RESOLUTION_DEFAULT))
                .ecgChannels(configuration.getInt(ECG_CHANNELS, ECG_CHANNELS_DEFAULT))
                .ecgHighPassFilter(configuration.getFloat(ECG_FILTER_FREQUENCY, ECG_FILTER_FREQUENCY_DEFAULT))
                .interBeatIntervalEnable(configuration.getBoolean(IBI_ENABLE, IBI_ENABLE_DEFAULT))
                .temperatureEnable(configuration.getBoolean(TEMP_ENABLE, TEMP_ENABLE_DEFAULT))
                .build())
    }

    companion object {
        private const val ACC_RATE = "bittium_faros_acceleration_rate"
        private const val ACC_RATE_DEFAULT = 25

        private const val ACC_RESOLUTION = "bittium_faros_acceleration_resolution"
        private const val ACC_RESOLUTION_DEFAULT = 0.001f

        private const val ECG_RATE = "bittium_faros_ecg_rate"
        private const val ECG_RATE_DEFAULT = 125

        private const val ECG_RESOLUTION = "bittium_faros_ecg_resolution"
        private const val ECG_RESOLUTION_DEFAULT = 1.0f

        private const val ECG_CHANNELS = "bittium_faros_ecg_channels"
        private const val ECG_CHANNELS_DEFAULT = 1

        private const val ECG_FILTER_FREQUENCY = "bittium_faros_ecg_filter_frequency"
        private const val ECG_FILTER_FREQUENCY_DEFAULT = 0.05f

        private const val TEMP_ENABLE = "bittium_faros_temperature_enable"
        private const val TEMP_ENABLE_DEFAULT = true

        private const val IBI_ENABLE = "bittium_faros_inter_beat_interval_enable"
        private const val IBI_ENABLE_DEFAULT = true
    }
}
