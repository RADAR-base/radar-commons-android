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

package org.radarbase.android

import android.Manifest.permission.*
import android.app.AppOpsManager
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.LocationManager
import android.os.*
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.widget.Toast
import androidx.annotation.CallSuper
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.apache.avro.specific.SpecificRecord
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_DEFAULT
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_KEY
import org.radarbase.android.auth.*
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.data.CacheStore
import org.radarbase.android.data.DataHandler
import org.radarbase.android.data.TableDataHandler
import org.radarbase.android.kafka.ServerStatusListener
import org.radarbase.android.source.*
import org.radarbase.android.source.SourceService.Companion.SERVER_RECORDS_SENT_NUMBER
import org.radarbase.android.source.SourceService.Companion.SERVER_RECORDS_SENT_TOPIC
import org.radarbase.android.source.SourceService.Companion.SERVER_STATUS_CHANGED
import org.radarbase.android.source.SourceService.Companion.SOURCE_CONNECT_FAILED
import org.radarbase.android.util.*
import org.radarbase.android.util.NotificationHandler.Companion.NOTIFICATION_CHANNEL_INFO
import org.radarcns.kafka.ObservationKey
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

abstract class RadarService : LifecycleService(), ServerStatusListener, LoginListener {
    private var configurationUpdateFuture: SafeHandler.HandlerFuture? = null
    private val fetchTimeout = ChangeRunner<Long>()
    private lateinit var mainHandler: Handler
    private var binder: IBinder? = null
    protected open val showSourceStatus: Boolean = false

    var dataHandler: DataHandler<ObservationKey, SpecificRecord>? = null
        private set

    open val cacheStore: CacheStore = CacheStore()

    private lateinit var mHandler: SafeHandler
    private var needsBluetooth = ChangeRunner(false)
    protected lateinit var configuration: RadarConfiguration

    /** Filters to only listen to certain source IDs or source names.  */
    private val sourceFilters: MutableMap<SourceServiceConnection<*>, Set<String>> = HashMap()

    private lateinit var providerLoader: SourceProviderLoader
    private lateinit var authConnection: AuthServiceConnection
    private lateinit var permissionsBroadcastReceiver: BroadcastRegistration
    private lateinit var sourceFailedReceiver: BroadcastRegistration
    private lateinit var serverStatusReceiver: BroadcastRegistration
    private var sourceRegistrar: SourceProviderRegistrar? = null
    private val configuredProviders = ChangeRunner<List<SourceProvider<*>>>()

    private val isMakingAuthRequest = AtomicBoolean(false)

    abstract val plugins: List<SourceProvider<*>>

    /** Connections.  */
    private var mConnections: List<SourceProvider<*>> = emptyList()
    private var isScanningEnabled = false

    /** An overview of how many records have been sent throughout the application.  */
    private var latestNumberOfRecordsSent = TimedLong(0)

    /** Current server status.  */
    private lateinit var serverStatus: ServerStatusListener.Status

    private val needsPermissions = LinkedHashSet<String>()

    protected open val servicePermissions: List<String>
        get() = listOf(ACCESS_NETWORK_STATE, INTERNET)

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    private lateinit var broadcaster: LocalBroadcastManager

    private var bluetoothNotification: NotificationHandler.NotificationRegistration? = null

    /** Defines callbacks for service binding, passed to bindService()  */
    private lateinit var bluetoothReceiver: BluetoothStateReceiver

    override fun onCreate() {
        super.onCreate()
        serverStatus = ServerStatusListener.Status.DISABLED
        binder = createBinder()
        mHandler = SafeHandler.getInstance("RadarService", THREAD_PRIORITY_BACKGROUND).apply {
            start()
        }
        mainHandler = Handler()

        configuration = radarConfig
        providerLoader = SourceProviderLoader(plugins)
        broadcaster = LocalBroadcastManager.getInstance(this)

        broadcaster.run {
            permissionsBroadcastReceiver = register(ACTION_PERMISSIONS_GRANTED) { _, intent ->
                val extraPermissions = intent.getStringArrayExtra(EXTRA_PERMISSIONS) ?: return@register
                val extraGrants = intent.getIntArrayExtra(EXTRA_GRANT_RESULTS) ?: return@register
                onPermissionsGranted(
                        extraPermissions,
                        extraGrants)
            }
            sourceFailedReceiver = register(SOURCE_CONNECT_FAILED) { context, intent ->
                Boast.makeText(context,
                        getString(R.string.cannot_connect_device,
                                intent.getStringExtra(SourceService.SOURCE_STATUS_NAME)),
                        Toast.LENGTH_SHORT).show()
            }
            serverStatusReceiver = register(SERVER_STATUS_CHANGED) { _, intent ->
                serverStatus = ServerStatusListener.Status.values()[intent.getIntExtra(SERVER_STATUS_CHANGED, 0)]
                if (serverStatus == ServerStatusListener.Status.UNAUTHORIZED) {
                    logger.debug("Status unauthorized")
                    authConnection.applyBinder {
                        if (isMakingAuthRequest.compareAndSet(false, true)) {
                            invalidate(null, false)
                            refresh()
                        }
                    }
                }
            }
        }

        configuration.config.observe(this, ::configure)

        authConnection = AuthServiceConnection(this, this).apply {
            bind()
        }
        authConnection.onUnboundListeners += {
            mHandler.execute {
                sourceRegistrar?.let {
                    it.close()
                    sourceRegistrar = null
                }
            }
        }

        bluetoothReceiver = BluetoothStateReceiver(this) { enabled ->
            // Upon state change, restart ui handler and restart Scanning.
            if (enabled) {
                bluetoothNotification?.cancel()
                startScanning()
            } else {
                mConnections.map { it.connection }
                        .filter { it.needsBluetooth() }
                        .forEach { it.stopRecording() }

                bluetoothNotification = radarApp.notificationHandler
                        .notify(BLUETOOTH_NOTIFICATION, NotificationHandler.NOTIFICATION_CHANNEL_ALERT, false) {
                            setContentTitle(getString(R.string.notification_bluetooth_needed_title))
                            setContentText(getString(R.string.notification_bluetooth_needed_text))
                        }
            }
        }
    }

    protected open fun createBinder(): IBinder {
        return RadarBinder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        configure(configuration.latestConfig)
        checkPermissions()
        startForeground(1, createForegroundNotification())

        return START_STICKY
    }

    protected open fun createForegroundNotification(): Notification {
        val mainIntent = Intent(this, radarApp.mainActivity)
        return radarApp.notificationHandler
                .create(NOTIFICATION_CHANNEL_INFO, true) {
                    setContentText(getText(R.string.service_notification_text))
                    setContentTitle(getText(R.string.service_notification_title))
                    setContentIntent(PendingIntent.getActivity(this@RadarService, 0, mainIntent, 0))
                }
    }

    override fun onDestroy() {
        if (needsBluetooth.value) {
            bluetoothReceiver.unregister()
        }
        permissionsBroadcastReceiver.unregister()
        sourceFailedReceiver.unregister()
        serverStatusReceiver.unregister()

        mHandler.stop {
            sourceRegistrar?.let {
                it.close()
                sourceRegistrar = null
            }
        }
        authConnection.unbind()

        mConnections.filter(SourceProvider<*>::isBound)
                .forEach(SourceProvider<*>::unbind)

        super.onDestroy()
    }

    @CallSuper
    protected open fun configure(config: SingleRadarConfiguration) {
       mHandler.executeReentrant {
           doConfigure(config)

           fetchTimeout.applyIfChanged(config.getLong(FETCH_TIMEOUT_MS_KEY, FETCH_TIMEOUT_MS_DEFAULT)) { timeout ->
               configurationUpdateFuture?.cancel()
               configurationUpdateFuture = mHandler.repeat(timeout) {
                   configuration.fetch()
               }
           }
       }
    }

    @CallSuper
    protected open fun doConfigure(config: SingleRadarConfiguration) {
        synchronized(this) {
            dataHandler ?: TableDataHandler(this, cacheStore)
                    .also {
                        dataHandler = it
                        it.statusListener = this
                    }
        }.handler {
            configure(config)
        }

        authConnection.applyBinder {
            applyState {
                mHandler.execute {
                    updateProviders(this, config)
                }
            }
        }
    }

    private fun hasFeatures(provider: SourceProvider<*>, packageManager: PackageManager?): Boolean {
        return packageManager?.let { manager ->
            provider.featuresNeeded.all { manager.hasSystemFeature(it) }
        } != false
    }

    private fun requestPermissions(permissions: Collection<String>) {
        startActivity(Intent(this, radarApp.mainActivity).apply {
            action = ACTION_CHECK_PERMISSIONS
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_PERMISSIONS, permissions.toTypedArray())
        })
    }

    private fun onPermissionsGranted(permissions: Array<String>, grantResults: IntArray) {
        val grantedPermissions = grantResults.asList()
                .mapIndexedNotNull { index, granted ->
                    val permission = permissions[index]
                    if (granted == PERMISSION_GRANTED) {
                        permission
                    } else {
                        logger.info("Denied permission {}", permission)
                        null
                    }
                }

        if (grantedPermissions.isNotEmpty()) {
            mHandler.execute {
                logger.info("Granted permissions {}", grantedPermissions)
                // Permission granted.
                needsPermissions -= grantedPermissions
                startScanning()
            }
        }
    }

    fun serviceConnected(connection: SourceServiceConnection<*>) {
        mHandler.execute {
            if (!isScanningEnabled) {
                getConnectionProvider(connection)?.also { provider ->
                    if (!provider.mayBeConnectedInBackground) {
                        provider.unbind()
                    }
                }
            }
            connection.serverStatus
                    ?.also { logger.debug("Initial server status: {}", it) }
                    ?.also(::updateServerStatus)

            updateBluetoothNeeded(needsBluetooth.value || connection.needsBluetooth())
            startScanning()
        }
    }

    private fun updateBluetoothNeeded(newValue: Boolean) {
        needsBluetooth.applyIfChanged(newValue) {
            if (newValue) {
                bluetoothReceiver.register()

                broadcaster.send(ACTION_BLUETOOTH_NEEDED_CHANGED) {
                    putExtra(ACTION_BLUETOOTH_NEEDED_CHANGED, BLUETOOTH_NEEDED)
                }
            } else {
                bluetoothReceiver.unregister()

                broadcaster.send(ACTION_BLUETOOTH_NEEDED_CHANGED) {
                    putExtra(ACTION_BLUETOOTH_NEEDED_CHANGED, BLUETOOTH_NOT_NEEDED)
                }
            }
        }
    }

    fun serviceDisconnected(connection: SourceServiceConnection<*>) {
        mHandler.execute {
            getConnectionProvider(connection)?.also { provider ->
                bindServices(listOf(provider), true)
            }
        }
    }

    private fun bindServices(providers: Collection<SourceProvider<*>>, unbindFirst: Boolean) {
        mHandler.executeReentrant {
            val authBinder = authConnection.binder
            if (authBinder == null) {
                mHandler.delay(1000) { bindServices(providers, unbindFirst) }
            } else {
                if (unbindFirst) {
                    providers.filter { it.isBound }
                            .forEach { provider ->
                                logger.info("Rebinding {} after disconnect", provider)
                                provider.unbind()
                            }
                }
                providers.filter { !it.isBound && (isScanningEnabled || it.mayBeConnectedInBackground) }
                        .forEach(SourceProvider<*>::bind)
            }
        }
    }

    override fun updateServerStatus(status: ServerStatusListener.Status) {
        if (status == this.serverStatus) {
            return
        }
        this.serverStatus = status
        if (status == ServerStatusListener.Status.DISCONNECTED) {
            this.latestNumberOfRecordsSent = TimedLong(-1)
        }

        broadcaster.send(SERVER_STATUS_CHANGED) {
            putExtra(SERVER_STATUS_CHANGED, status.ordinal)
        }
    }

    override fun updateRecordsSent(topicName: String, numberOfRecords: Long) {
        this.latestNumberOfRecordsSent = TimedLong(numberOfRecords)

        broadcaster.send(SERVER_RECORDS_SENT_TOPIC) {
            // Signal that a certain topic changed, the key of the map retrieved by getRecordsSent().
            putExtra(SERVER_RECORDS_SENT_TOPIC, topicName)
            putExtra(SERVER_RECORDS_SENT_NUMBER, numberOfRecords)
        }
    }

    fun sourceStatusUpdated(connection: SourceServiceConnection<*>, status: SourceStatusListener.Status) {
        logger.info("Source of {} was updated to {}", connection, status)
        if (status == SourceStatusListener.Status.CONNECTED) {
            logger.info("Device name is {} while connecting.", connection.sourceName)
        }
        if (status == SourceStatusListener.Status.DISCONNECTED) {
            startScanning()
        }
        if (showSourceStatus) {
            mainHandler.post {
                val showRes = when (status) {
                    SourceStatusListener.Status.READY -> R.string.device_ready
                    SourceStatusListener.Status.CONNECTED -> R.string.device_connected
                    SourceStatusListener.Status.CONNECTING -> R.string.device_connecting
                    SourceStatusListener.Status.DISCONNECTING -> return@post  // do not show toast
                    SourceStatusListener.Status.DISCONNECTED -> R.string.device_disconnected
                    SourceStatusListener.Status.UNAVAILABLE -> R.string.device_unavailable
                }
                Boast.makeText(this@RadarService, showRes).show()
            }
        }
    }

    protected fun getConnectionProvider(connection: SourceServiceConnection<*>): SourceProvider<*>? {
        return mConnections.find { it.connection == connection }
    }

    protected fun startScanning() {
        mHandler.executeReentrant {
            mConnections
                    .filter { it.connection.hasService()
                            && !it.connection.isRecording
                            && it.checkPermissions() }
                    .forEach { provider ->
                        val connection = provider.connection
                        logger.info("Starting recording on connection {}", connection)
                        connection.startRecording(sourceFilters[connection] ?: emptySet())
                    }
        }
    }

    protected fun checkPermissions() {
        needsPermissions.clear()
        needsPermissions += HashSet<String>()
                .apply {
                    this += servicePermissions
                    this += mConnections.flatMap { it.permissionsNeeded }
                    if (ACCESS_FINE_LOCATION in this || ACCESS_COARSE_LOCATION in this) {
                        this += LOCATION_SERVICE
                    }
                }
                .filterNot(::isPermissionGranted)

        if (needsPermissions.isNotEmpty()) {
            logger.debug("Requesting permission for {}", needsPermissions)
            requestPermissions(needsPermissions)
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return when (permission) {
            LOCATION_SERVICE -> applySystemService<LocationManager, Boolean>(Context.LOCATION_SERVICE) { locationManager ->
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } ?: true
            REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT -> applySystemService<PowerManager, Boolean>(Context.POWER_SERVICE) { powerManager ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || powerManager.isIgnoringBatteryOptimizations(applicationContext.packageName)
            } ?: true
            PACKAGE_USAGE_STATS_COMPAT -> applySystemService<AppOpsManager, Boolean>(Context.APP_OPS_SERVICE) { appOps ->
                @Suppress("DEPRECATION")
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || MODE_ALLOWED == appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), packageName)
            } ?: true
            else -> PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, permission)
        }
    }


    /** Configure whether a boot listener should start this application at boot.  */
    @Suppress("unused")
    protected open fun configureRunAtBoot(config: SingleRadarConfiguration, bootReceiver: Class<*>) {
        val receiver = ComponentName(applicationContext, bootReceiver)
        val pm = applicationContext.packageManager

        val startAtBoot = config.getBoolean(RadarConfiguration.START_AT_BOOT, false)
        val isStartedAtBoot = pm.getComponentEnabledSetting(receiver) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (startAtBoot && !isStartedAtBoot) {
            logger.info("From now on, this application will start at boot")
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP)
        } else if (!startAtBoot && isStartedAtBoot) {
            logger.info("Not starting application at boot anymore")
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
        }
    }

    protected open fun SourceProvider<*>.checkPermissions(): Boolean {
        val stillNeeded = permissionsNeeded.filter(needsPermissions::contains)
        return if (stillNeeded.isEmpty()) {
            true
        } else {
            logger.info("Need permissions {} to start plugin {}", stillNeeded, pluginName)
            false
        }
    }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        isMakingAuthRequest.set(false)
        mHandler.execute {
            updateProviders(authState, configuration.latestConfig)
        }
    }

    private fun removeProviders(sourceProviders: List<SourceProvider<*>>) {
        if (sourceProviders.isEmpty()) {
            return
        }
        logger.info("Removing plugins {}", sourceProviders.map { it.pluginName })
        mConnections = mConnections.filterNot(sourceProviders::contains)
        updateBluetoothNeeded(mConnections.any { it.isConnected && it.connection.needsBluetooth() })
        sourceProviders.forEach(SourceProvider<*>::unbind)
    }

    private fun addProviders(sourceProviders: List<SourceProvider<*>>) {
        if (sourceProviders.isEmpty()) {
            return
        }
        logger.info("Adding plugins {}", sourceProviders.map { it.pluginName })
        mConnections = mConnections + sourceProviders

        sourceFilters += sourceProviders.map { Pair(it.connection, emptySet()) }

        checkPermissions()
        bindServices(mConnections, false)
    }

    private fun createRegistrar(authServiceBinder: AuthService.AuthServiceBinder, providers: List<SourceProvider<*>>) {
        logger.info("Creating source registration")
        sourceRegistrar = SourceProviderRegistrar(authServiceBinder, mHandler, providers) { unregisteredProviders, registeredProviders ->
            logger.info("Registered providers: {}, unregistered providers: {}", registeredProviders.map { it.pluginName }, unregisteredProviders.map { it.pluginName })
            val oldConnections = mConnections
            removeProviders(unregisteredProviders.filter(mConnections::contains))
            addProviders(registeredProviders.filterNot(mConnections::contains))
            if (mConnections != oldConnections) {
                broadcaster.send(ACTION_PROVIDERS_UPDATED)
            }
        }
    }

    private fun updateProviders(authState: AppAuthState, config: SingleRadarConfiguration) {
        dataHandler?.handler {
            logger.info("Setting data submission authentication")
            rest {
                headers = authState.okHttpHeaders
            }
            submitter {
                userId = authState.userId
            }
        }

        val packageManager = packageManager

        configuredProviders.applyIfChanged(providerLoader.loadProvidersFromNames(config)
                .filter { hasFeatures(it, packageManager) }) { providers ->
            val oldConnections = mConnections
            removeProviders(mConnections.filter { it !in providers })

            sourceRegistrar?.let {
                it.close()
                sourceRegistrar = null
            }
            authConnection.applyBinder { createRegistrar(this, providers) }

            if (mConnections != oldConnections) {
                broadcaster.send(ACTION_PROVIDERS_UPDATED)
            }
        }
    }

    override fun loginFailed(manager: LoginManager?, ex: Exception?) {
        isMakingAuthRequest.set(false)
    }

    protected inner class RadarBinder : Binder(), IRadarBinder {
        override fun startScanning() = this@RadarService.startActiveScanning()
        override fun stopScanning() = this@RadarService.stopActiveScanning()

        override val serverStatus: ServerStatusListener.Status
            get() = this@RadarService.serverStatus

        override val latestNumberOfRecordsSent: TimedLong
            get() = this@RadarService.latestNumberOfRecordsSent

        override val connections: List<SourceProvider<*>>
            get() = mConnections

        override fun setAllowedSourceIds(connection: SourceServiceConnection<*>, allowedIds: Collection<String>) {
            sourceFilters[connection] = allowedIds.sanitizeIds()

            mHandler.execute {
                val status = connection.sourceStatus

                if (status == SourceStatusListener.Status.READY
                        || status == SourceStatusListener.Status.CONNECTING
                        || status == SourceStatusListener.Status.CONNECTED && !connection.isAllowedSource(allowedIds)) {
                    if (connection.isRecording) {
                        connection.stopRecording()
                        // will restart recording once the status is set to disconnected.
                    }
                }
            }
        }

        override val dataHandler: DataHandler<ObservationKey, SpecificRecord>?
            get() = this@RadarService.dataHandler

        override fun needsBluetooth(): Boolean = needsBluetooth.value
    }

    private fun stopActiveScanning() {
        mHandler.execute {
            isScanningEnabled = false
            mConnections
                    .filter { it.isConnected && it.connection.mayBeDisabledInBackground() }
                    .forEach(SourceProvider<*>::unbind)
        }
    }

    private fun startActiveScanning() {
        mHandler.execute {
            isScanningEnabled = true
            bindServices(mConnections, false)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RadarService::class.java)
        private const val RADAR_PACKAGE = "org.radarbase.android"

        const val ACTION_PROVIDERS_UPDATED = "$RADAR_PACKAGE.ACTION_PROVIDERS_UPDATED"

        const val ACTION_BLUETOOTH_NEEDED_CHANGED = "$RADAR_PACKAGE.BLUETOOTH_NEEDED_CHANGED"
        const val BLUETOOTH_NEEDED = 1
        const val BLUETOOTH_NOT_NEEDED = 2

        const val ACTION_CHECK_PERMISSIONS = "$RADAR_PACKAGE.ACTION_CHECK_PERMISSIONS"
        const val EXTRA_PERMISSIONS = "$RADAR_PACKAGE.EXTRA_PERMISSIONS"

        const val ACTION_PERMISSIONS_GRANTED = "$RADAR_PACKAGE.ACTION_PERMISSIONS_GRANTED"
        const val EXTRA_GRANT_RESULTS = "$RADAR_PACKAGE.EXTRA_GRANT_RESULTS"

        private const val BLUETOOTH_NOTIFICATION = 521290

        val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            REQUEST_IGNORE_BATTERY_OPTIMIZATIONS else "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        val PACKAGE_USAGE_STATS_COMPAT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PACKAGE_USAGE_STATS else "android.permission.PACKAGE_USAGE_STATS"

        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T, U> Context.applySystemService(type: String, callback: (T) -> U): U? {
            return (getSystemService(type) as T?)?.let(callback)
        }

        fun Collection<String>.sanitizeIds(): Set<String> = HashSet(mapNotNull(String::takeTrimmedIfNotEmpty))
    }
}
