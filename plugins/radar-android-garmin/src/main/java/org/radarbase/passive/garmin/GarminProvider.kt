package org.radarbase.garmin

import android.Manifest
import android.content.pm.PackageManager
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider
import org.radarbase.android.source.SourceService
import org.radarbase.passive.garmin.R

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

    override val sourceModel: String = "fenix 6"

    override val version: String = org.radarbase.android.BuildConfig.VERSION_NAME

    override val displayName: String
        get() = radarService.getString(R.string.garminlabel)
}