package org.radarbase.android.device

import com.crashlytics.android.Crashlytics
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.RadarService
import org.radarbase.android.util.ChangeApplier
import org.slf4j.LoggerFactory
import java.util.*

class ProviderLoader {
    private val deviceServiceCache = ChangeApplier(::loadProviders)

    /**
     * Loads the service providers specified in the
     * [RadarConfiguration.DEVICE_SERVICES_TO_CONNECT]. This function will call
     * [DeviceServiceProvider.radarService] on each of the
     * loaded service providers.
     */
    @Synchronized
    fun loadProviders(radarService: RadarService,
                      config: RadarConfiguration): List<DeviceServiceProvider<*>> {
        val deviceServices = config.getString(RadarConfiguration.DEVICE_SERVICES_TO_CONNECT)
        return deviceServiceCache.runIfChanged(deviceServices) { providers ->
            providers.forEach { it.radarService = radarService }
        }
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
                        (Class.forName(className)
                                .getConstructor()
                                .newInstance() as DeviceServiceProvider<*>)
                    } catch (ex: ClassNotFoundException) {
                        logger.warn("Provider {} not found", className)
                        null
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
