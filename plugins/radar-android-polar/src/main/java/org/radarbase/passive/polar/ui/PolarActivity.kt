package org.radarbase.passive.polar.ui

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.passive.polar.PolarProvider
import org.radarbase.passive.polar.PolarState
import org.radarbase.passive.polar.R
import org.slf4j.LoggerFactory

/**
 * Explicit control screen for the Polar device.
 */
class PolarActivity : AppCompatActivity() {

    private enum class Action { ENABLE_BLUETOOTH, CONNECT, START, STOP, NONE }

    private var provider: PolarProvider? = null
    private val state: PolarState?
        get() = provider?.connection?.sourceState

    private lateinit var deviceNameText: TextView
    private lateinit var statusText: TextView
    private lateinit var bluetoothIcon: ImageView
    private lateinit var messageText: TextView
    private lateinit var primaryButton: Button

    private var currentAction: Action = Action.NONE
    private var wasConnected: Boolean = false
    private var connectionStateKnown: Boolean = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            render()
            uiHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val radarServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? IRadarBinder ?: return
            provider = binder.connections.filterIsInstance<PolarProvider>().firstOrNull()
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            provider = null
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_polar)

        deviceNameText = findViewById(R.id.polar_device_name)
        statusText = findViewById(R.id.polar_status_text)
        bluetoothIcon = findViewById(R.id.polar_bluetooth_icon)
        messageText = findViewById(R.id.polar_message)
        primaryButton = findViewById(R.id.polar_primary_button)

        primaryButton.setOnClickListener { onPrimaryClicked() }
        render()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshRunnable)
        try {
            unbindService(radarServiceConnection)
        } catch (ex: IllegalArgumentException) {
            logger.warn("Polar control screen was not bound to the service")
        }
        super.onStop()
    }

    private fun onPrimaryClicked() {
        when (currentAction) {
            Action.ENABLE_BLUETOOTH -> openBluetoothSettings()
            Action.CONNECT -> {
                logger.debug("User requested to connect the Polar device")
                state?.polarController?.connectDevice()
            }
            Action.START -> {
                logger.debug("User requested to start Polar collection")
                state?.polarController?.startCollecting()
            }
            Action.STOP -> {
                logger.debug("User requested to stop Polar collection")
                state?.polarController?.stopCollecting()
            }
            Action.NONE -> { /* button is disabled in this state */ }
        }
        render()
    }

    private fun isBluetoothOn(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    private fun render() {
        if (!isBluetoothOn()) {
            wasConnected = false
            connectionStateKnown = false
            deviceNameText.text = state?.deviceName ?: getString(R.string.polarNoDevice)
            statusText.text = getString(R.string.polarStatusBluetoothOff)
            bluetoothIcon.alpha = 0.3f
            messageText.visibility = View.VISIBLE
            messageText.text = getString(R.string.polarBluetoothOffMessage)
            primaryButton.text = getString(R.string.polarTurnOnBluetooth)
            primaryButton.isEnabled = true
            currentAction = Action.ENABLE_BLUETOOTH
            return
        }

        val st = state
        val controller = st?.polarController
        val status = st?.status ?: SourceStatusListener.Status.DISCONNECTED
        val collecting = st?.isCollecting ?: false
        val connectionRequested = st?.connectionRequested ?: false
        val connected = status == SourceStatusListener.Status.CONNECTED

        deviceNameText.text = st?.deviceName ?: getString(R.string.polarNoDevice)
        bluetoothIcon.alpha = if (connected) 1.0f else 0.6f
        messageText.visibility = View.GONE

        if (st != null) {
            if (connectionStateKnown && connected && !wasConnected) {
                Toast.makeText(this, R.string.polarDeviceConnectedToast, Toast.LENGTH_SHORT).show()
            }
            wasConnected = connected
            connectionStateKnown = true
        }

        when {
            controller == null -> {
                statusText.text = getString(R.string.polarStatusNotConnected)
                primaryButton.text = getString(R.string.polarConnectDevice)
                primaryButton.isEnabled = false
                currentAction = Action.NONE
            }
            collecting -> {
                statusText.text = getString(R.string.polarStatusConnected)
                primaryButton.text = getString(R.string.polarStopCollection)
                primaryButton.isEnabled = true
                currentAction = Action.STOP
            }
            connected -> {
                statusText.text = getString(R.string.polarStatusConnected)
                primaryButton.text = getString(R.string.polarStartCollection)
                primaryButton.isEnabled = true
                currentAction = Action.START
            }
            connectionRequested -> {
                statusText.text = getString(R.string.polarStatusConnecting)
                primaryButton.text = getString(R.string.polarStatusConnecting)
                primaryButton.isEnabled = false
                currentAction = Action.NONE
            }
            else -> {
                statusText.text = getString(R.string.polarStatusNotConnected)
                primaryButton.text = getString(R.string.polarConnectDevice)
                primaryButton.isEnabled = true
                currentAction = Action.CONNECT
            }
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (ex: Exception) {
            logger.warn("Could not open Bluetooth settings, falling back to general settings", ex)
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Exception) {
                logger.error("Could not open settings to enable Bluetooth")
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PolarActivity::class.java)
        private const val REFRESH_INTERVAL_MS = 800L
    }
}
