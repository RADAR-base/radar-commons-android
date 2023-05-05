package org.radarbase.android.util

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration

data class StorageStage(
    var partial: Float = 0.75f,
    var full: Float = 1.0f
) {
    fun configure(config: SingleRadarConfiguration) {
        full = config.getFloat(RadarConfiguration.STORAGE_STAGE_FULL, full)
        partial = config.getFloat(RadarConfiguration.STORAGE_STAGE_PARTIAL, partial)
    }
}