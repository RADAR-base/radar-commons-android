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

package org.radarbase.passive.google.places

import android.Manifest
import android.os.Build
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider
import org.radarbase.android.source.SourceStatusListener

class GooglePlacesProvider(radarService: RadarService) : SourceProvider<BaseSourceState>(radarService) {
    override val description: String
        get() = radarService.getString(R.string.google_places_description)

    override val pluginNames: List<String>
        get() = listOf(
            "google_places",
            "places",
            ".google.GooglePlacesProvider",
            "org.radarbase.passive.google.places.GooglePlacesProvider",
            "org.radarcns.google.GooglePlacesProvider"
        )

    override val permissionsNeeded: List<String> = buildList(3) {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    override fun imageResource(pluginStatus: SourceStatusListener.Status): Int {
        iconResourceMap[ICON_PLUGIN_CONNECTED] = R.drawable.places_plugin_icon_connected
        iconResourceMap[ICON_PLUGIN_DISCONNECTED] = R.drawable.places_plugin_icon_disconnected
        iconResourceMap[ICON_PLUGIN_IDLE] = R.drawable.places_plugin_icon_idle
        return super.imageResource(pluginStatus)
    }

    override val serviceClass: Class<GooglePlacesService> = GooglePlacesService::class.java

    override val displayName: String
        get() = radarService.getString(R.string.googlePlacesDisplayName)

    override val sourceProducer: String
        get() = "GOOGLE"

    override val sourceModel: String
        get() = "PLACES"
    override val version: String
        get() = "1.0.0"
}