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

package org.radarbase.passive.bittium

import android.os.Process.THREAD_PRIORITY_FOREGROUND
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.android.util.SafeHandler
import org.radarcns.bittium.faros.FarosSdkFactory

/**
 * A service that manages a FarosDeviceManager and a TableDataHandler to send store the data of a
 * Faros bluetooth connection
 */
class FarosService : SourceService<FarosState>() {
    private lateinit var farosFactory: FarosSdkFactory
    private lateinit var handler: SafeHandler

    override val defaultState: FarosState
        get() = FarosState()

    override fun onCreate() {
        super.onCreate()
        handler = SafeHandler.getInstance("Faros", THREAD_PRIORITY_FOREGROUND)
        farosFactory = FarosSdkFactory()
    }

    override fun createSourceManager() = FarosManager(this, farosFactory, handler)

    override fun configureSourceManager(manager: SourceManager<FarosState>, config: SingleRadarConfiguration) {
        manager as FarosManager
        manager.applySettings(farosFactory.defaultSettingsBuilder()
                .accelerometerRate(config.getInt(ACC_RATE, ACC_RATE_DEFAULT))
                .accelerometerResolution(config.getFloat(ACC_RESOLUTION, ACC_RESOLUTION_DEFAULT))
                .ecgRate(config.getInt(ECG_RATE, ECG_RATE_DEFAULT))
                .ecgResolution(config.getFloat(ECG_RESOLUTION, ECG_RESOLUTION_DEFAULT))
                .ecgChannels(config.getInt(ECG_CHANNELS, ECG_CHANNELS_DEFAULT))
                .ecgHighPassFilter(config.getFloat(ECG_FILTER_FREQUENCY, ECG_FILTER_FREQUENCY_DEFAULT))
                .interBeatIntervalEnable(config.getBoolean(IBI_ENABLE, IBI_ENABLE_DEFAULT))
                .temperatureEnable(config.getBoolean(TEMP_ENABLE, TEMP_ENABLE_DEFAULT))
                .build())
        manager.notifyDisconnect(config.getBoolean(NOTIFY_DISCONNECT, NOTIFY_DISCONNECT_DEFAULT))
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

        private const val NOTIFY_DISCONNECT = "bittium_faros_notify_disconnect"
        private const val NOTIFY_DISCONNECT_DEFAULT = false
    }
}
