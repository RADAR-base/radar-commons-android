package org.radarbase.android.config

import org.radarbase.android.RadarConfiguration
import org.slf4j.LoggerFactory

interface RemoteConfig {
    val status: RadarConfiguration.RemoteConfigStatus
    var onStatusUpdateListener: (RadarConfiguration.RemoteConfigStatus) -> Unit
    val lastFetch: Long
    val cache: Map<String, String>

    fun doFetch(maxCacheAge: Long)

    fun fetch(maxCacheAge: Long) {
        if (lastFetch + maxCacheAge < System.currentTimeMillis()) {
            doFetch(maxCacheAge)
        } else {
            logger.info("No fetch needed. Old values still in cache.")
        }
    }

    fun forceFetch() = doFetch(0)

    companion object {
        private val logger = LoggerFactory.getLogger(RemoteConfig::class.java)
    }
}
