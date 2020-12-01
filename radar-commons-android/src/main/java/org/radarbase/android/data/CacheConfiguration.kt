package org.radarbase.android.data

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.util.MappedQueueFileStorage
import org.radarbase.util.QueueFile
import java.io.File

data class CacheConfiguration(
        /** Time in milliseconds until data is committed to disk. */
        var commitRate: Long = 10_000L,
        /** Maximum size the data cache may have in bytes.  */
        var maximumSize: Long = 450_000_000,
        /** Type of queue file implementation to use. */
        var queueFileType: QueueFileFactory = QueueFileFactory.DIRECT,
) {
    fun configure(config: SingleRadarConfiguration) {
        maximumSize = config.getLong(RadarConfiguration.MAX_CACHE_SIZE, maximumSize)
        commitRate = config.getLong(RadarConfiguration.DATABASE_COMMIT_RATE_KEY, commitRate)
    }

    enum class QueueFileFactory(val generator: (File, Long) -> QueueFile) {
        MAPPED(QueueFile::newMapped), DIRECT(QueueFile::newDirect);

        fun generate(file: File, size: Long) = generator(file, size)
    }
}
