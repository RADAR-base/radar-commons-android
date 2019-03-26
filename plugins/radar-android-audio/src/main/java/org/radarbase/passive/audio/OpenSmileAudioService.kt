/*
 * Copyright 2017 Universität Passau and The Hyve
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

package org.radarbase.passive.audio

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceManager
import org.radarbase.android.device.DeviceService

import java.util.concurrent.TimeUnit

/**
 * A service that manages the phone sensor manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */

class OpenSmileAudioService : DeviceService<BaseDeviceState>() {
    override val defaultState: BaseDeviceState
        get() = BaseDeviceState()


    override fun createDeviceManager(): OpensmileAudioManager {
        return OpensmileAudioManager(this)
    }

    override fun configureDeviceManager(manager: DeviceManager<BaseDeviceState>, configuration: RadarConfiguration) {
        val audioManager = manager as OpensmileAudioManager
        audioManager.setRecordRate(configuration.getLong(AUDIO_RECORD_RATE_S, DEFAULT_RECORD_RATE))
        audioManager.config = OpensmileAudioManager.AudioConfiguration(
                configuration.getString(AUDIO_CONFIG_FILE, "ComParE_2016.conf"),
                configuration.getLong(AUDIO_DURATION_S, 15L),
                TimeUnit.SECONDS
        )
    }

    companion object {
        private const val AUDIO_DURATION_S = "audio_duration"
        private const val AUDIO_RECORD_RATE_S = "audio_record_rate"
        private const val AUDIO_CONFIG_FILE = "audio_config_file"
        const val DEFAULT_RECORD_RATE = 3600L
    }
}
