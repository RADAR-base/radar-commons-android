package org.radarbase.passive.garmin

import android.Manifest
import android.content.pm.PackageManager
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider

class GarminProvider(radarService: RadarService) : SourceProvider<GarminState>(radarService) {
    override val permissionsNeeded = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    override val featuresNeeded = listOf(PackageManager.FEATURE_BLUETOOTH, PackageManager.FEATURE_BLUETOOTH_LE)
    override val pluginNames: List<String>
        get() = listOf( "garmin",
                ".passive.garmin.GarminProvider",
                "org.radarbase.passive.garmin.GarminProvider",
                "org.radarcns.passive.garmin.GarminProvider")
    override val serviceClass: Class<GarminService> = GarminService::class.java
    override val sourceProducer: String = "Garmin"

    override val sourceModel: String = "Generic"

    override val version = "1.0.0"

    override val displayName: String
        get() = radarService.getString(R.string.garminlabel)
}