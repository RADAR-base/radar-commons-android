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

import android.Manifest
import android.os.Build
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider

open class PhoneSensorProvider(radarService: RadarService) : SourceProvider<PhoneState>(radarService) {
    override val serviceClass: Class<PhoneSensorService> = PhoneSensorService::class.java

    override val pluginNames = listOf(
            "phone_sensors",
            "phone_sensor",
            ".phone.PhoneSensorProvider",
            "org.radarbase.passive.phone.PhoneSensorProvider",
            "org.radarcns.phone.PhoneSensorProvider")

    override val description: String
        get() = radarService.getString(R.string.phone_sensors_description)

    override val displayName: String
        get() = radarService.getString(R.string.phoneServiceDisplayName)

    override val permissionsNeeded: List<String> = listOf(SENSOR_ACTIVITY_RECOGNITION_COMPAT)

    override val sourceProducer: String = PRODUCER

    override val sourceModel: String = MODEL

    override val version: String = BuildConfig.VERSION_NAME

    companion object {
        const val PRODUCER = "ANDROID"
        const val MODEL = "PHONE"
        private val SENSOR_ACTIVITY_RECOGNITION_COMPAT = if ( Build.VERSION.SDK_INT >= 29 ) Manifest.permission.ACTIVITY_RECOGNITION
        else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
    }
}
