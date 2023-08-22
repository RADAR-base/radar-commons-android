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

package org.radarbase.passive.bittium

import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.NotificationHandler
import org.radarbase.android.util.SafeHandler
import org.radarbase.bittium.faros.BatteryStatus
import org.radarbase.bittium.faros.FarosDevice
import org.radarbase.bittium.faros.FarosDeviceListener
import org.radarbase.bittium.faros.FarosDeviceState
import org.radarbase.bittium.faros.FarosSdkFactory
import org.radarbase.bittium.faros.FarosSdkListener
import org.radarbase.bittium.faros.FarosSdkManager
import org.radarbase.bittium.faros.FarosSettings
import org.radarbase.bittium.faros.Measurement
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.bittium.BittiumFarosAcceleration
import org.radarcns.passive.bittium.BittiumFarosBatteryLevel
import org.radarcns.passive.bittium.BittiumFarosEcg
import org.radarcns.passive.bittium.BittiumFarosInterBeatInterval
import org.radarcns.passive.bittium.BittiumFarosTemperature
import org.slf4j.LoggerFactory
import java.io.IOException

class FarosManager internal constructor(
    service: FarosService,
    private val farosFactory: FarosSdkFactory,
    private val handler: SafeHandler,
) : AbstractSourceManager<FarosService, FarosState>(service),
    FarosDeviceListener,
    FarosSdkListener {
    private val notificationHandler by lazy {
        NotificationHandler(this.service)
    }
    private var doNotify: Boolean = false
    private val accelerationTopic: DataCache<ObservationKey, BittiumFarosAcceleration> = createCache("android_bittium_faros_acceleration", BittiumFarosAcceleration())
    private val ecgTopic: DataCache<ObservationKey, BittiumFarosEcg> = createCache("android_bittium_faros_ecg", BittiumFarosEcg())
    private val ibiTopic: DataCache<ObservationKey, BittiumFarosInterBeatInterval> = createCache("android_bittium_faros_inter_beat_interval", BittiumFarosInterBeatInterval())
    private val temperatureTopic: DataCache<ObservationKey, BittiumFarosTemperature> = createCache("android_bittium_faros_temperature", BittiumFarosTemperature())
    private val batteryTopic: DataCache<ObservationKey, BittiumFarosBatteryLevel> = createCache("android_bittium_faros_battery_level", BittiumFarosBatteryLevel())

    private lateinit var acceptableIds: List<Regex>
    private lateinit var apiManager: FarosSdkManager
    private var settings: FarosSettings = FarosSettings()

    private var faros: FarosDevice? = null

    override fun start(acceptableIds: Set<String>) {
        logger.info("Faros searching for device.")
        service.getString(R.string.farosLabel)

        handler.start()
        handler.execute {
            this.acceptableIds = acceptableIds.map {
                it.toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.LITERAL))
            }

            apiManager = farosFactory.createSdkManager(service)
            try {
                val rawHandler = checkNotNull(handler.handler) { "Faros handler is null" }
                apiManager.startScanning(this, rawHandler)
                status = SourceStatusListener.Status.READY
            } catch (ex: IllegalStateException) {
                logger.error("Failed to start scanning", ex)
                disconnect()
            }
        }
    }

    override fun onStatusUpdate(status: FarosDeviceState) {
        val radarStatus = when(status) {
            FarosDeviceState.IDLE -> {
                handler.execute {
                    logger.debug("Faros status is IDLE. Request to start/restart measurements.")
                    applySettings(this.settings)
                    faros?.run {
                        requestBatteryLevel()
                        startMeasurements()
                    }
                }
                notificationHandler.manager?.cancel(FAROS_DISCONNECTED_NOTIFICATION_ID)
                SourceStatusListener.Status.CONNECTED
            }
            FarosDeviceState.CONNECTING -> SourceStatusListener.Status.CONNECTING
            FarosDeviceState.DISCONNECTED, FarosDeviceState.DISCONNECTING -> {
                disconnect()
                SourceStatusListener.Status.DISCONNECTING
            }
            FarosDeviceState.MEASURING -> SourceStatusListener.Status.CONNECTED
            else -> {
                logger.warn("Faros status {} is unknown", status)
                return
            }
        }
        logger.debug("Faros status {} and radarStatus {}", status, radarStatus)

        this.status = radarStatus
    }

    override fun onDeviceScanned(device: FarosDevice) {
        val deviceName = device.name

        val existingDevice = faros
        if (existingDevice != null) {
            logger.info("Faros device {} already set, ignoring {}", existingDevice.name, deviceName)
            return
        }
        logger.info("Found Faros device {}", deviceName)

        val attributes: Map<String, String> = mapOf(
            Pair(
                "type",
                when (device.model) {
                    FarosDevice.Model.Faros90 -> "FAROS_90"
                    FarosDevice.Model.Faros180 -> "FAROS_180"
                    FarosDevice.Model.Faros360 -> "FAROS_360"
                    else -> "unknown"
                },
            ),
        )

        if (
            acceptableIds.isEmpty() ||
            deviceName == null ||
            acceptableIds.any { it.containsMatchIn(deviceName) }
        ) {
            register(deviceName, deviceName, attributes) { sourceMetadata ->
                sourceMetadata ?: run {
                    logger.warn("Cannot register a Faros device")
                    return@register
                }

                handler.executeReentrant {
                    logger.info("Stopping scanning")
                    apiManager.stopScanning()
                    name = service.getString(R.string.farosDeviceName, deviceName)

                    val rawHandler = handler.handler ?: run {
                        logger.warn("Cannot connect to Faros device: the Manager Handler has stopped")
                        return@executeReentrant
                    }

                    logger.info("Connecting to device {}", deviceName)
                    device.connect(this, rawHandler)
                    this.faros = device
                    status = SourceStatusListener.Status.CONNECTING
                }
            }
        }
    }

    override fun didReceiveMeasurement(measurement: Measurement) {
        val currentTime = currentTime
        processAcceleration(currentTime, measurement)
        processEcg(currentTime, measurement)
        processTemperature(currentTime, measurement)
        processInterBeatInterval(currentTime, measurement)
        processBatteryStatus(currentTime, measurement)
    }

    private fun processAcceleration(currentTime: Double, measurement: Measurement) {
        val lastAcceleration: FloatArray? = measurement.accelerationIterator().fold(null as FloatArray?) { _, sample ->
            val array = sample.value
            send(
                accelerationTopic,
                BittiumFarosAcceleration(
                    sample.timestamp,
                    currentTime,
                    array[0],
                    array[1],
                    array[2],
                ),
            )
            array
        }
        if (lastAcceleration != null) {
            state.setAcceleration(
                lastAcceleration[0],
                lastAcceleration[1],
                lastAcceleration[2]
            )
        }
    }

    private fun processEcg(currentTime: Double, measurement: Measurement) {
        measurement.ecgIterator().forEach { sample ->
            val channels = sample.value
            send(
                ecgTopic,
                BittiumFarosEcg(
                    sample.timestamp,
                    currentTime,
                    channels[0],
                    channels.getOrNull(1),
                    channels.getOrNull(2),
                ),
            )
        }
    }

    private fun processTemperature(currentTime: Double, measurement: Measurement) {
        val temperature = measurement.temperature ?: return
        state.temperature = temperature
        send(temperatureTopic, BittiumFarosTemperature(measurement.timestamp, currentTime, temperature))
    }

    private fun processInterBeatInterval(currentTime: Double, measurement: Measurement) {
        val ibi = measurement.interBeatInterval ?: return
        state.heartRate = 60 / ibi
        send(ibiTopic, BittiumFarosInterBeatInterval(measurement.timestamp, currentTime, ibi))
    }

    private fun processBatteryStatus(currentTime: Double, measurement: Measurement) {
        // only send approximate battery levels if the battery level interval is disabled.
        val level = when(val status = measurement.batteryStatus) {
            BatteryStatus.CRITICAL -> 0.05f
            BatteryStatus.LOW      -> 0.175f
            BatteryStatus.MEDIUM   -> 0.5f
            BatteryStatus.FULL     -> 0.875f
            else -> {
                logger.warn("Unknown battery status {} passed", status)
                return
            }
        }
        state.batteryLevel = level
        send(batteryTopic, BittiumFarosBatteryLevel(measurement.timestamp, currentTime, level, false))
    }

    override fun didReceiveBatteryLevel(timestamp: Double, batteryLevel: Float) {
        state.batteryLevel = batteryLevel
        send(batteryTopic, BittiumFarosBatteryLevel(timestamp, currentTime, batteryLevel, true))
    }

    internal fun applySettings(settings: FarosSettings) {
        handler.executeReentrant {
            this.settings = settings
            faros?.run {
                if (isMeasuring) {
                    logger.info("Device is measuring. Stopping device before applying settings.")
                    stopMeasurements()
                    // will apply in onStatusUpdate(), when the device becomes idle.
                } else {
                    logger.info("Applying device settings {}", settings)
                    apply(settings)
                }
            }
        }
    }

    override fun disconnect() {
        if (!isClosed && doNotify) {
            notificationHandler.notify(
                id = FAROS_DISCONNECTED_NOTIFICATION_ID,
                channel = NotificationHandler.NOTIFICATION_CHANNEL_ALERT,
                includeStartIntent = true,
            ) {
                setContentTitle(service.getString(R.string.notification_faros_disconnected_title))
                setContentText(service.getString(R.string.notification_faros_disconnected_text))
            }
        }

        super.disconnect()
    }

    override fun onClose() {
        logger.info("Faros BT Closing device {}", this)

        handler.stop {
            try {
                faros?.close()
            } catch (e2: IOException) {
                logger.error("Faros socket close failed")
            } catch (npe: NullPointerException) {
                logger.info("Can't close an unopened socket")
            }

            apiManager.close()
        }
    }

    fun notifyDisconnect(doNotify: Boolean) {
        this.doNotify = doNotify
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FarosManager::class.java)

        private const val FAROS_DISCONNECTED_NOTIFICATION_ID = 27287
    }
}
