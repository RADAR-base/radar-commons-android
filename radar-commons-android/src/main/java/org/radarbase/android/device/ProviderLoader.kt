package org.radarbase.android.device

import com.crashlytics.android.Crashlytics
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.RadarService
import org.slf4j.LoggerFactory
import java.util.*

class ProviderLoader {

    private var previousDeviceServices: String? = null
    private var previousProviders: List<DeviceServiceProvider<*>>? = null

    init {
        previousDeviceServices = null
        previousProviders = null
    }

    /**
     * Loads the service providers specified in the
     * [RadarConfiguration.DEVICE_SERVICES_TO_CONNECT]. This function will call
     * [DeviceServiceProvider.radarService] on each of the
     * loaded service providers.
     */
    @Synchronized
    fun loadProviders(context: RadarService,
                      config: RadarConfiguration): List<DeviceServiceProvider<*>> {
        val deviceServices = config.getString(RadarConfiguration.DEVICE_SERVICES_TO_CONNECT)
        if (previousDeviceServices == deviceServices) {
            return previousProviders ?: listOf()
        }
        val providers = loadProviders(deviceServices)
        for (provider in providers) {
            provider.radarService = context
        }
        previousDeviceServices = deviceServices
        previousProviders = providers

        return providers
    }

    /**
     * Loads the service providers specified in given whitespace-delimited String.
     */
    private fun loadProviders(deviceServices: String): List<DeviceServiceProvider<*>> {
        return Scanner(deviceServices).asSequence()
                .flatMap {
                    if (it[0] == '.') {
                        sequenceOf("org.radarcns$it", "org.radarbase$it")
                    } else sequenceOf(it)
                }
                .map { className ->
                    try {
                        Class.forName(className)
                                .getConstructor()
                                .newInstance() as DeviceServiceProvider<*>
                    } catch (ex: ReflectiveOperationException) {
                        logger.warn("Provider {} is not a legal DeviceServiceProvider: {}", className,
                                ex.toString())
                        Crashlytics.logException(ex)
                        null
                    } catch (ex: ClassCastException) {
                        logger.warn("Provider {} is not a DeviceServiceProvider: {}", className,
                                ex.toString())
                        Crashlytics.logException(ex)
                        null
                    }
                }
                .filterNotNull()
                .toList()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProviderLoader::class.java)
    }
}
