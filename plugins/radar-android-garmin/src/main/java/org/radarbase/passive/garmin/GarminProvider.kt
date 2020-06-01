package org.radarbase.passive.garmin

import android.Manifest
import android.content.pm.PackageManager
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider
import org.radarbase.android.source.SourceService

class GarminProvider(radarService: RadarService) : SourceProvider<GarminState>(radarService) {
    override val permissionsNeeded = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    override val featuresNeeded = listOf(PackageManager.FEATURE_BLUETOOTH, PackageManager.FEATURE_BLUETOOTH_LE)
    override val pluginNames: List<String>
        get() = TODO("Not yet implemented")
    override val serviceClass: Class<out SourceService<*>>
        get() = TODO("Not yet implemented")
    override val displayName: String
        get() = TODO("Not yet implemented")
    override val sourceProducer: String
        get() = TODO("Not yet implemented")
    override val sourceModel: String
        get() = TODO("Not yet implemented")
    override val version: String
        get() = TODO("Not yet implemented")

}