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

import android.Manifest.permission.RECORD_AUDIO
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.pm.PackageManager
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider

open class OpenSmileAudioProvider(radarService: RadarService) : SourceProvider<BaseSourceState>(radarService) {
    override val serviceClass: Class<OpenSmileAudioService> = OpenSmileAudioService::class.java

    override val pluginNames = listOf(
            "opensmile_audio",
            "audio",
            "org.radarbase.passive.audio.OpenSmileAudioProvider",
            "org.radarcns.audio.AudioServiceProvider")

    override val displayName: String
        get() = radarService.getString(R.string.header_audio_status)

    override val description: String?
        get() = radarService.getString(R.string.audio_description)

    override val permissionsNeeded: List<String> = listOf(RECORD_AUDIO, WRITE_EXTERNAL_STORAGE)
    override val featuresNeeded: List<String> = listOf(PackageManager.FEATURE_MICROPHONE)
    override val sourceProducer: String = "OpenSmile"
    override val sourceModel: String = "Audio"
    override val version: String = BuildConfig.VERSION_NAME
}
