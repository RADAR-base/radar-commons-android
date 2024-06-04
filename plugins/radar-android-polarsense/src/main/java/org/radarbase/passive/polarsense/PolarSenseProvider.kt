package org.radarbase.passive.polarsense

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider

open class PolarSenseProvider(radarService: RadarService) : SourceProvider<PolarSenseState>(radarService) {
    override val serviceClass: Class<PolarSenseService> = PolarSenseService::class.java

    override val pluginNames = listOf(
        "PolarSense_sensors",
        "PolarSense_sensor",
        ".polar.PolarSenseProvider",
        "org.radarbase.passive.polar.PolarSenseProvider",
        "org.radarcns.polar.PolarSenseProvider")

    override val description: String
        get() = radarService.getString(R.string.polarSensorsDescription)
    override val hasDetailView = true

    override val displayName: String
        get() = radarService.getString(R.string.polarDisplayName)

    override val permissionsNeeded = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    override val featuresNeeded = listOf(PackageManager.FEATURE_BLUETOOTH, PackageManager.FEATURE_BLUETOOTH_LE)

    override val sourceProducer: String = PRODUCER

    override val sourceModel: String = MODEL

    override val version: String = BuildConfig.VERSION_NAME

    override val isFilterable = true
    companion object {
        const val PRODUCER = "Polar"
        const val MODEL = "Generic"
    }
}
