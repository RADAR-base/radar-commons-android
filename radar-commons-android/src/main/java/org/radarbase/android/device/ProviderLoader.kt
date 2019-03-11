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
        val providers = ArrayList<DeviceServiceProvider<*>>()
        val scanner = Scanner(deviceServices)
        while (scanner.hasNext()) {
            var className = scanner.next()
            if (className[0] == '.') {
                className = "org.radarcns$className"
            }
            try {
                val providerClass = Class.forName(className)
                val serviceProvider = providerClass.getConstructor().newInstance() as DeviceServiceProvider<*>
                providers.add(serviceProvider)
            } catch (ex: ReflectiveOperationException) {
                logger.warn("Provider {} is not a legal DeviceServiceProvider: {}", className,
                        ex.toString())
                Crashlytics.logException(ex)
            }
        }
        return providers
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProviderLoader::class.java)
    }
}
