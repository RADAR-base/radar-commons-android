package org.radarbase.passive.polar

import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider

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

    override val displayName: String
        get() = radarService.getString(R.string.polarDisplayName)

    override val permissionsNeeded: List<String> = emptyList()

    override val sourceProducer: String = PRODUCER

    override val sourceModel: String = MODEL

    override val version: String = BuildConfig.VERSION_NAME

    companion object {
        const val PRODUCER = "Polar"
        const val MODEL = "Generic"
    }
}
