package org.radarbase.android.config

import android.content.Context
import androidx.lifecycle.MutableLiveData
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_DEFAULT
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_KEY
import org.radarbase.android.auth.AppAuthState
import org.slf4j.LoggerFactory

class CombinedRadarConfig(
        private val localConfig: LocalConfig,
        private val remoteConfigs: List<RemoteConfig>,
        defaultsFactory: () -> Map<String, String>): RadarConfiguration {
    private val defaults = defaultsFactory()
            .filterValues { it.isNotEmpty() }
    @Volatile
    override var status: RadarConfiguration.RemoteConfigStatus = RadarConfiguration.RemoteConfigStatus.INITIAL

    @Volatile
    override var latestConfig: SingleRadarConfiguration = readConfig()
    override val config: MutableLiveData<SingleRadarConfiguration> = MutableLiveData()

    init {
        config.postValue(latestConfig)

        remoteConfigs.forEach { remoteConfig ->
            remoteConfig.onStatusUpdateListener = { newStatus ->
                logger.info("Got updated status {}", newStatus)
                status = newStatus

                if (newStatus == RadarConfiguration.RemoteConfigStatus.FETCHED) {
                    updateConfig()
                }
            }
        }
    }

    private fun updateConfig() {
        val newConfig = readConfig()
        if (newConfig != latestConfig) {
            logger.info("Updating config to {}", newConfig)
            latestConfig = newConfig
            config.postValue(newConfig)
            remoteConfigs.forEach { it.updateWithConfig(newConfig) }
        } else {
            logger.info("No change to config. Skipping.")
        }
    }

    override fun put(key: String, value: Any): String? = localConfig.put(key, value)

    override fun persistChanges() {
        if (localConfig.persistChanges()) {
            updateConfig()
        }
    }

    private fun readConfig() = SingleRadarConfiguration(status, HashMap<String, String>().apply {
        this += defaults
        remoteConfigs.forEach {
            this += it.cache
        }
        this += localConfig.config
    })

    override fun reset(vararg keys: String) {
        localConfig -= keys
        persistChanges()
    }

    override fun fetch() = remoteConfigs.forEach {
        it.fetch(latestConfig.getLong(FETCH_TIMEOUT_MS_KEY, FETCH_TIMEOUT_MS_DEFAULT))
    }

    override fun forceFetch() = remoteConfigs.forEach {
        it.forceFetch()
    }

    override fun toString(): String = latestConfig.toString()

    override fun updateWithAuthState(context: Context, appAuthState: AppAuthState?): Boolean {
        val result = super.updateWithAuthState(context, appAuthState)
        remoteConfigs.forEach {
            it.updateWithAuthState(appAuthState)
        }
        return result
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CombinedRadarConfig::class.java)
    }
}
