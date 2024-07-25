package org.radarbase.android.splash

import android.app.Activity
import android.content.Intent
import android.content.Intent.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthServiceConnection
import org.radarbase.android.auth.LoginListener
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.producer.AuthenticationException
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Ensure that settings and authentication is loaded
 */
@Keep
abstract class SplashActivity : AppCompatActivity() {
    private lateinit var authConnection: AuthServiceConnection
    private lateinit var loginListener: LoginListener
    private lateinit var config: RadarConfiguration
    private lateinit var networkReceiver: NetworkConnectedReceiver

    protected var configReceiver: Boolean = false

    protected var state: Int = STATE_INITIAL
    protected abstract val delayMs: Long
    protected var startedAt: Long = 0
    protected var enable = true
    protected var waitForFullFetchMs = 0L

    protected lateinit var handler: Handler
    protected var startActivityFuture: Runnable? = null
    private val configObserver: Observer<SingleRadarConfiguration> = Observer { config ->
        updateConfig(config.status, allowPartialConfiguration = false)
    }

    private fun updateConfig(status: RadarConfiguration.RemoteConfigStatus, allowPartialConfiguration: Boolean) {
        if (enable
            && (lifecycle.currentState == Lifecycle.State.RESUMED
                    || lifecycle.currentState == Lifecycle.State.STARTED)
            && state != STATE_AUTHORIZING
        ) {
            if (
                status == RadarConfiguration.RemoteConfigStatus.FETCHED
                || (status == RadarConfiguration.RemoteConfigStatus.PARTIALLY_FETCHED
                        && (waitForFullFetchMs <= 0L || allowPartialConfiguration))
            ) {
                logger.info("Config has been fetched, checking authentication")
                stopConfigListener()
                startAuthConnection()
            } else if (status == RadarConfiguration.RemoteConfigStatus.PARTIALLY_FETCHED) {
                handler.postDelayed({
                    updateConfig(radarConfig.status, allowPartialConfiguration = true)
                }, waitForFullFetchMs)
            }
        }
    }

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!enable) {
            return
        }
        networkReceiver = NetworkConnectedReceiver(this, null)

        loginListener = createLoginListener()
        authConnection = AuthServiceConnection(this@SplashActivity, loginListener)
        config = radarConfig
        configReceiver = false
        handler = Handler(Looper.getMainLooper())
        startedAt = SystemClock.elapsedRealtime()

        createView()
    }

    protected abstract fun createView()
    protected abstract fun updateView()

    override fun onStart() {
        super.onStart()
        if (!enable) {
            return
        }

        logger.info("Starting SplashActivity")
        networkReceiver.register()

        when (config.status) {
            RadarConfiguration.RemoteConfigStatus.UNAVAILABLE -> {
                logger.info("Firebase unavailable")
                updateState(STATE_FIREBASE_UNAVAILABLE)
            }
            RadarConfiguration.RemoteConfigStatus.FETCHED -> {
                logger.info("Firebase fetched, starting AuthService")
                startAuthConnection()
            }
            else -> {
                logger.info("Starting listening for configuration updates")
                if (!networkReceiver.state.isConnected) {
                    updateState(STATE_DISCONNECTED)
                }
                startConfigReceiver()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!enable) {
            return
        }
        logger.info("Stopping splash")
        stopConfigListener()
        stopAuthConnection()

        networkReceiver.unregister()
    }

    protected open fun updateState(newState: Int) {
        if (newState != state) {
            state = newState

            runOnUiThread {
                updateView()
            }
        }
    }

    protected open fun createLoginListener(): LoginListener {
        return object : LoginListener {
            override fun loginFailed(manager: LoginManager?, ex: Exception?) {
                if (ex != null && ex is IOException && ex !is AuthenticationException) {
                    updateState(STATE_DISCONNECTED)
                } else {
                    startActivity(radarApp.loginActivity)
                }
            }

            override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
                if (authState.isPrivacyPolicyAccepted) {
                    startActivity(radarApp.mainActivity)
                } else {
                    startActivity(radarApp.loginActivity)
                }
            }

            override fun loggedOut(manager: LoginManager?, authState: AppAuthState) = Unit
        }
    }

    protected open fun startActivity(activity: Class<out Activity>) {
        logger.debug("Scheduling start of SplashActivity")
        handler.post {
            if (state == STATE_FINISHED) {
                return@post
            }
            updateState(STATE_STARTING)
            startActivityFuture?.also {
                handler.removeCallbacks(it)
            }
            Runnable {
                updateState(STATE_FINISHED)

                logger.info("Starting SplashActivity")
                Intent(this@SplashActivity, activity).also {
                    it.flags = FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_TASK_ON_HOME or FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
                    onWillStartActivity()
                    startActivity(it)
                    onDidStartActivity()
                }
                finish()
            }.also { runnable ->
                startActivityFuture = runnable
                val delayRemaining = Math.max(0, delayMs - (SystemClock.elapsedRealtime() - startedAt))
                handler.postDelayed(runnable, delayRemaining)
            }
        }
    }

    protected open fun onWillStartActivity() {}

    protected open fun onDidStartActivity() {}

    protected open fun startConfigReceiver() {
        updateState(STATE_FETCHING_CONFIG)
        config.forceFetch()
        radarConfig.config.observe(this, configObserver)
        configReceiver = true
    }

    protected open fun startAuthConnection() {
        if (!authConnection.isBound) {
            updateState(STATE_AUTHORIZING)
            authConnection.bind()
        }
    }

    protected open fun stopConfigListener() {
        if (configReceiver) {
            radarConfig.config.removeObserver(configObserver)
            configReceiver = false
        }
    }

    protected open fun stopAuthConnection() {
        authConnection.unbind()
    }

    @Keep
    companion object {
        private val logger = LoggerFactory.getLogger(SplashActivity::class.java)

        const val STATE_INITIAL = 1
        const val STATE_FETCHING_CONFIG = 2
        const val STATE_AUTHORIZING = 3
        const val STATE_STARTING = 4
        const val STATE_DISCONNECTED = 5
        const val STATE_FIREBASE_UNAVAILABLE = 6
        const val STATE_FINISHED = 7
    }
}
