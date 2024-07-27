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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.analytics.FirebaseAnalytics
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.PROJECT_ID_KEY
import org.radarbase.android.RadarConfiguration.Companion.RADAR_CONFIGURATION_CHANGED
import org.radarbase.android.RadarConfiguration.Companion.UI_REFRESH_RATE_KEY
import org.radarbase.android.RadarConfiguration.Companion.USER_ID_KEY
import org.radarbase.android.RadarService.Companion.ACTION_CHECK_PERMISSIONS
import org.radarbase.android.RadarService.Companion.ACTION_PROVIDERS_UPDATED
import org.radarbase.android.RadarService.Companion.EXTRA_PERMISSIONS
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthServiceConnection
import org.radarbase.android.auth.LoginListener
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.util.*
import org.slf4j.LoggerFactory
import java.io.File

/** Base MainActivity class. It manages the services to collect the data and starts up a view. To
 * create an application, extend this class and override the abstract methods.  */
abstract class MainActivity : AppCompatActivity(), LoginListener {

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
    protected lateinit var authConnection: AuthServiceConnection
    protected lateinit var radarConnection: ManagedServiceConnection<IRadarBinder>

    private lateinit var bluetoothEnforcer: BluetoothEnforcer

    protected lateinit var configuration: RadarConfiguration
    private var connectionsUpdatedReceiver: BroadcastRegistration? = null
    private lateinit var broadcaster: LocalBroadcastManager

    protected open val requestPermissionTimeoutMs: Long
        get() = REQUEST_PERMISSION_TIMEOUT_MS

    val radarService: IRadarBinder?
        get() = radarConnection.binder

    val userId: String?
        get() = configuration.latestConfig.optString(USER_ID_KEY)

    val projectId: String?
        get() = configuration.latestConfig.optString(PROJECT_ID_KEY)

    @CallSuper
    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        permissionHandler.saveInstanceState(savedInstanceState)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configuration = radarConfig
        mHandler = SafeHandler.getInstance("Main background handler", Process.THREAD_PRIORITY_BACKGROUND)
        permissionHandler = PermissionHandler(this, mHandler, requestPermissionTimeoutMs)

        savedInstanceState?.also { permissionHandler.restoreInstanceState(it) }

        radarConnection = ManagedServiceConnection<IRadarBinder>(this@MainActivity, radarApp.radarService).apply {
            bindFlags = Context.BIND_ABOVE_CLIENT or Context.BIND_AUTO_CREATE
            onBoundListeners += IRadarBinder::startScanning
            onBoundListeners += { binder -> view?.onRadarServiceBound(binder) }
            onUnboundListeners += IRadarBinder::stopScanning
        }

        broadcaster = LocalBroadcastManager.getInstance(this)
        bluetoothEnforcer = BluetoothEnforcer(this, radarConnection)
        authConnection = AuthServiceConnection(this, this)
        create()
    }

    @CallSuper
    protected fun create() {
        logger.info("RADAR configuration at create: {}", configuration)
        onConfigChanged()

        configurationBroadcastReceiver = LocalBroadcastManager.getInstance(this)
                .register(RADAR_CONFIGURATION_CHANGED) { _, _ -> onConfigChanged() }

        // Start the UI thread
        uiRefreshRate = configuration.latestConfig.getLong(UI_REFRESH_RATE_KEY, 250L)
    }

    @CallSuper
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

    private var uiUpdater: SafeHandler.HandlerFuture? = null

    override fun onResume() {
        super.onResume()
        uiUpdater = mHandler.repeat(uiRefreshRate) {
            try {
                // Update all rows in the UI with the data from the connections
                view?.update()
            } catch (ex: Exception) {
                logger.error("Failed to update view", ex)
            }
        }
    }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) = Unit

    override fun loginFailed(manager: LoginManager?, ex: Exception?) = Unit

    override fun loggedOut(manager: LoginManager?, authState: AppAuthState) {

    }

    override fun onPause() {
        super.onPause()
        uiUpdater?.let {
            it.cancel()
            uiUpdater = null
        }
    }

    @CallSuper
    public override fun onStart() {
        super.onStart()
        mHandler.start()
        authConnection.bind()
        bluetoothEnforcer.start()

        val radarServiceCls = radarApp.radarService
        try {
            val intent = Intent(this, radarServiceCls)
            ContextCompat.startForegroundService(this, intent)
        } catch (ex: IllegalStateException) {
            logger.error("Failed to start RadarService: activity is in background.", ex)
        }

        radarConnection.bind()

        permissionHandler.invalidateCache()

        LocalBroadcastManager.getInstance(this).apply {
            connectionsUpdatedReceiver = register(ACTION_PROVIDERS_UPDATED) { _, _ ->
                synchronized(this@MainActivity) {
                    view = createView()
                }
            }
        }
        synchronized(this@MainActivity) {
            view = createView()
        }
    }

    override fun onNewIntent(intent: Intent) {
        if (ACTION_CHECK_PERMISSIONS == intent.action) {
            permissionHandler.replaceNeededPermissions(intent.getStringArrayExtra(EXTRA_PERMISSIONS))
        }

        super.onNewIntent(intent)
    }

    @CallSuper
    public override fun onStop() {
        super.onStop()

        mHandler.stop { view = null }

        radarConnection.unbind()
        authConnection.unbind()
        bluetoothEnforcer.stop()

        connectionsUpdatedReceiver?.unregister()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        bluetoothEnforcer.onActivityResult(requestCode, resultCode)
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
        radarConnection.unbind()
        authConnection.applyBinder { invalidate(null, disableRefresh) }
        radarConfig.reset()
        clearConfigSharedPrefs()
        clearAppData(this)
        logger.debug("Disabling Firebase Analytics")
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(false)
        logger.info("Starting SplashActivity: ${packageManager.getLaunchIntentForPackage(packageName)}")
        radarConfig.resetStatus()
        // Start Launcher Activity in overriding method
    }

    private fun clearConfigSharedPrefs() {
        val sharedPreferences = getSharedPreferences("org.radarbase.android.config.LocalConfiguration", Context.MODE_PRIVATE)
        sharedPreferences.all.forEach { (key, value) ->
            logger.info("Finalizing Source service: Shared Prefs: Deleting: $key -> $value")
        }
        sharedPreferences.edit().clear().apply()
    }

    private fun clearAppData(context: Context) {
        clearCache(context)
        clearFilesDir(context)
    }

    private fun clearFilesDir(context: Context) {
        logger.info("Finalizing Source Service: clearing files")
        val filesDir = context.filesDir
        deleteFilesInDirectory(filesDir)
    }

    private fun clearCache(context: Context) {
        logger.info("Finalizing Source Service: clearing cache")
        val cacheDir = context.cacheDir
        deleteFilesInDirectory(cacheDir)
    }

    private fun deleteFilesInDirectory(directory: File) {
        if (directory.isDirectory) {
            val children = directory.listFiles()
            if (children != null) {
                for (child in children) {
                    if (child.absolutePath.toString().contains("firebase")) return
                    logger.info("Finalizing Source Service: deleting: ${child.absolutePath}")
                    deleteFilesInDirectory(child)
                }
            }
        }
        directory.delete()
    }



    companion object {
        private val logger = LoggerFactory.getLogger(MainActivity::class.java)

        private const val REQUEST_PERMISSION_TIMEOUT_MS = 86_400_000L // 1 day
    }
}
