package org.radarbase.android.splash

import android.app.Activity
import android.content.Intent
import android.content.Intent.*
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.RadarApplication
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthServiceConnection
import org.radarbase.android.auth.LoginListener
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.radarApp
import org.radarbase.android.util.BroadcastRegistration
import org.radarbase.android.util.NetworkConnectedReceiver
import org.radarbase.android.util.register
import org.slf4j.LoggerFactory
import java.net.ConnectException

/**
 * Ensure that settings and authentication is loaded
 */
abstract class SplashActivity : AppCompatActivity() {
    private lateinit var authConnection: AuthServiceConnection
    private lateinit var loginListener: LoginListener
    private lateinit var config: RadarConfiguration
    private var receiveConfigUpdates: BroadcastRegistration? = null
    private lateinit var networkReceiver: NetworkConnectedReceiver

    protected var configReceiver: Boolean = false

    protected var state: Int = STATE_INITIAL
    protected abstract val delayMs: Long
    protected var startedAt: Long = 0

    protected lateinit var handler: Handler
    protected var startActivityFuture: Runnable? = null

    @CallSuper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        networkReceiver = NetworkConnectedReceiver(this, null)

        loginListener = createLoginListener()
        authConnection = AuthServiceConnection(this@SplashActivity, loginListener)
        config = (application as RadarApplication).configuration
        configReceiver = false
        handler = Handler(mainLooper)
        startedAt = SystemClock.elapsedRealtime()

        createView()
    }

    protected abstract fun createView()
    protected abstract fun updateView()

    override fun onStart() {
        super.onStart()
        logger.info("Starting SplashActivity")
        networkReceiver.register()

        when (config.status) {
            RadarConfiguration.FirebaseStatus.UNAVAILABLE -> {
                logger.info("Firebase unavailable")
                updateState(STATE_FIREBASE_UNAVAILABLE)
            }
            RadarConfiguration.FirebaseStatus.FETCHED -> {
                logger.info("Firebase fetched, starting AuthService")
                startAuthConnection()
            }
            else -> {
                logger.info("Starting listening for configuration updates")
                if (!networkReceiver.isConnected) {
                    updateState(STATE_DISCONNECTED)
                }
                startConfigReceiver()
            }
        }
    }

    override fun onStop() {
        super.onStop()
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
                if (ex is ConnectException) {
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
        }
    }

    protected open fun startActivity(activity: Class<out Activity>) {
        logger.debug("Scheduling start of activity {}", activity.simpleName)
        handler.post {
            if (state == STATE_FINISHED) {
                return@post
            }
            updateState(STATE_STARTING)
            startActivityFuture?.also {
                handler.removeCallbacks(startActivityFuture)
            }
            Runnable {
                updateState(STATE_FINISHED)

                logger.info("Starting activity {}", activity.simpleName)
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
        LocalBroadcastManager.getInstance(this)
                .register(RadarConfiguration.RADAR_CONFIGURATION_CHANGED) { _, _ ->
                    if ((lifecycle.currentState == Lifecycle.State.RESUMED
                                    || lifecycle.currentState == Lifecycle.State.STARTED)
                            && config.status == RadarConfiguration.FirebaseStatus.FETCHED) {
                        logger.info("Config has been fetched, checking authentication")
                        stopConfigListener()
                        startAuthConnection()
                    }
                }
        config.fetch()
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
            receiveConfigUpdates?.unregister()
            configReceiver = false
        }
    }

    protected open fun stopAuthConnection() {
        authConnection.unbind()
    }

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
