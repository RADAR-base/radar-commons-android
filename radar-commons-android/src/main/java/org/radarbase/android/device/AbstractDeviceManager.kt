/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.android.device

import androidx.annotation.CallSuper
import com.crashlytics.android.Crashlytics
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.RadarConfiguration.Companion.SOURCE_ID_KEY
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.data.DataCache
import org.radarbase.topic.AvroTopic
import org.radarcns.kafka.ObservationKey
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Abstract DeviceManager that handles some common functionality.
 *
 * @param <S> service type the manager is started by
 * @param <T> state type that the manager will update.
 */
abstract class AbstractDeviceManager<S : DeviceService<T>, T : BaseDeviceState>(val service: S)
        : DeviceManager<T> {

    /** Get the current device state.  */
    override val state: T = service.state

    protected val dataHandler = service.dataHandler ?: throw IllegalStateException(
            "Cannot start device manager without data handler")

    /** Get the name of the device.  */
    /** Set the device name. Be sure to do this as soon as possible.  */
    @get:Synchronized
    @set:Synchronized
    override var name: String = android.os.Build.MODEL
        protected set

    /** Whether this device manager has been closed.  */
    override val isClosed: Boolean
        get() = hasClosed

    @get:Synchronized
    @set:Synchronized
    private var hasClosed: Boolean = false
    private var didWarn: Boolean = false

    /**
     * Update the device status. The device status should be updated with the following meanings:
     *
     *  * DISABLED if the manager will not be able to record any data.
     *  * READY if the manager is searching for a device to connect with.
     *  * CONNECTING if the manager has found a device to connect with.
     *  * CONNECTED if the manager is connected to a device.
     *  * DISCONNECTED if the device has disconnected OR the manager is closed.
     *
     * If DISABLED is set, no other status may be set.  Once DISCONNECTED has been set, no other
     * status may be set, and the manager will be closed by the service if not already closed.
     *
     * @param status status to set
     */
    protected open fun updateStatus(status: DeviceStatusListener.Status) {
        this.state.status = status
        this.service.deviceStatusUpdated(this, status)
    }

    protected fun register(
            physicalId: String? = null,
            name: String = this.name,
            attributes: Map<String, String> = emptyMap()): Boolean {
        this.name = name
        return service.ensureRegistration(
                physicalId ?: RadarConfiguration.getOrSetUUID(service, SOURCE_ID_KEY),
                name, attributes)
    }

    /**
     * Creates and registers an Avro topic
     * @param name The name of the topic
     * @param valueClass The value class
     * @param <V> topic value type
     * @return created topic
     */
    protected fun <V : SpecificRecord> createCache(
            name: String, valueClass: Class<V>): DataCache<ObservationKey, V> {
        try {
            val method = valueClass.getMethod("getClassSchema")
            val valueSchema = method.invoke(null) as Schema
            val topic = AvroTopic(name,
                    ObservationKey.getClassSchema(), valueSchema,
                    ObservationKey::class.java, valueClass)
            return dataHandler.registerCache(topic)
        } catch (e: ReflectiveOperationException) {
            logger.error("Error creating topic {}", name, e)
            throw RuntimeException(e)
        } catch (e: IOException) {
            logger.error("Error creating topic {}", name, e)
            throw RuntimeException(e)
        }
    }

    /**
     * Send a single record, using the cache to persist the data.
     * If the current device is not registered when this is called, the data will NOT be sent.
     */
    protected fun <V : SpecificRecord> send(dataCache: DataCache<ObservationKey, V>, value: V) {
        val key = state.id
        if (key.getSourceId() != null) {
            try {
                dataCache.addMeasurement(key, value)
            } catch (ex: IllegalArgumentException) {
                Crashlytics.log("Cannot send measurement for " + state.id)
                Crashlytics.logException(ex)
                logger.error("Cannot send to dataCache {}: {}", dataCache.topic.name, ex.message)
            }

        } else if (!didWarn) {
            logger.warn("Cannot send data without a source ID from {}", javaClass.simpleName)
            didWarn = true
        }
    }

    @CallSuper
    override fun didRegister(source: SourceMetadata) {
        name = source.sourceName!!
        state.id.setSourceId(source.sourceId)
    }

    /**
     * Close the manager, disconnecting any device if necessary. Override and call super if
     * additional resources should be cleaned up. This implementation calls updateStatus with status
     * DISCONNECTED.
     */
    @Throws(IOException::class)
    override fun close() {
        hasClosed = true
        updateStatus(DeviceStatusListener.Status.DISCONNECTED)
    }

    override fun toString() = "DeviceManager{name='$name', status=$state}"

    companion object {
        private val logger = LoggerFactory.getLogger(AbstractDeviceManager::class.java)

        val currentTime: Double
            get() = System.currentTimeMillis() / 1000.0
    }
}
