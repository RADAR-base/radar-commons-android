package org.radarbase.android.config

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.AppAuthState
import org.slf4j.LoggerFactory

interface RemoteConfig {
    val status: RadarConfiguration.RemoteConfigStatus
    var onStatusUpdateListener: (RadarConfiguration.RemoteConfigStatus) -> Unit
    var lastFetch: Long
    val cache: Map<String, String>

    fun doFetch(maxCacheAgeMillis: Long)

    fun fetch(maxCacheAge: Long) {
        if (lastFetch + maxCacheAge < System.currentTimeMillis()) {
            doFetch(maxCacheAge)
        } else {
            logger.info("No fetch needed. Old values still in cache.")
        }
    }

    fun forceFetch() {
        lastFetch = 0
        doFetch(0)
    }

    fun updateWithConfig(config: SingleRadarConfiguration?) = Unit
    fun updateWithAuthState(appAuthState: AppAuthState?)

    companion object {
        private val logger = LoggerFactory.getLogger(RemoteConfig::class.java)
    }
}
