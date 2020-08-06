package org.radarbase.android.config

import androidx.lifecycle.MutableLiveData
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_DEFAULT
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_KEY

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
                status = newStatus

                if (newStatus == RadarConfiguration.RemoteConfigStatus.FETCHED) {
                    val newConfig = readConfig()
                    if (newConfig != latestConfig) {
                        latestConfig = newConfig
                        config.postValue(newConfig)
                    }
                }
            }
        }
    }

    private fun updateConfig() {
        val newConfig = readConfig()
        if (newConfig != latestConfig) {
            latestConfig = newConfig
            config.postValue(newConfig)
        }
    }

    override fun put(key: String, value: Any): String? = localConfig.put(key, value)

    override fun persistChanges() {
        if (localConfig.persistChanges()) {
            updateConfig()
        }
    }

    private fun readConfig() = SingleRadarConfiguration(status, HashMap<String, String>().apply {
        putAll(defaults)
        remoteConfigs.forEach {
            putAll(it.cache)
        }
        putAll(localConfig.config)
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
}
