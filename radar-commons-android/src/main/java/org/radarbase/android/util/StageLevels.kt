package org.radarbase.android.util

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration

data class StageLevels(
        var minimum: Float = 0.1f,
        var reduced: Float = 0.2f
) {
    fun configure(config: SingleRadarConfiguration) {
        minimum = config.getFloat(RadarConfiguration.KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL, minimum)
        reduced = config.getFloat(RadarConfiguration.KAFKA_UPLOAD_REDUCED_BATTERY_LEVEL, reduced)
    }
}
