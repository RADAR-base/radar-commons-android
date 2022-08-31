package org.radarbase.android.source

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.ChangeApplier
import org.slf4j.LoggerFactory
import java.util.*

class SourceProviderLoader(private var plugins: List<SourceProvider<*>>) {
    private val pluginCache = ChangeApplier<String, List<SourceProvider<*>>>(::loadProvidersFromNames)

    init {
        val pluginNames = plugins.map { it.pluginNames.toHashSet() }

        for (i in pluginNames.indices) {
            for (j in i + 1 until pluginNames.size) {
                if (pluginNames[i].any { it in pluginNames[j] }) {
                    logger.warn("Providers {} and {} have overlapping plugin names.", plugins[i], plugins[j])
                }
            }
        }
    }

    /**
     * Loads the service providers specified in the
     * [RadarConfiguration.DEVICE_SERVICES_TO_CONNECT]. This function will call
     * [SourceProvider.radarService] on each of the
     * loaded service providers.
     */
    @Synchronized
    fun loadProvidersFromNames(config: SingleRadarConfiguration): List<SourceProvider<*>> {
        val pluginString = listOf(
                config.getString(RadarConfiguration.DEVICE_SERVICES_TO_CONNECT, ""),
                config.getString(RadarConfiguration.PLUGINS, ""))
                .joinToString(separator = " ")

        return pluginCache.applyIfChanged(pluginString) { providers ->
            logger.info("Loading plugins {}", providers.map { it.pluginNames.firstOrNull() ?: it })
        }
    }

    /**
     * Loads the service providers specified in given whitespace-delimited String.
     */
    private fun loadProvidersFromNames(pluginString: String): List<SourceProvider<*>> {
        return Scanner(pluginString)
            .asSequence()
            .mapNotNull { pluginName ->
                plugins.find { pluginName in it.pluginNames }
                        .also { if (it == null) logger.warn("Plugin {} not found", pluginName) }
            }
            .distinct()
            .toList()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SourceProviderLoader::class.java)
    }
}
