package org.radarbase.android.data

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration

data class CacheConfiguration(
        /** Time in milliseconds until data is committed to disk. */
        var commitRate: Long = 10_000L,
        /** Maximum size the data cache may have in bytes.  */
        var maximumSize: Long = 450_000_000
) {
    fun configure(config: SingleRadarConfiguration) {
        maximumSize = config.getLong(RadarConfiguration.MAX_CACHE_SIZE, maximumSize)
        commitRate = config.getLong(RadarConfiguration.DATABASE_COMMIT_RATE_KEY, commitRate)
    }
}
