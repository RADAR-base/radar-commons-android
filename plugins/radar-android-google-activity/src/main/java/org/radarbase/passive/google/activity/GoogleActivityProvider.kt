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

package org.radarbase.passive.google.activity

import android.Manifest
import android.os.Build
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider

class GoogleActivityProvider(radarService: RadarService) : SourceProvider<BaseSourceState>(radarService) {

    override val description: String
        get() = radarService.getString(R.string.google_activity_description)

    override val pluginNames: List<String>
        get() = listOf(
            "google_activity",
            "activity",
            ".google.GoogleActivityProvider",
            "org.radarbase.passive.google.activity.GoogleActivityProvider",
            "org.radarcns.google.GoogleActivityProvider"
        )

    override val serviceClass: Class<GoogleActivityService> = GoogleActivityService::class.java

    override val permissionsNeeded: List<String> = listOf(ACTIVITY_RECOGNITION_COMPAT)

    override val displayName: String
        get() = radarService.getString(R.string.google_activity_display_name)

    override fun imageResource(state: BaseSourceState?): Int = R.drawable.google_activity_icon

    override val sourceProducer: String = "GOOGLE"

    override val sourceModel: String = "ACTIVITY"

    override val version: String
        get() = "1.0.0"

    companion object {
        val ACTIVITY_RECOGNITION_COMPAT = if ( Build.VERSION.SDK_INT >= 29 ) Manifest.permission.ACTIVITY_RECOGNITION
        else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
    }
}

