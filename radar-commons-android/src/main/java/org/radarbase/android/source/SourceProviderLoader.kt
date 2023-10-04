package org.radarbase.android.source

import org.radarbase.android.RadarConfiguration
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.ChangeApplier
import org.slf4j.LoggerFactory

class SourceProviderLoader(plugins: List<SourceProvider<*>>) {
    private val pluginCache = ChangeApplier<String, List<SourceProvider<*>>>(::loadProvidersFromNames)
    private val pluginNameMap: Map<String, List<SourceProvider<*>>> = buildMap {
        plugins.forEach { plugin ->
            plugin.pluginNames.forEach { name ->
                val pluginList = get(name)
                if (pluginList == null) {
                    put(name, listOf(plugin))
                } else {
                    pluginList.forEach { existingPlugin ->
                        logger.warn("Providers {} and {} have overlapping plugin names.", plugin, existingPlugin)
                    }
                    put(name, pluginList + plugin)
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
        val pluginString = config.getString(RadarConfiguration.PLUGINS, "")

        return pluginCache.applyIfChanged(pluginString) { providers ->
            logger.info("Loading plugins {}", providers.map { it.pluginNames.firstOrNull() ?: it })
        }
    }

    /**
     * Loads the service providers specified in given whitespace-delimited String.
     */
    private fun loadProvidersFromNames(pluginString: String): List<SourceProvider<*>> =
        pluginString.splitToSequence(' ', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { pluginName ->
                pluginNameMap[pluginName] ?: run {
                    logger.warn("Plugin {} not found", pluginName)
                    emptyList()
                }
            }
            .distinctBy { it.serviceClass }
            .toList()

    companion object {
        private val logger = LoggerFactory.getLogger(SourceProviderLoader::class.java)
    }
}
