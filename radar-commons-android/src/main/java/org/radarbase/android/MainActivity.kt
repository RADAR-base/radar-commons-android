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
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.PROJECT_ID_KEY
import org.radarbase.android.RadarConfiguration.Companion.UI_REFRESH_RATE_KEY
import org.radarbase.android.RadarConfiguration.Companion.USER_ID_KEY
import org.radarbase.android.RadarConfiguration.RemoteConfigStatus.INITIAL
import org.radarbase.android.RadarService.Companion.ACTION_CHECK_PERMISSIONS
import org.radarbase.android.RadarService.Companion.EXTRA_PERMISSIONS
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.auth.AuthServiceStateReactor
import org.radarbase.android.auth.LoginListener
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.BindState
import org.radarbase.android.util.BluetoothEnforcer
import org.radarbase.android.util.CoroutineTaskExecutor
import org.radarbase.android.util.ManagedServiceConnection
import org.radarbase.android.util.ManagedServiceConnection.Companion.serviceConnection
import org.radarbase.android.util.PermissionBroadcast
import org.radarbase.android.util.PermissionHandler
import org.radarbase.kotlin.coroutines.launchJoin
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

typealias RadarServiceStateReactor = suspend (IRadarBinder) -> Unit

/** Base MainActivity class. It manages the services to collect the data and starts up a view. To
 * create an application, extend this class and override the abstract methods.  */
@Suppress("MemberVisibilityCanBePrivate")
abstract class MainActivity : AppCompatActivity(), LoginListener {

    /** Time between refreshes.  */
    private var uiRefreshRate: Long = 0

    /** Hander in the background. It is set to null whenever the activity is not running.  */
    private lateinit var mainExecutor: CoroutineTaskExecutor

    /** The UI to show the service data.  */
    @get:Synchronized
    var view: MainActivityView? = null
        private set

    private lateinit var permissionHandler: PermissionHandler
    protected lateinit var authConnection: ManagedServiceConnection<AuthService.AuthServiceBinder>
    protected lateinit var radarConnection: ManagedServiceConnection<IRadarBinder>

    private lateinit var bluetoothEnforcer: BluetoothEnforcer

    protected lateinit var configuration: RadarConfiguration

    protected var radarServiceBinder: IRadarBinder? = null
        private set
    protected var authServiceBinder: AuthService.AuthServiceBinder? = null
        private set

    val radarBinder: IRadarBinder?
        get() = radarServiceBinder

    private var mainAuthRegistry: AuthService.LoginListenerRegistry? = null

    protected open val requestPermissionTimeout: Duration
        get() = REQUEST_PERMISSION_TIMEOUT

    val radarService: StateFlow<BindState<IRadarBinder>>
        get() = radarConnection.state

    val userId: String?
        get() = configuration.latestConfig.optString(USER_ID_KEY)

    val projectId: String?
        get() = configuration.latestConfig.optString(PROJECT_ID_KEY)

    private val mutexCreateView: Mutex = Mutex()
    private var connectionBound: Boolean = false

    private var radarConnectionJob: Job? = null
    private var authConnectionJob: Job? = null

    protected var mainActivityView: MainActivityView? = null

    private val radarServiceBoundActions: MutableList<RadarServiceStateReactor> = mutableListOf(
        IRadarBinder::startScanning,
        {binder -> view?.onRadarServiceBound(binder)},
        {
            logger.debug("Radar Service bound to {}", this::class.simpleName)
        }
    )
    private val radarServiceUnboundActions: MutableList<RadarServiceStateReactor> = mutableListOf(
        IRadarBinder::stopScanning,
        {
            logger.debug("Radar Service unbound from {}", this::class.simpleName)
        }
    )

    private val authServiceBoundActions: MutableList<AuthServiceStateReactor> =
        mutableListOf({ binder ->
            mainAuthRegistry = binder.addLoginListener(this)
            binder.refreshIfOnline()
        }, { binder ->
            binder.applyState {
                if (userId == null) {
                    this@MainActivity.logoutSucceeded(null, this)
                }
            }
        }, {
            logger.debug("Auth Service bound to {}", this::class.simpleName)
        }
        )

    private val authServiceUnboundActions: MutableList<AuthServiceStateReactor> = mutableListOf(
        { binder ->
            mainAuthRegistry?.let {
                binder.removeLoginListener(it)
            }
        }, {
            logger.debug("Auth Service unbound from {}", this::class.simpleName)
        }
    )

    @CallSuper
    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        permissionHandler.saveInstanceState(savedInstanceState)

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configuration = radarConfig
        mainExecutor = CoroutineTaskExecutor(this::class.simpleName!!, Dispatchers.Default)
        permissionHandler = PermissionHandler(
            this,
            mainExecutor,
            _permissionsBroadcastReceiver,
            requestPermissionTimeout.inWholeMilliseconds,
            activityResultRegistry
        )

        savedInstanceState?.also { permissionHandler.restoreInstanceState(it) }

        radarConnection = ManagedServiceConnection(
            this,
            radarApp.radarService,
            IRadarBinder::class.java
        ).apply {
            bindFlags = Context.BIND_AUTO_CREATE or Context.BIND_ABOVE_CLIENT
        }

        bluetoothEnforcer = BluetoothEnforcer(this, radarConnection, radarServiceBoundActions, activityResultRegistry)
        authConnection = serviceConnection(radarApp.authService)
        create()
    }

    @CallSuper
    protected fun create() {
        logger.info("RADAR configuration at create: {}", configuration)
        onConfigChanged()

        connectionBound = false

        // Start the UI thread
        uiRefreshRate = configuration.latestConfig.getLong(UI_REFRESH_RATE_KEY, 250L)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                (application as RadarApplication).radarServiceImpl.actionProvidersUpdated.collect {
                    logger.trace("NewBroadcastTrace: ActionProviderUpdated: {}", it)
                    mutexCreateView.withLock {
                        logger.debug("Source providers updated, creating a new view")
                        view = createView()
                    }
                }
            }
        }

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

    private var uiUpdater: CoroutineTaskExecutor.CoroutineFutureHandle? = null

    override fun onResume() {
        super.onResume()
        uiUpdater = mainExecutor.repeat(uiRefreshRate) {
            try {
                // Update all rows in the UI with the data from the connections
                view?.update()
            } catch (ex: Exception) {
                logger.error("Failed to update view", ex)
            }
        }
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
        mainExecutor.start()

        mainActivityView?.stopLogoutProgress()
        bluetoothEnforcer.start()

        val radarServiceCls = radarApp.radarService
        try {
            val intent = Intent(this, radarServiceCls)
            ContextCompat.startForegroundService(this, intent)
        } catch (ex: IllegalStateException) {
            logger.error("Failed to start RadarService: activity is in background.", ex)
        }

        setUpConnectionJobs()

        with(lifecycleScope) {
            launch {
                try {
                    connectionBound = true
                    authConnection.bind()
                    radarConnection.bind()
                } catch (ex: Exception) {
                    connectionBound = false
                    throw  ex
                }
            }
        }
        permissionHandler.invalidateCache()
        radarConnectionJob?.start()
        authConnectionJob?.start()

        lifecycleScope.launch {
            mutexCreateView.withLock {
                logger.trace("Creating a new view")
                view = createView()
            }
        }
    }

    private fun setUpConnectionJobs() {
        with(lifecycleScope) {
            radarConnectionJob = launch(start = CoroutineStart.LAZY) {
                radarConnection.state
                    .collect { bindState: BindState<IRadarBinder> ->
                        when (bindState) {
                            is ManagedServiceConnection.BoundService -> {
                                radarServiceBinder = bindState.binder
                                    .also { binder ->
                                        radarServiceBoundActions.launchJoin { action ->
                                            action(binder)
                                        }
                                    }
                            }

                            is ManagedServiceConnection.Unbound -> {
                                radarServiceBinder?.also { binder ->
                                    radarServiceUnboundActions.launchJoin { action ->
                                        action(binder)
                                    }
                                    radarServiceBinder = null
                                }
                            }
                        }
                    }
            }

            authConnectionJob = launch(start = CoroutineStart.LAZY) {
                authConnection.state
                    .collect { bindState ->
                        when (bindState) {
                            is ManagedServiceConnection.BoundService -> {
                                authServiceBinder = bindState.binder.also { binder ->
                                    authServiceBoundActions.launchJoin {
                                        it(binder)
                                    }
                                }
                            }

                            is ManagedServiceConnection.Unbound -> {
                                authServiceBinder?.also { binder ->
                                    authServiceUnboundActions.launchJoin {
                                        it(binder)
                                    }
                                    authServiceBinder = null
                                }
                            }
                        }
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

    @CallSuper
    public override fun onStop() {
        mainExecutor.stop { view = null }

        if (connectionBound) {
            connectionBound = false
            radarConnection.unbind()
            authConnection.unbind()
        }
        bluetoothEnforcer.stop()

        radarConnectionJob?.cancel()
        authConnectionJob?.cancel()
        radarConnectionJob = null
        authConnectionJob = null
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHandler.permissionsGranted(requestCode, permissions, grantResults)
    }

    /**
     * Log out of the current application.
     * @param disableRefresh if `true`, also remove any refresh tokens; if `false`, just remove
     * the access token but allow the same user to automatically log in again if it is
     * still valid.
     */
    protected suspend fun logout(disableRefresh: Boolean) {
        mainActivityView?.showLogoutProgress()
        lifecycleScope.launch {
            authConnection.applyBinder { invalidate(null, disableRefresh) }
        }
        logger.debug("Disabling Firebase Analytics")
        FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(false)
    }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) = Unit

    override suspend fun logoutSucceeded(manager: LoginManager?, authState: AppAuthState) {
        if (!connectionBound) {
            connectionBound = false
            radarConnection.unbind()
            authConnection.unbind()
            delay(400)
        }
        clearAppData(this)
        delay(300)
        logger.info("Starting SplashActivity")
        val applicationPackage = packageName
        withContext(Dispatchers.Main) {
            mainActivityView?.stopLogoutProgress()
        }
        val intent = packageManager.getLaunchIntentForPackage(applicationPackage) ?: return
        logger.debug("Starting splash activity with intent {}", intent)
        startActivity(intent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun clearAppData(context: Context) {
        clearCache(context)
        clearFilesDir(context)
    }

    private fun clearFilesDir(context: Context) {
        val filesDir = context.filesDir
        deleteFilesInDirectory(filesDir)
    }

    private fun clearCache(context: Context) {
        val cacheDir = context.cacheDir
        deleteFilesInDirectory(cacheDir)
    }

    private fun deleteFilesInDirectory(directory: File) {
        if (directory.isDirectory) {
            val children = directory.listFiles()
            if (children != null) {
                for (child in children) {
                    if (child.absolutePath.toString().contains("firebase")) return
                    deleteFilesInDirectory(child)
                }
            }
        }
        directory.delete()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MainActivity::class.java)

        private val REQUEST_PERMISSION_TIMEOUT = 86_400_000.milliseconds // 1 day

        private var _permissionsBroadcastReceiver: MutableSharedFlow<PermissionBroadcast> = MutableSharedFlow()

        val LifecycleService.permissionsBroadcastReceiver: SharedFlow<PermissionBroadcast>
            get() = _permissionsBroadcastReceiver
    }
}
