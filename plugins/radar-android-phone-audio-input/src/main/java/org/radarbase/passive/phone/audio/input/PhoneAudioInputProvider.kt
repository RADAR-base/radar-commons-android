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

package org.radarbase.passive.phone.audio.input

import android.Manifest
import android.content.Intent
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider
import org.radarbase.passive.phone.audio.input.ui.PhoneAudioInputActivity

class PhoneAudioInputProvider(radarService: RadarService): SourceProvider<PhoneAudioInputState>(radarService) {

    override val description: String
        get() = radarService.getString(R.string.phone_audio_input_description)
    override val pluginNames: List<String>
        get() = listOf(
            "phone_audio_input",
            "audio_input",
            ".phone.PhoneAudioInputProvider",
            "org.radarbase.passive.phone.audio.input.PhoneAudioInputProvider"

        )
    override val serviceClass: Class<PhoneAudioInputService>
        get() = PhoneAudioInputService::class.java
    override val displayName: String
        get() = radarService.getString(R.string.phone_audio_input_display_name)
    override val sourceProducer: String
        get() = "ANDROID"
    override val sourceModel: String
        get() = "PHONE"
    override val version: String
        get() = BuildConfig.VERSION_NAME
    override val permissionsNeeded: List<String>
        get() = listOf(Manifest.permission.RECORD_AUDIO)
    override val actions: List<Action>
        get() = listOf(Action(radarService.getString(R.string.startRecordingActivity)){
            startActivity(Intent(this, PhoneAudioInputActivity::class.java))
        })
}