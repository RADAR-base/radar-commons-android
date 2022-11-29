package org.radarbase.android.auth

import android.content.Intent
import android.content.Intent.*
import android.os.Binder
import android.os.IBinder
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import androidx.annotation.Keep
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.radarbase.android.RadarApplication
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.util.DelayedRetry
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.android.util.SafeHandler
import org.radarbase.producer.AuthenticationException
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.util.concurrent.TimeUnit

@Keep
abstract class AuthService : LifecycleService(), LoginListener {
    lateinit var loginManagers: List<LoginManager>
    private val listeners: MutableList<LoginListenerRegistration> = mutableListOf()
    lateinit var config: RadarConfiguration
    var loginListenerId: Long = 0
    private lateinit var networkConnectedListener: NetworkConnectedReceiver
    private var configRegistration: LoginListenerRegistration? = null
    private var refreshDelay = DelayedRetry(RETRY_MIN_DELAY, RETRY_MAX_DELAY)
    private var isConnected: Boolean = false
    private var sourceRegistrationStarted: Boolean = false
    private val successHandlers: MutableList<() -> Unit> = mutableListOf()
    private val _authState: MutableStateFlow<AppAuthState> = MutableStateFlow(AppAuthState())
    private val authState: StateFlow<AppAuthState> = _authState

    open val authSerialization: AuthSerialization by lazy {
        SharedPreferencesAuthSerialization(this)
    }

    @Volatile
    private var isInLoginActivity: Boolean = false

    override fun onCreate() {
        super.onCreate()

        networkConnectedListener = NetworkConnectedReceiver(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.Default) {
                    authState
                        .collectLatest { state ->
                            authSerialization.store(state)
                        }
                }
                launch(Dispatchers.Default) {
                    authState
                        .collectLatest { state ->
                            radarConfig.updateWithAuthState(this@AuthService, state)
                        }
                }
                launch(Dispatchers.Default) {
                    networkConnectedListener.state
                        .map { it != NetworkConnectedReceiver.NetworkState.Disconnected }
                        .distinctUntilChanged()
                        .onEach { isConnected = it }
                        .combine(authState) { isConnected, authState ->
                            Pair(isConnected, authState)
                        }
                        .collectLatest { (isConnected, authState) ->
                            if (isConnected && !authState.isValidFor(5, TimeUnit.MINUTES)) {
                                refresh()
                            }
                        }
                }
            }
        }
        lifecycleScope.launch(Dispatchers.Default) {
            val loadedAuth = authSerialization.load() ?: return@launch
            _authState.value = loadedAuth
        }

        loginManagers = createLoginManagers(appAuth)
    }

    /**
     * Refresh the access token. If the existing token is still valid, do not refresh.
     * The request gets handled in a separate thread and returns a result via loginSucceeded. If
     * the current authentication is not valid and it is not possible to refresh the authentication
     * this opens the login activity.
     */
    suspend fun refresh() {
        logger.info("Refreshing authentication state")
        if (!authState.last().isValidFor(5, TimeUnit.MINUTES)) {
            doRefresh()
        }
    }

    private suspend fun doRefresh() {
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
        if (!isInLoginActivity) {
            try {
                logger.info("Starting login activity")
                val intent = Intent(this, (application as RadarApplication).loginActivity)
                intent.flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_TASK_ON_HOME or FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
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
            when (ex) {
                is ConnectException -> {
                    isConnected = false
                    handler.delay(refreshDelay.nextDelay(), ::refresh)
                }
                is AuthenticationException -> {
                    callListeners {
                        it.loginListener.loginFailed(manager, ex)
                    }
                }
                else -> handler.delay(refreshDelay.nextDelay(), ::refresh)
            }
        }
    }

    private fun callListeners(call: (LoginListenerRegistration) -> Unit) {
        synchronized(listeners) {
            listeners.forEach(call)
        }
    }

    private fun callListeners(sinceUpdate: Long, call: (LoginListenerRegistration) -> Unit) {
        synchronized(listeners) {
            listeners.filter { it.lastUpdate < sinceUpdate }
                    .forEach { listener ->
                        listener.lastUpdate = sinceUpdate
                        call(listener)
                    }
        }
    }

    private val relevantManagers: List<LoginManager>
        get() = appAuth.authenticationSource?.let { authSource ->
                loginManagers.filter { authSource in it.sourceTypes }
            } ?: loginManagers

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        handler.executeReentrant {
            logger.info("Log in succeeded.")
            isConnected = true
            refreshDelay.reset()
            appAuth = authState

            callListeners(sinceUpdate = appAuth.lastUpdate) {
                it.loginListener.loginSucceeded(manager, appAuth)
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
        handler.execute {
            if (appAuth.isValid) {
                loginListener.loginSucceeded(null, appAuth)
            }
        }

        return LoginListenerRegistration(loginListener)
                .also {
                    synchronized(listeners) { listeners += it }
                }
    }

    fun removeLoginListener(registration: LoginListenerRegistration) {
        synchronized(listeners) {
            logger.debug("Removing login listener #{}: {} (starting with {} listeners)", registration.id, registration.loginListener, listeners.size)
            listeners -= listeners.filter { it.id == registration.id }
        }
    }

    /**
     * Create your login managers here. Call [LoginManager.start] for the login method that
     * a user indicates.
     * @param appAuth previous invalid authentication
     * @return non-empty list of login managers to use
     */
    protected abstract fun createLoginManagers(appAuth: AppAuthState): List<LoginManager>

    private fun updateState(update: AppAuthState.Builder.() -> Unit) {
        handler.executeReentrant {
            appAuth = appAuth.alter(update)
        }
    }

    private fun applyState(function: AppAuthState.() -> Unit) {
        handler.executeReentrant {
            appAuth.function()
        }
    }

    fun invalidate(token: String?, disableRefresh: Boolean) {
        handler.executeReentrant {
            logger.info("Invalidating authentication state")
            if (token?.let { it == appAuth.token } != false) {
                appAuth = appAuth.alter { invalidate() }

                if (relevantManagers.any { manager ->
                    manager.invalidate(appAuth, disableRefresh)
                            ?.also { appAuth = it } != null
                }) {
                    authSerialization.store(appAuth)
                    refresh()
                }
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

        var isInLoginActivity: Boolean
            get() = this@AuthService.isInLoginActivity
            set(value) {
                this@AuthService.isInLoginActivity = value
            }
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
                sourceMetadata -= sources
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
