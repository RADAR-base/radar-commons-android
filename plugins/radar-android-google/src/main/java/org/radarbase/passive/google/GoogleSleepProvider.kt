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

package org.radarbase.passive.google

import android.Manifest
import android.os.Build
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider

class GoogleSleepProvider(radarService: RadarService) : SourceProvider<BaseSourceState>(radarService) {

    override val description: String
        get() = radarService.getString(R.string.google_sleep_description)

    override val serviceClass: Class<GoogleSleepService> = GoogleSleepService::class.java

    override val pluginNames = listOf(
            "google_sleep",
            "sleep",
            ".phone.GoogleSleepProvider",
            "org.radarbase.passive.google.GoogleSleepProvider",
            "org.radarcns.google.GoogleSleepProvider")

    override val displayName: String
        get() = radarService.getString(R.string.googleSleepServiceDisplayName)

    override val permissionsNeeded: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        listOf(Manifest.permission.ACTIVITY_RECOGNITION)
    } else {
        emptyList()
    }

    override val sourceProducer: String = "ANDROID"
    override val sourceModel: String = "PHONE"

    override val version: String = BuildConfig.VERSION_NAME

    override val isDisplayable: Boolean = false
}
