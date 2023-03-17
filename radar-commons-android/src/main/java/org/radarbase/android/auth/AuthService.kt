package org.radarbase.android.auth

import android.app.Service
import android.content.Intent
import android.content.Intent.*
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import androidx.annotation.Keep
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.RadarApplication
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.LoginActivity.Companion.ACTION_LOGIN_SUCCESS
import org.radarbase.android.util.DelayedRetry
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.android.util.SafeHandler
import org.radarbase.android.util.send
import org.radarbase.producer.AuthenticationException
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.util.concurrent.TimeUnit

@Keep
abstract class AuthService : Service(), LoginListener {
    private lateinit var mainHandler: Handler
    private lateinit var appAuth: AppAuthState
    lateinit var loginManagers: List<LoginManager>
    private val listeners: MutableList<LoginListenerRegistration> = mutableListOf()
    lateinit var config: RadarConfiguration
    val handler = SafeHandler.getInstance("AuthService", THREAD_PRIORITY_BACKGROUND)
    var loginListenerId: Long = 0
    private lateinit var networkConnectedListener: NetworkConnectedReceiver
    private var configRegistration: LoginListenerRegistration? = null
    private var refreshDelay = DelayedRetry(RETRY_MIN_DELAY, RETRY_MAX_DELAY)
    private var isConnected: Boolean = false
    private var sourceRegistrationStarted: Boolean = false
    private val successHandlers: MutableList<() -> Unit> = mutableListOf()

    open val authSerialization: AuthSerialization by lazy {
        SharedPreferencesAuthSerialization(this)
    }

    private lateinit var broadcaster: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        broadcaster = LocalBroadcastManager.getInstance(this)
        mainHandler = Handler(mainLooper)
        appAuth = authSerialization.load() ?: AppAuthState()
        config = radarConfig
        config.updateWithAuthState(this, appAuth)
        handler.start()
        loginManagers = createLoginManagers(appAuth)
        networkConnectedListener = NetworkConnectedReceiver(this, object : NetworkConnectedReceiver.NetworkConnectedListener {
            override fun onNetworkConnectionChanged(state: NetworkConnectedReceiver.NetworkState) {
                handler.execute {
                    isConnected = state.isConnected
                    if (isConnected && !appAuth.isValidFor(5, TimeUnit.MINUTES)) {
                        refresh()
                    }
                }
            }
        })
        networkConnectedListener.register()

        configRegistration = addLoginListener(object : LoginListener {
            override fun loginFailed(manager: LoginManager?, ex: java.lang.Exception?) {
                // no action required
            }

            override fun logoutSucceeded(manager: LoginManager?, authState: AppAuthState) {
                authSerialization.store(appAuth)
                config.updateWithAuthState(this@AuthService, appAuth)
            }

            override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
                refreshDelay.reset()
                authSerialization.store(appAuth)
                config.updateWithAuthState(this@AuthService, appAuth)
            }
        })
    }

    /**
     * Refresh the access token. If the existing token is still valid, do not refresh.
     * The request gets handled in a separate thread and returns a result via loginSucceeded. If
     * the current authentication is not valid and it is not possible to refresh the authentication
     * this opens the login activity.
     */
    fun refresh() {
        handler.execute {
            logger.info("Refreshing authentication state")
            if (appAuth.isValidFor(5, TimeUnit.MINUTES)) {
                callListeners(sinceUpdate = appAuth.lastUpdate) {
                    it.loginListener.loginSucceeded(null, appAuth)
                }
            } else {
                doRefresh()
            }
        }
    }

    fun triggerFlush() {
        handler.executeReentrant {
            authSerialization.store(appAuth)
        }
    }

    private fun doRefresh() {
        if (relevantManagers.none { it.refresh(appAuth) }) {
            startLogin()
        }
    }

    /**
     * Refresh the access token. If the existing token is still valid, do not refresh.
     * The request gets handled in a separate thread and returns a result via loginSucceeded. If
     * there is no network connectivity, just check if the authentication could be refreshed if the
     * client was online.
     */
    fun refreshIfOnline() {
        handler.execute {
            when {
                isConnected -> refresh()
                appAuth.isValid || relevantManagers.any { it.isRefreshable(appAuth) } -> {
                    logger.info("Retrieving active authentication state without refreshing")
                    callListeners(sinceUpdate = appAuth.lastUpdate) {
                        it.loginListener.loginSucceeded(null, appAuth)
                    }
                }
                else -> {
                    logger.error("Failed to retrieve authentication state without refreshing",
                        IllegalStateException(
                            "Cannot refresh authentication state $appAuth: not online and no" +
                            "applicable authentication manager."
                        )
                    )
                    startLogin()
                }
            }
        }
    }

    protected open fun startLogin() {
        mainHandler.post {
            if (radarApp.loginActivity in radarApp.activeActivities) return@post

            try {
                logger.info("Starting login activity")
                val intent = Intent(this, (application as RadarApplication).loginActivity)
                intent.flags =
                    FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_TASK_ON_HOME or FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
            } catch (ex: IllegalStateException) {
                logger.warn("Failed to start login activity. Notifying about login.")
                showLoginNotification()
            }
        }
    }

    protected abstract fun showLoginNotification()

    override fun loginFailed(manager: LoginManager?, ex: java.lang.Exception?) {
        handler.executeReentrant {
            logger.info("Login failed: {}", ex?.toString())

            if (ex is ConnectException) {
                isConnected = false
            }
            callListeners {
                it.loginListener.loginFailed(manager, ex)
            }
            if (ex !is AuthenticationException) {
                handler.delay(refreshDelay.nextDelay(), ::refresh)
            }
        }
    }

    private fun callListeners(call: (LoginListenerRegistration) -> Unit) {
        handler.execute {
            listeners.forEach(call)
        }
    }

    private fun callListeners(sinceUpdate: Long, call: (LoginListenerRegistration) -> Unit) {
        handler.execute {
            listeners.filter { it.lastUpdate < sinceUpdate }
                    .forEach { listener ->
                        listener.lastUpdate = sinceUpdate
                        call(listener)
                    }
        }
    }

    private val relevantManagers: List<LoginManager>
        get() {
            val authSource = appAuth.authenticationSource
            return if (authSource != null) {
                loginManagers.filter { manager -> authSource in manager.sourceTypes }
            } else {
                loginManagers
            }
        }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        handler.executeReentrant {
            logger.info("Log in succeeded.")
            isConnected = true
            refreshDelay.reset()
            appAuth = authState

            broadcaster.send(ACTION_LOGIN_SUCCESS)

            callListeners(sinceUpdate = appAuth.lastUpdate) {
                it.loginListener.loginSucceeded(manager, appAuth)
            }
        }
    }

    override fun logoutSucceeded(manager: LoginManager?, authState: AppAuthState) {
        handler.executeReentrant {
            logger.info("Log out succeeded.")
            appAuth = authState
            callListeners {
                it.loginListener.logoutSucceeded(manager, appAuth)
            }
        }
    }

    override fun onDestroy() {
        networkConnectedListener.unregister()
        configRegistration?.let { removeLoginListener(it) }
        handler.stop {
            loginManagers.forEach { it.onDestroy() }
            authSerialization.store(appAuth)
        }
    }

    fun update(manager: LoginManager) {
        handler.execute {
            logger.info("Refreshing manager {}", manager)
            manager.refresh(appAuth)
        }
    }

    fun addLoginListener(loginListener: LoginListener): LoginListenerRegistration {
        val registration = handler.compute {
            LoginListenerRegistration(loginListener)
                .also { listeners += it }
        }
        handler.execute {
            if (appAuth.isValid) {
                loginListener.loginSucceeded(null, appAuth)
            }
        }
        return registration
    }

    fun removeLoginListener(registration: LoginListenerRegistration) {
        handler.execute {
            logger.debug("Removing login listener #{}: {} (starting with {} listeners)", registration.id, registration.loginListener, listeners.size)
            listeners.removeAll { it.id == registration.id }
        }
    }

    /**
     * Create your login managers here. Call [LoginManager.start] for the login method that
     * a user indicates.
     * @param appAuth previous invalid authentication
     * @return non-empty list of login managers to use
     */
    protected abstract fun createLoginManagers(appAuth: AppAuthState): List<LoginManager>

    private fun updateState(update: AppAuthState.Builder.() -> Unit) = handler.executeReentrant {
        appAuth = appAuth.alter(update)
    }

    private fun applyState(function: AppAuthState.() -> Unit) = handler.executeReentrant {
        appAuth.function()
    }

    fun invalidate(token: String?, disableRefresh: Boolean) {
        handler.executeReentrant {
            logger.info("Invalidating authentication state")
            if (token == null || token == appAuth.token) {
                val (updatedManager, updatedAuth) = relevantManagers.asSequence()
                    .map { it to it.invalidate(appAuth, disableRefresh) }
                    .firstOrNull { (_, updatedAuth) -> updatedAuth != null }
                    ?: Pair(null, appAuth)

                val invalidatedAuth = if (disableRefresh) {
                    updatedAuth!!.reset()
                } else {
                    updatedAuth!!.alter { invalidate() }
                }

                logoutSucceeded(updatedManager, invalidatedAuth)
                refresh()
            }
        }
    }

    private fun registerSource(source: SourceMetadata, success: (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit) {
        handler.execute {
            logger.info("Registering source with {}: {}", source.type, source.sourceId)

            relevantManagers.any { manager ->
                manager.registerSource(appAuth, source, { newAppAuth, newSource ->
                    if (newAppAuth != appAuth) {
                        appAuth = newAppAuth
                        authSerialization.store(appAuth)
                    }
                    successHandlers += { success(newAppAuth, newSource) }
                    if (!sourceRegistrationStarted) {
                        handler.delay(1_000L) {
                            doRefresh()
                            successHandlers.forEach { it() }
                            successHandlers.clear()
                            sourceRegistrationStarted = false
                        }
                        sourceRegistrationStarted = true
                    }
                }, { ex ->
                    appAuth.alter {
                        sourceMetadata.removeAll(source::matches)
                    }
                    authSerialization.store(appAuth)
                    failure(ex)
                })
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return AuthServiceBinder()
    }

    inner class AuthServiceBinder: Binder() {
        fun addLoginListener(loginListener: LoginListener): LoginListenerRegistration = this@AuthService.addLoginListener(loginListener)

        fun removeLoginListener(registration: LoginListenerRegistration) = this@AuthService.removeLoginListener(registration)

        val managers: List<LoginManager>
            get() = loginManagers

        fun update(manager: LoginManager) = this@AuthService.update(manager)

        fun triggerFlush() = this@AuthService.triggerFlush()

        fun refresh() = this@AuthService.refresh()

        fun refreshIfOnline() = this@AuthService.refreshIfOnline()

        fun applyState(apply: AppAuthState.() -> Unit) = this@AuthService.applyState(apply)

        @Suppress("unused")
        fun updateState(update: AppAuthState.Builder.() -> Unit) = this@AuthService.updateState(update)

        fun registerSource(source: SourceMetadata, success: (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit) =
                this@AuthService.registerSource(source, success, failure)

        fun updateSource(source: SourceMetadata, success: (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit) =
                this@AuthService.updateSource(source, success, failure)

        fun unregisterSources(sources: Iterable<SourceMetadata>) =
                this@AuthService.unregisterSources(sources)

        fun invalidate(token: String?, disableRefresh: Boolean) = this@AuthService.invalidate(token, disableRefresh)
    }

    private fun updateSource(source: SourceMetadata, success: (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit) {
        handler.execute {
            relevantManagers.any { manager ->
                manager.updateSource(appAuth, source, success, failure)
            }
        }
    }

    private fun unregisterSources(sources: Iterable<SourceMetadata>) {
        handler.execute {
            updateState {
                sourceMetadata -= sources.toHashSet()
            }
            doRefresh()
        }
    }

    inner class LoginListenerRegistration(val loginListener: LoginListener) {
        val id = ++loginListenerId
        var lastUpdate = 0L
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AuthService::class.java)
        const val RETRY_MIN_DELAY = 5L
        const val RETRY_MAX_DELAY = 86400L
        const val PRIVACY_POLICY_URL_PROPERTY = "org.radarcns.android.auth.portal.ManagementPortalClient.privacyPolicyUrl"
        const val BASE_URL_PROPERTY = "org.radarcns.android.auth.portal.ManagementPortalClient.baseUrl"
    }
}
