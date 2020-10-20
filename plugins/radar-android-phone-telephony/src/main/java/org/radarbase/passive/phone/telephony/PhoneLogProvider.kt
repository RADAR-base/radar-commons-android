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

package org.radarbase.passive.phone.telephony

import android.Manifest.permission.READ_CALL_LOG
import android.Manifest.permission.READ_SMS
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider

open class PhoneLogProvider(radarService: RadarService) : SourceProvider<BaseSourceState>(radarService) {
    override val serviceClass: Class<PhoneLogService> = PhoneLogService::class.java

    override val pluginNames = listOf(
            "phone_telephony",
            "telephony",
            "phone_log",
            ".phone.PhoneLogProvider",
            "org.radarbase.passive.phone.telephony.PhoneLogProvider",
            "org.radarcns.phone.PhoneLogProvider")

    override val description: String
        get() = radarService.getString(R.string.phone_log_description)

    override val displayName: String
        get() = radarService.getString(R.string.phoneLogServiceDisplayName)

    override val permissionsNeeded: List<String> = listOf(READ_CALL_LOG, READ_SMS)

    override val isDisplayable: Boolean = false

    override val sourceProducer: String = "ANDROID"

    override val sourceModel: String = "PHONE"

    override val version: String = BuildConfig.VERSION_NAME
}
