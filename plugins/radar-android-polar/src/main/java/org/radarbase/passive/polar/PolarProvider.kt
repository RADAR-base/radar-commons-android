package org.radarbase.passive.polar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider
import org.radarbase.passive.polar.PolarService.Companion.POLAR_SHARED_PREF_KEY
import org.radarbase.passive.polar.PolarService.Companion.POLAR_SHARED_PREF_NAME
import org.radarbase.passive.polar.ui.PolarActivity
import androidx.core.content.edit

open class PolarProvider(radarService: RadarService) : SourceProvider<PolarState>(radarService) {
    override val serviceClass: Class<PolarService> = PolarService::class.java

    override val pluginNames = listOf(
        "polar_sensors",
        "polar_sensor",
        ".polar.PolarProvider",
        "org.radarbase.passive.polar.PolarProvider",
        "org.radarcns.polar.PolarProvider")

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

    override val sourceProducer: String = POLAR_PRODUCER

    override val sourceModel: String = POLAR_MODEL

    override val version: String = BuildConfig.VERSION_NAME

    override val actions: List<Action>
        get() = super.actions.toMutableList().apply {
                val uiEnabled = config.latestConfig.isExplicitDisclosureProject()
                if (uiEnabled) {
                    add(
                        Action(radarService.getString(R.string.polarControlsAction), null) {
                            startActivity(Intent(this, PolarActivity::class.java))
                        }
                    )
                }
                add(
                    Action("Reset Polar device ID", null) {
                        applicationContext.getSharedPreferences(POLAR_SHARED_PREF_NAME, Context.MODE_PRIVATE)
                            .edit {
                                remove(POLAR_SHARED_PREF_KEY)
                            }
                    }
                )
            }.toList()

    override val isFilterable = true
    companion object {
        private const val POLAR_PRODUCER = "Polar"
        private const val POLAR_MODEL = "Generic"
    }
}
