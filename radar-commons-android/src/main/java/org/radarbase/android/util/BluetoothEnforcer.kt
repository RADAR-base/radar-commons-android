package org.radarbase.android.util

import android.bluetooth.BluetoothAdapter
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.ENABLE_BLUETOOTH_REQUESTS
import org.radarbase.android.RadarService
import java.util.concurrent.TimeUnit

class BluetoothEnforcer(
        private val context: ComponentActivity,
        private val radarConnection: ManagedServiceConnection<IRadarBinder>
) {
    private val config = context.radarConfig
    private val enableBluetoothRequests: ChangeRunner<Boolean> = ChangeRunner(false)
    private val bluetoothIsNeeded = ChangeRunner(false)
    private val bluetoothStateReceiver: BluetoothStateReceiver = BluetoothStateReceiver(context) { enabled ->
        if (!enabled) requestEnableBt()
    }
    private lateinit var bluetoothNeededReceiver: BroadcastRegistration

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

    init {
        val lastRequest = prefs.getLong(LAST_REQUEST, 0L)
        val cooldown = TimeUnit.SECONDS.toMillis(config.getLong(BLUETOOTH_REQUEST_COOLDOWN, TimeUnit.DAYS.toSeconds(3)))
        if (lastRequest + cooldown < System.currentTimeMillis()) {
            config.reset(ENABLE_BLUETOOTH_REQUESTS)
        }
        isEnabled = config.getBoolean(ENABLE_BLUETOOTH_REQUESTS, true)
    }

    fun start() {
        testBindBluetooth()

        LocalBroadcastManager.getInstance(context).apply {
            bluetoothNeededReceiver = register(RadarService.ACTION_BLUETOOTH_NEEDED_CHANGED) { _, _ ->
                testBindBluetooth()
            }
        }
    }

    fun stop() {
        bluetoothNeededReceiver.unregister()
        bluetoothIsNeeded.applyIfChanged(false) {
            bluetoothStateReceiver.unregister()
        }
    }


    private fun testBindBluetooth() {
        radarConnection.applyBinder {
            bluetoothIsNeeded.applyIfChanged(needsBluetooth()) { doesNeedBluetooth ->
                if (doesNeedBluetooth && isEnabled) {
                    bluetoothStateReceiver.register()
                    requestEnableBt()
                } else {
                    bluetoothStateReceiver.unregister()
                }
            }
        }
    }

    /**
     * Sends an intent to request bluetooth to be turned on.
     */
    private fun requestEnableBt() {
        if (isEnabled && !BluetoothHelper.bluetoothIsEnabled) {
            prefs.edit()
                    .putLong(LAST_REQUEST, System.currentTimeMillis())
                    .apply()

            isEnabled = false

            context.startActivityForResult(Intent().apply {
                action = BluetoothAdapter.ACTION_REQUEST_ENABLE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, REQUEST_ENABLE_BT)
        }
    }

    companion object {
        const val REQUEST_ENABLE_BT: Int = 6944
        private const val LAST_REQUEST: String = "lastRequest"
        private const val BLUETOOTH_REQUEST_COOLDOWN = "bluetooth_request_cooldown"
    }
}
