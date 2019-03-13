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

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import androidx.annotation.CallSuper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.*
import org.radarbase.android.auth.portal.SourceType
import org.radarbase.android.data.DataHandler
import org.radarbase.android.device.DeviceServiceProvider.Companion.NEEDS_BLUETOOTH_KEY
import org.radarbase.android.radarApp
import org.radarbase.android.util.BundleSerialization
import org.radarbase.android.util.ManagedServiceConnection
import org.radarbase.android.util.SafeHandler
import org.radarbase.android.util.send
import org.radarcns.kafka.ObservationKey
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.HashSet
import kotlin.collections.HashMap

/**
 * A service that manages a DeviceManager and a TableDataHandler to send addToPreferences the data of a
 * wearable device and send it to a Kafka REST proxy.
 *
 * Specific wearables should extend this class.
 */
abstract class DeviceService<T : BaseDeviceState> : Service(), DeviceStatusListener, LoginListener {
    val key = ObservationKey()

    /** Stops the device when bluetooth is disabled.  */
    private val mBluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action

            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        logger.warn("Bluetooth is off")
                        handler.execute { stopDeviceManager(unsetDeviceManager()) }
                    }
                    else -> logger.debug("Bluetooth is in state {}", state)
                }
            }
        }
    }
    @get:Synchronized
    @set:Synchronized
    var dataHandler: DataHandler<ObservationKey, SpecificRecord>? = null
    @get:Synchronized
    var deviceManager: DeviceManager<T>? = null
        private set
    private lateinit var mBinder: DeviceBinder<T>
    private var hasBluetoothPermission: Boolean = false
    lateinit var sources: Set<SourceMetadata>
    lateinit var sourceTypes: Set<SourceType>
    private lateinit var authConnection: AuthServiceConnection
    protected lateinit var config: RadarConfiguration
    private lateinit var radarConnection: ManagedServiceConnection<org.radarbase.android.IRadarBinder>
    private lateinit var handler: SafeHandler
    private var startFuture: SafeHandler.HandlerFuture? = null
    private lateinit var broadcaster: LocalBroadcastManager

    val state: T
        get() {
            return deviceManager?.state ?: defaultState.apply {
                id.apply {
                    setProjectId(key.getProjectId())
                    setUserId(key.getUserId())
                    setSourceId(key.getSourceId())
                }
            }
        }

    /**
     * Default state when no device manager is active.
     */
    protected abstract val defaultState: T

    private val expectedSourceNames: Set<String>
        get() = HashSet<String>(sources
                .map { it.expectedSourceName }
                .filter { it != null })

    open val isBluetoothConnectionRequired: Boolean
        get() = hasBluetoothPermission()

    @CallSuper
    override fun onCreate() {
        logger.info("Creating DeviceService {}", this)
        super.onCreate()
        sources = HashSet()
        sourceTypes = HashSet()
        broadcaster = LocalBroadcastManager.getInstance(this)

        mBinder = createBinder()

        if (isBluetoothConnectionRequired) {
            val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            registerReceiver(mBluetoothReceiver, intentFilter)
        }

        authConnection = AuthServiceConnection(this, this)
        authConnection.bind()

        radarConnection = ManagedServiceConnection(this, radarApp.radarService)
        radarConnection.onBoundListeners.add { binder ->
            dataHandler = binder.dataHandler
            handler.execute {
                startFuture?.runNow()
            }
        }
        radarConnection.bind()
        handler = SafeHandler(javaClass.simpleName, THREAD_PRIORITY_BACKGROUND)

        config = radarApp.configuration

        deviceManager = null
        startFuture = null
    }

    @CallSuper
    override fun onDestroy() {
        logger.info("Destroying DeviceService {}", this)
        super.onDestroy()

        radarConnection.unbind()
        authConnection.unbind()

        if (isBluetoothConnectionRequired) {
            // Unregister broadcast listeners
            unregisterReceiver(mBluetoothReceiver)
        }
        handler.stop { stopDeviceManager(unsetDeviceManager()) }

        radarApp.onDeviceServiceDestroy(this)
    }

    @CallSuper
    protected open fun configure(configuration: RadarConfiguration) {
        val localManager = deviceManager
        if (localManager != null) {
            configureDeviceManager(localManager, configuration)
        }
    }

    protected open fun configureDeviceManager(manager: DeviceManager<T>, configuration: RadarConfiguration) {}

    override fun onBind(intent: Intent): DeviceBinder<T> {
        doBind(intent, true)
        return mBinder
    }

    @CallSuper
    override fun onRebind(intent: Intent) {
        doBind(intent, false)
    }

    private fun doBind(intent: Intent, firstBind: Boolean) {
        logger.debug("Received (re)bind in {}", this)
        val extras = BundleSerialization.getPersistentExtras(intent, this) ?: Bundle()
        onInvocation(extras)

        radarApp.onDeviceServiceInvocation(this, extras, firstBind)
    }

    override fun onUnbind(intent: Intent): Boolean {
        logger.debug("Received unbind in {}", this)
        return true
    }

    override fun deviceFailedToConnect(name: String) {
        broadcaster.send(DEVICE_CONNECT_FAILED) {
            putExtra(DEVICE_SERVICE_CLASS, javaClass.name)
            putExtra(DEVICE_STATUS_NAME, name)
        }
    }

    private fun broadcastDeviceStatus(name: String?, status: DeviceStatusListener.Status) {
        broadcaster.send(DEVICE_STATUS_CHANGED) {
            putExtra(DEVICE_STATUS_CHANGED, status.ordinal)
            putExtra(DEVICE_SERVICE_CLASS, javaClass.name)
            name?.let { putExtra(DEVICE_STATUS_NAME, it) }
        }
    }

    override fun deviceStatusUpdated(deviceManager: DeviceManager<*>, status: DeviceStatusListener.Status) {
        if (status == DeviceStatusListener.Status.DISCONNECTED) {
            handler.execute(true) {
                if (this.deviceManager === deviceManager) {
                    this.deviceManager = null
                }
                stopDeviceManager(deviceManager)
            }
        }
        broadcastDeviceStatus(deviceManager.name, status)
    }

    @Synchronized
    private fun unsetDeviceManager(): DeviceManager<*>? {
        val tmpManager = deviceManager
        deviceManager = null
        handler.stop { }
        return tmpManager
    }

    private fun stopDeviceManager(deviceManager: DeviceManager<*>?) {
        if (deviceManager != null) {
            if (!deviceManager.isClosed) {
                try {
                    deviceManager.close()
                } catch (e: IOException) {
                    logger.warn("Failed to close device scanner", e)
                }

            }
        }
    }

    /**
     * New device manager for the current device.
     */
    protected abstract fun createDeviceManager(): DeviceManager<T>

    fun startRecording(acceptableIds: Set<String>) {
        if (key.getUserId() == null) {
            throw IllegalStateException("Cannot start recording: user ID is not set.")
        }
        val expectedNames = expectedSourceNames
        val actualIds = if (expectedNames.isEmpty()) acceptableIds else expectedNames

        handler.start()
        handler.execute {
            val localManager = deviceManager
            if (localManager == null) {
                if (isBluetoothConnectionRequired && BluetoothAdapter.getDefaultAdapter() != null && !BluetoothAdapter.getDefaultAdapter().isEnabled) {
                    logger.error("Cannot start recording without Bluetooth")
                    return@execute
                }
                if (radarConnection.binder != null && dataHandler != null) {
                    logger.info("Starting recording")
                    if (deviceManager == null) {
                        createDeviceManager().also { manager ->
                            deviceManager = manager
                            configureDeviceManager(manager, config)
                            manager.start(actualIds)
                        }
                    }
                } else {
                    startFuture = handler.delay(100) {
                        startFuture?.let {
                            startFuture = null
                            startRecording(acceptableIds)
                        }
                    }
                }
            }
        }
    }

    fun stopRecording() {
        handler.execute {
            startFuture?.let {
                it.cancel()
                startFuture = null
            }
            stopDeviceManager(unsetDeviceManager())
            logger.info("Stopped recording {}", this)
        }
    }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        key.setProjectId(authState.projectId)
        key.setUserId(authState.userId)
    }

    override fun loginFailed(manager: LoginManager?, ex: Exception?) {

    }

    /**
     * Override this function to get any parameters from the given intent.
     * Bundle classloader needs to be set correctly for this to work.
     *
     * @param bundle intent extras that the activity provided.
     */
    @CallSuper
    fun onInvocation(bundle: Bundle) {
        setHasBluetoothPermission(bundle.getBoolean(NEEDS_BLUETOOTH_KEY, false))

        configure(config)
    }

    /** Get the service local binder.  */
    protected fun createBinder(): DeviceBinder<T> {
        return DeviceBinder(this)
    }

    protected fun setHasBluetoothPermission(isRequired: Boolean) {
        val oldBluetoothNeeded = isBluetoothConnectionRequired
        hasBluetoothPermission = isRequired
        val newBluetoothNeeded = isBluetoothConnectionRequired

        if (oldBluetoothNeeded && !newBluetoothNeeded) {
            unregisterReceiver(mBluetoothReceiver)
        } else if (!oldBluetoothNeeded && newBluetoothNeeded) {
            // Register for broadcasts on BluetoothAdapter state change
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            registerReceiver(mBluetoothReceiver, filter)
        }
    }

    protected fun hasBluetoothPermission(): Boolean {
        return hasBluetoothPermission
    }

    private fun registerDevice(type: SourceType, name: String, attributes: Map<String, String>) {
        logger.info("Registering source {} with attributes {}", type, attributes)

        val source = SourceMetadata(type).apply {
            sourceName = name
            this.attributes = attributes
        }

        authConnection.binder?.registerSource(source,
                { authState, updatedSource ->
                    key.setProjectId(authState.projectId)
                    key.setUserId(authState.userId)
                    key.setSourceId(updatedSource.sourceId)
                    source.sourceId = updatedSource.sourceId
                    source.sourceName = updatedSource.sourceName
                    source.expectedSourceName = updatedSource.expectedSourceName
                    deviceManager?.didRegister(source)
                },
                { handler.delay(300_000L) { registerDevice(type, name, attributes) } })
                ?: handler.delay(300_000L) { registerDevice(type, name, attributes) }
    }

    open fun ensureRegistration(id: String?, name: String, attributes: Map<String, String>): Boolean {
        val fullAttributes = HashMap(attributes).apply {
            put("physicalId", id ?: "")
            put("physicalName", name)
        }
        return if (sources.isEmpty()) {
            matchingSourceType?.let { registerDevice(it, name, fullAttributes) } != null
        } else {
            val matchingSource = sources
                    .find { source ->
                        if (source.matches(id, name)) {
                            true
                        } else if (id != null && source.attributes["physicalId"]?.isEmpty() == false) {
                            source.attributes["physicalId"] == id
                        } else if (source.attributes["physicalName"]?.isEmpty() == false) {
                            source.attributes["physicalName"] == name
                        } else {
                            false
                        }
                    }

            if (matchingSource == null) {
                matchingSourceType?.let { registerDevice(it, name, fullAttributes) } != null
            } else {
                deviceManager?.didRegister(matchingSource)
                true
            }
        }
    }

    private val matchingSourceType: SourceType?
        get() = sourceTypes
                    .filter { it.hasDynamicRegistration }
                    .sortedBy { it.catalogVersion }
                    .firstOrNull()

    override fun toString(): String {
        val localManager = deviceManager
        return if (localManager == null) {
            javaClass.simpleName + "<null>"
        } else {
            javaClass.simpleName + "<" + localManager.name + ">"
        }
    }

    companion object {
        private const val PREFIX = "org.radarcns.android."
        const val SERVER_STATUS_CHANGED = PREFIX + "ServerStatusListener.Status"
        const val SERVER_RECORDS_SENT_TOPIC = PREFIX + "ServerStatusListener.topic"
        const val SERVER_RECORDS_SENT_NUMBER = PREFIX + "ServerStatusListener.lastNumberOfRecordsSent"
        const val CACHE_TOPIC = PREFIX + "DataCache.topic"
        const val CACHE_RECORDS_UNSENT_NUMBER = PREFIX + "DataCache.numberOfRecords.first"
        const val DEVICE_SERVICE_CLASS = PREFIX + "DeviceService.getClass"
        const val DEVICE_STATUS_CHANGED = PREFIX + "DeviceStatusListener.Status"
        const val DEVICE_STATUS_NAME = PREFIX + "DeviceManager.getName"
        const val DEVICE_CONNECT_FAILED = PREFIX + "DeviceStatusListener.deviceFailedToConnect"

        private val logger = LoggerFactory.getLogger(DeviceService::class.java)
    }
}
