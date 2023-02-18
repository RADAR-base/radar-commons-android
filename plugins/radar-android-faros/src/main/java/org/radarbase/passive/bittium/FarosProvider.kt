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

import android.Manifest.permission.*
import android.content.pm.PackageManager
import android.os.Build
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider

class FarosProvider(radarService: RadarService) : SourceProvider<FarosState>(radarService) {
    override val description: String?
        get() = radarService.getString(R.string.farosDescription)

    override val serviceClass: Class<FarosService> = FarosService::class.java

    override val pluginNames = listOf(
            "bittium_faros",
            "faros",
            ".passive.bittium.FarosProvider",
            "org.radarbase.passive.bittium.FarosProvider",
            "org.radarcns.passive.bittium.FarosProvider")

    override val hasDetailView: Boolean = true

    override val permissionsNeeded: List<String> = buildList {
        add(ACCESS_COARSE_LOCATION)
        add(ACCESS_FINE_LOCATION)
        add(BLUETOOTH)
        add(BLUETOOTH_ADMIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(BLUETOOTH_SCAN)
            add(BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(ACCESS_BACKGROUND_LOCATION)
        }
    }

    override val featuresNeeded: List<String> = listOf(PackageManager.FEATURE_BLUETOOTH)

    override val sourceProducer: String = "Bittium"

    override val sourceModel: String = "Faros"

    override val version: String = BuildConfig.VERSION_NAME

    override val isFilterable = true

    override val displayName: String
        get() = radarService.getString(R.string.farosLabel)
}
