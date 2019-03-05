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

package org.radarcns.android

import android.arch.lifecycle.Lifecycle
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.support.annotation.CallSuper
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity
import org.radarcns.android.RadarService.Companion.ACTION_BLUETOOTH_NEEDED_CHANGED
import org.radarcns.android.RadarService.Companion.ACTION_PROVIDERS_UPDATED
import org.radarcns.android.auth.AuthService
import org.radarcns.android.util.ManagedServiceConnection
import org.radarcns.android.util.PermissionHandler
import org.radarcns.android.util.SafeHandler
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

    private lateinit var configurationBroadcastReceiver: BroadcastReceiver
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
    private val bluetoothNeededReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_BLUETOOTH_NEEDED_CHANGED) {
                testBindBluetooth()
            }
        }
    }

    @Volatile
    private var bluetoothReceiverIsEnabled: Boolean = false
    protected lateinit var configuration: RadarConfiguration
    private lateinit var connectionsUpdatedReceiver: BroadcastReceiver

    protected open val requestPermissionTimeoutMs: Long
        get() = REQUEST_PERMISSION_TIMEOUT_MS

    val radarService: IRadarBinder?
        get() = radarConnection.binder

    val userId: String
        get() = configuration.getString(RadarConfiguration.USER_ID_KEY, null)

    val projectId: String
        get() = configuration.getString(RadarConfiguration.PROJECT_ID_KEY, null)

    private val localBroadcastManager: LocalBroadcastManager
        get() = LocalBroadcastManager.getInstance(this)

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

        connectionsUpdatedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                synchronized(this@MainActivity) {
                    view = createView()
                }
            }
        }
        configuration = (application as RadarApplication).configuration
        authConnection = ManagedServiceConnection(this, AuthService::class.java)
        configurationBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onConfigChanged()
            }
        }
        create()
    }

    @CallSuper
    protected fun create() {
        logger.info("RADAR configuration at create: {}", configuration)
        onConfigChanged()

        localBroadcastManager.registerReceiver(configurationBroadcastReceiver,
                        IntentFilter(RadarConfiguration.RADAR_CONFIGURATION_CHANGED))

        // Start the UI thread
        uiRefreshRate = configuration.getLong(RadarConfiguration.UI_REFRESH_RATE_KEY)
    }

    override fun onDestroy() {
        super.onDestroy()

        localBroadcastManager.unregisterReceiver(configurationBroadcastReceiver)
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
        mHandler.postDelayed({
            try {
                // Update all rows in the UI with the data from the connections
                view?.update()
            } catch (ex: Exception) {
                logger.error("Failed to update view")
            }
            lifecycle.currentState == Lifecycle.State.RESUMED
        }, uiRefreshRate)
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

        localBroadcastManager.apply {
            registerReceiver(bluetoothNeededReceiver, IntentFilter(ACTION_BLUETOOTH_NEEDED_CHANGED))
            registerReceiver(connectionsUpdatedReceiver, IntentFilter(ACTION_PROVIDERS_UPDATED))
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (RadarService.ACTION_CHECK_PERMISSIONS == intent.action) {
            permissionHandler.replaceNeededPermissions(intent.getStringArrayExtra(RadarService.EXTRA_PERMISSIONS))
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

        localBroadcastManager.apply {
            unregisterReceiver(bluetoothNeededReceiver)
            unregisterReceiver(connectionsUpdatedReceiver)
        }

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
