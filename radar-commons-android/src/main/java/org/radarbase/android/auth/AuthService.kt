package org.radarbase.android.auth

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME
import android.os.Binder
import android.os.IBinder
import androidx.annotation.Keep
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.radarbase.android.RadarApplication
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.util.CoroutineTaskExecutor
import org.radarbase.android.util.DelayedRetry
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.kotlin.coroutines.launchJoin
import org.radarbase.producer.AuthenticationException
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Keep
abstract class AuthService : LifecycleService(), LoginListener {

    private val latestAppAuth: AppAuthState
        get() = authState.value

    lateinit var loginManagers: List<LoginManager>
    lateinit var config: RadarConfiguration
    private lateinit var networkConnectedListener: NetworkConnectedReceiver
    private var refreshDelay = DelayedRetry(RETRY_MIN_DELAY, RETRY_MAX_DELAY)
    private var isConnected: Boolean = false
    private val _authState: MutableStateFlow<AppAuthState> = MutableStateFlow(AppAuthState())
    val authState: StateFlow<AppAuthState> = _authState.asStateFlow()

    private val executor: CoroutineTaskExecutor = CoroutineTaskExecutor(AuthService::class.simpleName!!, Dispatchers.Default)

    private val serviceMutex: Mutex = Mutex(false)
    private val registryTweakMutex: Mutex = Mutex(false)
    private val authUpdateMutex: Mutex = Mutex(false)

    private var needLoadedState: AtomicBoolean = AtomicBoolean(true)

    private val _authStateFailures: MutableSharedFlow<AuthLoginListener.AuthStateFailure> = MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _authStateSuccess: MutableSharedFlow<AuthLoginListener.AuthStateSuccess> = MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _authStateLogout: MutableSharedFlow<AuthLoginListener.AuthStateLogout> = MutableSharedFlow(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var sourceRegistrationStarted: Boolean = false
    private val authStateFailures: Flow<AuthLoginListener.AuthStateFailure> = _authStateFailures
    private val authStateSuccess: Flow<AuthLoginListener.AuthStateSuccess> = _authStateSuccess
    private val authStateLogout: Flow<AuthLoginListener.AuthStateLogout> = _authStateLogout

    open val authSerialization: AuthSerialization by lazy {
        SharedPreferencesAuthSerialization(this)
    }

    private val successHandlers: MutableList<suspend () -> Unit> = mutableListOf()

    @Volatile
    private var isInLoginActivity: Boolean = false
    private var isNetworkStatusReceived: Boolean = false

    private var loginListenerId: Long = 0L
    private val listeners: MutableList<LoginListenerRegistry> = mutableListOf()


    override fun onCreate() {
        super.onCreate()

        networkConnectedListener = NetworkConnectedReceiver(this)
        CoroutineTaskExecutor.startRetryScope()
        executor.start()
        lifecycleScope.launch(Dispatchers.Default) {
            needLoadedState.set(true)
            val loadedAuth = authSerialization.load()
            loadedAuth ?: run {
                needLoadedState.set(false)
                return@launch
            }
            _authState.value = loadedAuth
            needLoadedState.set(false)
        }

        lifecycleScope.launch {
            loginManagers = createLoginManagers(_authState)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch(Dispatchers.Default) {
                    authState.collectLatest { state ->
                        if (needLoadedState.get()) {
                            for (i in 1..4) {
                                if (needLoadedState.get()) {
                                    logger.trace("Storing auth state -- waiting for state to load")
                                    delay(50)
                                }
                            }
                        }
                        authSerialization.store(state)
                    }
                }
                launch(Dispatchers.Default) {
                    authState
                        .collectLatest { state ->
                        logger.trace("Collected AppAuth: {}", state)
                        radarConfig.updateWithAuthState(this@AuthService, state)
                    }
                }
                launch(Dispatchers.Default) {
                    networkConnectedListener.monitor()
                    networkConnectedListener.state!!
                        .map { it != NetworkConnectedReceiver.NetworkState.Disconnected }
                        .distinctUntilChanged()
                        .onEach { isConnected = it }
                        .collectLatest { isConnected ->
                            isNetworkStatusReceived = true
                            if (isConnected && !latestAppAuth.isValidFor(5, TimeUnit.MINUTES)) {
                                refresh()
                            } else {
                                refreshDelay.reset()
                            }
                        }
                }
                launch(Dispatchers.Default) {
                    authStateFailures
                        .collectLatest { (manager, ex) ->
                            logger.info("Login failed: {}", ex.toString())
                            when (ex) {
                                is AuthenticationException -> {
                                    callListeners {
                                        it.listener.loginFailed(manager, ex)
                                    }
                                }
                                is ConnectException -> {
                                    isConnected = false
                                    executor.delay(refreshDelay.nextDelay(), ::refresh)
                                }
                                else -> {
                                    executor.delay(refreshDelay.nextDelay(), ::refresh)
                                }
                            }
                        }
                }
                launch(Dispatchers.Default) {
                    authStateSuccess
                        .collectLatest { successState: AuthLoginListener.AuthStateSuccess ->
                            logger.info("Log-in succeeded")
                            isConnected = true
                            refreshDelay.reset()
                            callListeners(sinceUpdate = latestAppAuth.lastUpdate) {
                                logger.trace("Login succeeded (AuthService:: CallListener {} with state): AppAuthState: {}",it, authState.value)
                                it.listener.loginSucceeded(successState.manager, latestAppAuth)
                            }
                        }
                }
            }
        }
    }

    /**
     * Refresh the access token. If the existing token is still valid, do not refresh.
     * The request gets handled in a separate thread and returns a result via loginSucceeded. If
     * the current authentication is not valid and it is not possible to refresh the authentication
     * this opens the login activity.
     */
    suspend fun refresh() {
        executor.execute {
            logger.info("Refreshing authentication state")
            if (needLoadedState.get()) {
                for (i in 1..4) {
                    if (needLoadedState.get()) {
                        logger.trace("Refreshing auth state -- waiting for state to load")
                        delay(50)
                    }
                }
            }
            if (!authState.value.isValidFor(5, TimeUnit.MINUTES)) {
                doRefresh()
            } else {
                callListeners(sinceUpdate = latestAppAuth.lastUpdate) {
                    it.listener.loginSucceeded(null, latestAppAuth)
                }
            }
        }
    }

    private suspend fun doRefresh() {
        var canRefresh = false
        updateState { state ->
            canRefresh = state.relevantManagers.any { it.refresh(this) }
        }
        if (!canRefresh) {
            startLogin()
        }
    }

    /**
     * Refresh the access token. If the existing token is still valid, do not refresh.
     * The request gets handled in a separate thread and returns a result via loginSucceeded. If
     * there is no network connectivity, just check if the authentication could be refreshed if the
     * client was online.
     */
    suspend fun refreshIfOnline() {
        executor.execute {
            logger.debug("Refreshing if online")
            for (i in 1..3) {
                if (!isNetworkStatusReceived) {
                    delay(100)
                }
            }
            if (isConnected) {
                logger.trace("Network is available, now refreshing")
                refresh()
            } else {
                logger.trace("Network is not available, proceeding with other way")
                val appAuth = latestAppAuth
                if (appAuth.isValid || appAuth.relevantManagers.any {
                        it.isRefreshable(
                            appAuth
                        )
                    }) {
                    logger.info("Retrieving active authentication state without refreshing")
                    callListeners(sinceUpdate = appAuth.lastUpdate) {
                        it.listener.loginSucceeded(null, appAuth)
                    }
                } else {
                    logger.error(
                        "Failed to retrieve authentication state without refreshing",
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

    private suspend fun callListeners(call: suspend (LoginListenerRegistry) -> Unit) {
        serviceMutex.withLock {
            listeners.launchJoin {
                call(it)
            }
        }
    }

    private suspend fun callListeners(sinceUpdate: Long, call: (LoginListenerRegistry) -> Unit) {
        serviceMutex.withLock {
            val obsoleteListeners = listeners.filter { it.lastUpdated < sinceUpdate }
            obsoleteListeners.launchJoin {
                logger.debug("Sending login succeeded to {}", it.listener)
                it.lastUpdated = sinceUpdate
                call(it)
            }
        }
    }

    suspend fun addLoginListener(loginListener: LoginListener): LoginListenerRegistry {
        executor.execute {
            val auth = latestAppAuth
            if (auth.isValid) {
                loginListener.loginSucceeded(null, auth)
            }
        }

        return LoginListenerRegistry(loginListener)
            .also {
                registryTweakMutex.withLock { listeners += it }
                logger.debug("Added login listener {} to registry ", it.listener)
            }
    }

    suspend fun removeLoginListener(registry: LoginListenerRegistry) {
        registryTweakMutex.withLock {
            logger.debug(
                "Removing login listener #{}: {} (starting with {} listeners)",
                registry.id,
                registry.listener,
                listeners.size
            )
            listeners -= listeners.filter { it.id == registry.id }.toSet()
        }
    }


    protected abstract fun showLoginNotification()

    override fun loginFailed(manager: LoginManager?, ex: java.lang.Exception?) {
        lifecycleScope.launch {
            _authStateFailures.emit(AuthLoginListener.AuthStateFailure(manager, ex))
        }
    }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        lifecycleScope.launch {
            _authStateSuccess.emit(AuthLoginListener.AuthStateSuccess(manager))
        }
        _authState.value = authState
    }

    override suspend fun logoutSucceeded(manager: LoginManager?, authState: AppAuthState) {
        lifecycleScope.launch {
            _authStateLogout.emit(AuthLoginListener.AuthStateLogout(manager, authState))
        }
        _authState.value = authState
    }

    private val AppAuthState.relevantManagers: List<LoginManager>
        get() = loginManagers.filter { it.appliesTo(this) }

    private val AppAuthState.manager: LoginManager
        get() = relevantManagers.first()

    override fun onDestroy() {
        super.onDestroy()
        loginManagers.forEach { it.onDestroy() }
        executor.stop()
        CoroutineTaskExecutor.shutdownRetryScope()
    }

    suspend fun update(manager: LoginManager) {
        logger.info("Refreshing manager {}", manager)
        updateState {
            manager.refresh(this)
        }
    }

    /**
     * Create your login managers here. Call [LoginManager.start] for the login method that
     * a user indicates.
     * @param appAuth previous invalid authentication
     * @return non-empty list of login managers to use
     */
    protected abstract suspend fun createLoginManagers(appAuth: StateFlow<AppAuthState>): List<LoginManager>

    private suspend fun <T> updateState(
        update: suspend AppAuthState.Builder.(AppAuthState) -> T
    ): T {
        logger.info("Trying to tweak auth state")
        authUpdateMutex.withLock {
            var result: T? = null
            do {
                logger.debug("Updating auth state")
                val currentValue = authState.value
                val newValue = currentValue.alter {
                    result = this.update(currentValue)
                }
            } while (!_authState.compareAndSet(currentValue, newValue))
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }

    private suspend fun applyState(function: suspend AppAuthState.() -> Unit) {
        latestAppAuth.apply {
            function()
        }
    }

    suspend fun invalidate(token: String?, disableRefresh: Boolean) {
        executor.execute {
            updateState { auth ->
                logger.info("Invalidating authentication state")
                if (token != null && token != auth.token) return@updateState

                auth.relevantManagers.forEach {
                    it.invalidate(this@updateState, disableRefresh)
                }
                if (disableRefresh) {
                    clear()
                } else {
                    invalidate()
                }
            }
        }
    }

    suspend fun registerSource(
        source: SourceMetadata,
        success: suspend (AppAuthState, SourceMetadata) -> Unit,
        failure: suspend (Exception?) -> Unit
    ) {
        logger.info(
            "Registering source with SourceType: {}, and SourceId: {}",
            source.type,
            source.sourceId
        )

        var appAuthAfterRegistration: AppAuthState? = null
        updateState { auth ->
            auth.relevantManagers.any { manager ->
                manager.registerSource(
                    this,
                    source,
                    { newAppAuth, newSource ->
//                        if (newAppAuth != auth) {
//                            _authState.value = newAppAuth
//                        }
                        appAuthAfterRegistration = newAppAuth
                        successHandlers += { success(newAppAuth, newSource) }
                        if (!sourceRegistrationStarted) {
                            executor.delay(1_000L) {
                                doRefresh()
                                successHandlers.forEach { it() }
                                successHandlers.clear()
                                sourceRegistrationStarted = false
                            }
                            sourceRegistrationStarted = true
                        }
                    },
                    { ex ->
                        this.sourceMetadata.removeAll(source::matches)
                        failure(ex)
                    })
            }
        }
        if (appAuthAfterRegistration == null) {
            logger.trace("AppAuthRegistrationTrace: Auth state is null")
        } else {
            if (appAuthAfterRegistration == latestAppAuth) {
                logger.trace("AppAuthRegistrationTrace: Already updated")
            } else {
                logger.trace("AppAuthRegistrationTrace: Not updated now updating...")
                appAuthAfterRegistration?.also {
                    _authState.value = it
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return AuthServiceBinder()
    }

    inner class AuthServiceBinder: Binder() {

        suspend fun addLoginListener(loginListener: LoginListener) =
            this@AuthService.addLoginListener(loginListener)

        suspend fun removeLoginListener(registry: LoginListenerRegistry) =
            this@AuthService.removeLoginListener(registry)

        val managers: List<LoginManager>
            get() = loginManagers

        val authStateFailures: Flow<AuthLoginListener.AuthStateFailure>
            get() = this@AuthService.authStateFailures

        val authStateSuccess: Flow<AuthLoginListener.AuthStateSuccess>
            get() = this@AuthService.authStateSuccess

        val authStateLogouts: Flow<AuthLoginListener.AuthStateLogout>
            get() = this@AuthService.authStateLogout

        suspend fun update(manager: LoginManager) = this@AuthService.update(manager)

        suspend fun refresh() = this@AuthService.refresh()

        suspend fun refreshIfOnline() = this@AuthService.refreshIfOnline()

        suspend fun applyState(apply: suspend AppAuthState.() -> Unit) = this@AuthService.applyState(apply)

        @Suppress("unused")
        suspend fun <T> updateState(update: AppAuthState.Builder.(AppAuthState) -> T): T = this@AuthService.updateState(update)

        suspend fun registerSource(
            source: SourceMetadata,
            success: suspend (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit
        ) =
            this@AuthService.registerSource(source, success, failure)

        fun updateSource(source: SourceMetadata, success: (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit) =
                this@AuthService.updateSource(source, success, failure)

        suspend fun unregisterSources(sources: Iterable<SourceMetadata>) =
                this@AuthService.unregisterSources(sources)

        suspend fun invalidate(token: String?, disableRefresh: Boolean) = this@AuthService.invalidate(token, disableRefresh)

        var isInLoginActivity: Boolean
            get() = this@AuthService.isInLoginActivity
            set(value) {
                this@AuthService.isInLoginActivity = value
            }
    }

    private fun updateSource(
        source: SourceMetadata,
        success: (AppAuthState, SourceMetadata) -> Unit,
        failure: (Exception?) -> Unit
    ) {
        executor.execute {
            updateState {
                it.relevantManagers.any { manager ->
                    manager.updateSource(this, source, success, failure)
                }
            }
        }
        loginManagers
    }

    /**
     * Representation of possible states, whether the login succeeded, failed, or the logout occurred.
     * This class will replace the LoginListener callbacks in the future
     */
    sealed interface AuthLoginListener {
        data class AuthStateFailure(val manager: LoginManager?, val exception: Exception?) :
            AuthLoginListener

        data class AuthStateSuccess(
            val manager: LoginManager? = null
        ) : AuthLoginListener

        data class AuthStateLogout(val manager: LoginManager?, val authState: AppAuthState) :
            AuthLoginListener
    }

    private suspend fun unregisterSources(sources: Iterable<SourceMetadata>) {
        updateState {
            sourceMetadata -= sources.toSet()
        }
        doRefresh()
    }

    inner class LoginListenerRegistry(val listener: LoginListener) {
        val id = ++loginListenerId
        var lastUpdated = 0L
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AuthService::class.java)
        const val RETRY_MIN_DELAY = 5L
        const val RETRY_MAX_DELAY = 86400L
        const val PRIVACY_POLICY_URL_PROPERTY = "org.radarcns.android.auth.portal.ManagementPortalClient.privacyPolicyUrl"
        const val BASE_URL_PROPERTY = "org.radarcns.android.auth.portal.ManagementPortalClient.baseUrl"
    }
}
