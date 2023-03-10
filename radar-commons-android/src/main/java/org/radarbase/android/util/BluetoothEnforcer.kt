package org.radarbase.android.util

import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.ENABLE_BLUETOOTH_REQUESTS
import org.radarbase.android.RadarService
import org.radarbase.android.util.BluetoothStateReceiver.Companion.bluetoothIsEnabled
import org.radarbase.android.util.BluetoothStateReceiver.Companion.hasBluetoothPermission
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class BluetoothEnforcer(
    private val context: ComponentActivity,
    private val radarConnection: ManagedServiceConnection<IRadarBinder>,
) {
    private val cooldown: Duration
    private val handler = Handler(Looper.getMainLooper())
    private var isRequestingBluetooth = false
    private val config = context.radarConfig
    private val enableBluetoothRequests: ChangeRunner<Boolean>
    private val bluetoothIsNeeded = ChangeRunner(false)
    private lateinit var bluetoothNeededRegistration: BroadcastRegistration

    private val bluetoothStateReceiver: BluetoothStateReceiver

    var isEnabled: Boolean
        get() = enableBluetoothRequests.value
        set(value) {
            enableBluetoothRequests.applyIfChanged(value) { enableRequests ->
                config.put(ENABLE_BLUETOOTH_REQUESTS, enableRequests)
                config.persistChanges()
                if (bluetoothIsNeeded.value) {
                    bluetoothStateReceiver.register()
                } else {
                    bluetoothStateReceiver.unregister()
                }
            }
        }

    private val prefs = context.getSharedPreferences("org.radarbase.android.util.BluetoothEnforcer", MODE_PRIVATE)
    private val resetBluetoothRequests = Runnable {
        config.reset(ENABLE_BLUETOOTH_REQUESTS)
        isEnabled = config.latestConfig.getBoolean(ENABLE_BLUETOOTH_REQUESTS, true)
    }

    init {
        val latestConfig = config.latestConfig
        val lastRequest = prefs.getLong(LAST_REQUEST, 0L)
        cooldown = latestConfig.getLong(BLUETOOTH_REQUEST_COOLDOWN, 3.days.inWholeSeconds).seconds
        if (lastRequest + cooldown.inWholeMilliseconds < System.currentTimeMillis()) {
            resetBluetoothRequests.run()
        }

        radarConnection.onBoundListeners += {
            updateNeedsBluetooth(it.needsBluetooth())
        }
        enableBluetoothRequests = ChangeRunner(
            latestConfig.getBoolean(ENABLE_BLUETOOTH_REQUESTS, true)
        )

        bluetoothStateReceiver = BluetoothStateReceiver(context) { enabled ->
            if (!enabled) {
                requestEnableBt()
            }
        }
    }

    fun start() {
        testBindBluetooth()

        LocalBroadcastManager.getInstance(context).apply {
            bluetoothNeededRegistration = register(RadarService.ACTION_BLUETOOTH_NEEDED_CHANGED) { _, _ ->
                testBindBluetooth()
            }
        }
    }

    fun stop() {
        bluetoothNeededRegistration.unregister()
        bluetoothIsNeeded.applyIfChanged(false) {
            bluetoothStateReceiver.unregister()
        }
    }

    private fun updateNeedsBluetooth(value: Boolean) {
        bluetoothIsNeeded.applyIfChanged(value) { doesNeedBluetooth ->
            if (doesNeedBluetooth && isEnabled) {
                bluetoothStateReceiver.register()
                requestEnableBt()
            } else {
                bluetoothStateReceiver.unregister()
            }
        }
    }

    private fun testBindBluetooth() {
        radarConnection.applyBinder {
            updateNeedsBluetooth(needsBluetooth())
        }
    }

    /**
     * Sends an intent to request bluetooth to be turned on.
     */
    private fun requestEnableBt() {
        handler.post {
            if (isRequestingBluetooth || !context.hasBluetoothPermission || !isEnabled) {
                return@post
            }
            isRequestingBluetooth = handler.postDelayed({
                try {
                    if (!context.hasBluetoothPermission) {
                        logger.error("Cannot initiate Bluetooth scan without scan permissions")
                        return@postDelayed
                    }
                    if (isEnabled && !context.bluetoothIsEnabled) {
                        prefs.edit()
                            .putLong(LAST_REQUEST, System.currentTimeMillis())
                            .apply()

                        handler.removeCallbacks(resetBluetoothRequests)
                        handler.postDelayed(resetBluetoothRequests, cooldown.inWholeMilliseconds)

                        isEnabled = false

                        context.startActivityForResult(Intent().apply {
                            action = BluetoothAdapter.ACTION_REQUEST_ENABLE
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }, REQUEST_ENABLE_BT)
                    }
                } catch (ex: Throwable) {
                    logger.error("Failed to request Bluetooth permissions", ex)
                } finally {
                    isRequestingBluetooth = false
                }
            }, 1000L)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode == REQUEST_ENABLE_BT) {
            isEnabled = resultCode == RESULT_OK
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BluetoothEnforcer::class.java)
        const val REQUEST_ENABLE_BT: Int = 6944
        private const val LAST_REQUEST: String = "lastRequest"
        private const val BLUETOOTH_REQUEST_COOLDOWN = "bluetooth_request_cooldown"
    }
}
