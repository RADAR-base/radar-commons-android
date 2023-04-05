package org.radarbase.android.data

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.kafka.SubmitterConfiguration
import org.radarbase.android.util.StageLevels
import org.radarbase.android.util.StorageStage
import org.radarbase.android.util.takeTrimmedIfNotEmpty
import java.util.*

data class DataHandlerConfiguration(
        /** Minimum battery level to send data. */
        var batteryStageLevels: StageLevels = StageLevels(),
        /** Data sending reduction if a reduced battery level is detected. */
        var reducedUploadMultiplier: Int = 5,
        /** Whether to send only if Wifi or Ethernet is enabled. */
        var sendOnlyWithWifi: Boolean = true,
        /** Whether to use cellular only for high priority topics. */
        var sendOverDataHighPriority: Boolean = true,
        /** Topics marked as high priority. */
        var highPriorityTopics: Set<String> = emptySet(),
        /**Weather to show notification based on storage levels. */
        var storageStage: StorageStage = StorageStage(),
        var restConfig: RestConfiguration = RestConfiguration(),
        var cacheConfig: CacheConfiguration = CacheConfiguration(),
        var submitterConfig: SubmitterConfiguration = SubmitterConfiguration()
) {
    fun batteryLevel(builder: StageLevels.() -> Unit) {
        batteryStageLevels = batteryStageLevels.copy().apply(builder)
    }
    fun cache(builder: CacheConfiguration.() -> Unit) {
        cacheConfig = cacheConfig.copy().apply(builder)
    }
    fun submitter(builder: SubmitterConfiguration.() -> Unit) {
        submitterConfig = submitterConfig.copy().apply(builder)
    }
    fun rest(builder: RestConfiguration.() -> Unit) {
        restConfig = restConfig.copy().apply(builder)
    }
    fun storage(builder: StorageStage.() -> Unit){
        storageStage = storageStage.copy().apply(builder)
    }

    fun configure(config: SingleRadarConfiguration) {
        sendOnlyWithWifi = config.getBoolean(RadarConfiguration.SEND_ONLY_WITH_WIFI, RadarConfiguration.SEND_ONLY_WITH_WIFI_DEFAULT)
        sendOverDataHighPriority = config.getBoolean(RadarConfiguration.SEND_OVER_DATA_HIGH_PRIORITY, RadarConfiguration.SEND_OVER_DATA_HIGH_PRIORITY_DEFAULT)
        highPriorityTopics = HashSet(config.getString(RadarConfiguration.TOPICS_HIGH_PRIORITY, "")
                .split(providerSeparator)
                .mapNotNull(String::takeTrimmedIfNotEmpty))

        cache {
            configure(config)
        }
        batteryLevel {
            configure(config)
        }
        rest {
            configure(config)
        }
        submitter {
            configure(config)
        }
        storage{
            configure(config)
        }
    }

    companion object {
        private val providerSeparator = ",".toRegex()
    }
}
