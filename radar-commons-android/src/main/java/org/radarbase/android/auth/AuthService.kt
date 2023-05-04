package org.radarbase.android.auth

import android.content.Intent
import android.content.Intent.*
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import androidx.annotation.Keep
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.portal.ManagementPortalLoginManager
import org.radarbase.android.util.SafeHandler
import org.slf4j.LoggerFactory
import java.net.ConnectException

@Keep
open class AuthService : LifecycleService(), LoginListener {
    private lateinit var mainHandler: Handler
    private lateinit var appAuth: AppAuthState
    lateinit var loginManagers: List<LoginManager>
    private val listeners: MutableList<LoginListenerRegistration> = mutableListOf()
    lateinit var config: RadarConfiguration
    val handler = SafeHandler.getInstance("AuthService", THREAD_PRIORITY_BACKGROUND)
    var loginListenerId: Long = 0
    private var configRegistration: LoginListenerRegistration? = null
    private var isConnected: Boolean = false
    private var sourceRegistrationStarted: Boolean = false
    private val successHandlers: MutableList<() -> Unit> = mutableListOf()

    private lateinit var broadcaster: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        config = RadarConfiguration.getInstance(this)
        broadcaster = LocalBroadcastManager.getInstance(this)
        mainHandler = Handler(mainLooper)
        appAuth = AppAuthState()
        handler.start()
        loginManagers = createLoginManagers(appAuth)

        config.config.observe(this) { singleConfig ->
            loginManagers.forEach {
                it.configure(singleConfig)
            }
        }

        configRegistration = addLoginListener(object : LoginListener {
            override fun loginFailed(manager: LoginManager?, ex: java.lang.Exception?) {
                // no action required
            }

            override fun logoutSucceeded(manager: LoginManager?, authState: AppAuthState) {
                config.updateWithAuthState(this@AuthService, authState)
            }

            override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
                config.updateWithAuthState(this@AuthService, authState)
            }
        })
    }

    override fun loginFailed(manager: LoginManager?, ex: java.lang.Exception?) {
        handler.executeReentrant {
            logger.info("Login failed: {}", ex?.toString())

            if (ex is ConnectException) {
                isConnected = false
            }
            callListeners {
                it.loginListener.loginFailed(manager, ex)
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
            listeners
                .filter { it.lastUpdate < sinceUpdate }
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
            appAuth = authState

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
        super.onDestroy()
        configRegistration?.let { removeLoginListener(it) }
        handler.stop()
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
    protected open fun createLoginManagers(appAuth: AppAuthState): List<LoginManager> = listOf(
        ManagementPortalLoginManager(appAuth),
    )

    private fun updateState(update: AppAuthState.Builder.() -> Unit) = handler.executeReentrant {
        val newAppAuth = appAuth.alter(update)
        if (
            newAppAuth.userId == appAuth.userId &&
            newAppAuth.token == appAuth.token &&
            newAppAuth.baseUrl == appAuth.baseUrl
        ) {
            return@executeReentrant
        }
        if (newAppAuth.userId != null && newAppAuth.token != null) {
            var latestAuthState = newAppAuth
            loginManagers.forEach {
                val managerAuthState = it.fetch(latestAuthState)
                if (managerAuthState != null) {
                    latestAuthState = managerAuthState
                }
            }
            loginSucceeded(null, latestAuthState)
        }
    }

    private fun applyState(function: AppAuthState.() -> Unit) = handler.executeReentrant {
        appAuth.function()
    }

    fun invalidate(token: String?, disableRefresh: Boolean) {
        handler.executeReentrant {
            logger.info("Invalidating authentication state")
            if (token == null || token == appAuth.token) {
                val invalidatedAuth = if (disableRefresh) {
                    appAuth.reset()
                } else {
                    appAuth.alter { invalidate() }
                }

                logoutSucceeded(null, invalidatedAuth)
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
                    }
                    successHandlers += { success(newAppAuth, newSource) }
                    if (!sourceRegistrationStarted) {
                        handler.delay(1_000L) {
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
                    failure(ex)
                })
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return AuthServiceBinder()
    }

    inner class AuthServiceBinder: Binder() {
        fun addLoginListener(loginListener: LoginListener): LoginListenerRegistration = this@AuthService.addLoginListener(loginListener)

        fun removeLoginListener(registration: LoginListenerRegistration) = this@AuthService.removeLoginListener(registration)

        val managers: List<LoginManager>
            get() = loginManagers

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

        var authServiceClass: Class<out AuthService> = AuthService::class.java
    }
}
