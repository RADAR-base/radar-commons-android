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

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.RadarConfiguration.Companion.PROJECT_ID_KEY
import org.radarbase.android.RadarConfiguration.Companion.RADAR_CONFIGURATION_CHANGED
import org.radarbase.android.RadarConfiguration.Companion.UI_REFRESH_RATE_KEY
import org.radarbase.android.RadarConfiguration.Companion.USER_ID_KEY
import org.radarbase.android.RadarService.Companion.ACTION_BLUETOOTH_NEEDED_CHANGED
import org.radarbase.android.RadarService.Companion.ACTION_CHECK_PERMISSIONS
import org.radarbase.android.RadarService.Companion.ACTION_PROVIDERS_UPDATED
import org.radarbase.android.RadarService.Companion.EXTRA_PERMISSIONS
import org.radarbase.android.auth.AuthService
import org.radarbase.android.util.*
import org.slf4j.LoggerFactory

/** Base MainActivity class. It manages the services to collect the data and starts up a view. To
 * create an application, extend this class and override the abstract methods.  */
abstract class MainActivity : AppCompatActivity() {

    /** Time between refreshes.  */
    private var uiRefreshRate: Long = 0

    /** Hander in the background. It is set to null whenever the activity is not running.  */
    private lateinit var mHandler: SafeHandler

    /** The UI to show the service data.  */
    @get:Synchronized
    var view: MainActivityView? = null
        private set

    private var configurationBroadcastReceiver: BroadcastRegistration? = null
    private lateinit var permissionHandler: PermissionHandler
    private var radarServiceIsStarted: Boolean = false
    protected lateinit var authConnection: ManagedServiceConnection<AuthService.AuthServiceBinder>
    protected lateinit var radarConnection: ManagedServiceConnection<IRadarBinder>

    /** Defines callbacks for service binding, passed to bindService()  */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                logger.debug("Bluetooth state {}", state)
                // Upon state change, restart ui handler and restart Scanning.
                if (state == BluetoothAdapter.STATE_OFF) {
                    requestEnableBt()
                }
            }
        }
    }
    private var bluetoothNeededReceiver: BroadcastRegistration? = null

    @Volatile
    private var bluetoothReceiverIsEnabled: Boolean = false
    protected lateinit var configuration: RadarConfiguration
    private var connectionsUpdatedReceiver: BroadcastRegistration? = null

    protected open val requestPermissionTimeoutMs: Long
        get() = REQUEST_PERMISSION_TIMEOUT_MS

    val radarService: IRadarBinder?
        get() = radarConnection.binder

    val userId: String?
        get() = configuration.optString(USER_ID_KEY)

    val projectId: String?
        get() = configuration.optString(PROJECT_ID_KEY)

    /**
     * Sends an intent to request bluetooth to be turned on.
     */
    protected fun requestEnableBt() {
        if (!BluetoothAdapter.getDefaultAdapter().isEnabled) {
            applicationContext.startActivity(Intent().apply {
                action = BluetoothAdapter.ACTION_REQUEST_ENABLE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun testBindBluetooth() {
        radarConnection.applyBinder { binder ->
            val needsBluetooth = binder.needsBluetooth()

            if (needsBluetooth != bluetoothReceiverIsEnabled) {
                if (needsBluetooth) {
                    registerReceiver(bluetoothReceiver,
                            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
                    bluetoothReceiverIsEnabled = true
                    requestEnableBt()
                } else {
                    bluetoothReceiverIsEnabled = false
                    unregisterReceiver(bluetoothReceiver)
                }
            }
        }
    }

    @CallSuper
    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        permissionHandler.saveInstanceState(savedInstanceState)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mHandler = SafeHandler("Service connection", Process.THREAD_PRIORITY_BACKGROUND)
        bluetoothReceiverIsEnabled = false
        permissionHandler = PermissionHandler(this, mHandler, requestPermissionTimeoutMs)

        savedInstanceState?.also { permissionHandler.restoreInstanceState(it) }

        radarConnection = ManagedServiceConnection(this@MainActivity, RadarService::class.java)
        radarConnection.bindFlags = Context.BIND_ABOVE_CLIENT
        radarConnection.onBoundListeners += IRadarBinder::startScanning
        radarConnection.onUnboundListeners += IRadarBinder::stopScanning

        configuration = radarApp.configuration
        authConnection = ManagedServiceConnection(this, AuthService::class.java)
        create()
    }

    @CallSuper
    protected fun create() {
        logger.info("RADAR configuration at create: {}", configuration)
        onConfigChanged()

        configurationBroadcastReceiver = LocalBroadcastManager.getInstance(this)
                .register(RADAR_CONFIGURATION_CHANGED) { _, _ -> onConfigChanged() }

        // Start the UI thread
        uiRefreshRate = configuration.getLong(UI_REFRESH_RATE_KEY)
    }

    override fun onDestroy() {
        super.onDestroy()

        configurationBroadcastReceiver?.unregister()
    }

    /**
     * Called whenever the RadarConfiguration is changed. This can be at activity start or
     * when the configuration is updated from Firebase.
     */
    @CallSuper
    protected fun onConfigChanged() {

    }

    /** Create a view to show the data of this activity.  */
    protected abstract fun createView(): MainActivityView

    override fun onResume() {
        super.onResume()
        mHandler.repeatWhile(uiRefreshRate) {
            try {
                // Update all rows in the UI with the data from the connections
                view?.update()
            } catch (ex: Exception) {
                logger.error("Failed to update view")
            }
            lifecycle.currentState == Lifecycle.State.RESUMED
        }
    }

    public override fun onStart() {
        super.onStart()
        mHandler.start()

        authConnection.bind()

        permissionHandler.invalidateCache()

        val radarServiceCls = (application as RadarApplication).radarService
        if (!radarServiceIsStarted) {
            try {
                val intent = Intent(this, radarServiceCls)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                radarServiceIsStarted = true
            } catch (ex: IllegalStateException) {
                logger.error("Failed to start RadarService: activity is in background.", ex)
            }
        }
        if (radarServiceIsStarted) {
            radarConnection.bind()
        }

        testBindBluetooth()

        LocalBroadcastManager.getInstance(this).apply {
            bluetoothNeededReceiver = register(ACTION_BLUETOOTH_NEEDED_CHANGED) { _, _ ->
                testBindBluetooth()
            }
            connectionsUpdatedReceiver = register(ACTION_PROVIDERS_UPDATED) { _, _ ->
                synchronized(this@MainActivity) {
                    view = createView()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (ACTION_CHECK_PERMISSIONS == intent.action) {
            permissionHandler.replaceNeededPermissions(intent.getStringArrayExtra(EXTRA_PERMISSIONS))
        }

        super.onNewIntent(intent)
    }

    public override fun onStop() {
        super.onStop()

        mHandler.stop { view = null }

        if (radarServiceIsStarted) {
            radarConnection.unbind()
        }

        authConnection.unbind()

        bluetoothNeededReceiver?.unregister()
        connectionsUpdatedReceiver?.unregister()

        if (bluetoothReceiverIsEnabled) {
            bluetoothReceiverIsEnabled = false
            unregisterReceiver(bluetoothReceiver)
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        permissionHandler.onActivityResult(requestCode, resultCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHandler.permissionsGranted(requestCode, permissions, grantResults)
    }

    /**
     * Log out of the current application.
     * @param disableRefresh if `true`, also remove any refresh tokens; if `false`, just remove
     * the access token but allow the same user to automatically log in again if it is
     * still valid.
     */
    protected fun logout(disableRefresh: Boolean) {
        authConnection.applyBinder { it.invalidate(null, disableRefresh) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MainActivity::class.java)

        private const val REQUEST_PERMISSION_TIMEOUT_MS = 86_400_000L // 1 day
    }
}
