package org.radarbase.android.kafka

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration

data class SubmitterConfiguration(
        var userId: String? = null,
        var amountLimit: Int = 1000,
        var sizeLimit: Long = 5000000L,
        var uploadRate: Long = 10L,
        var uploadRateMultiplier: Int = 1) {

    fun configure(config: SingleRadarConfiguration) {
        uploadRate = config.getLong(RadarConfiguration.KAFKA_UPLOAD_RATE_KEY, uploadRate)
        amountLimit = config.getInt(RadarConfiguration.KAFKA_RECORDS_SEND_LIMIT_KEY, amountLimit)
        sizeLimit = config.getLong(RadarConfiguration.KAFKA_RECORDS_SIZE_LIMIT_KEY, sizeLimit)
    }
}
