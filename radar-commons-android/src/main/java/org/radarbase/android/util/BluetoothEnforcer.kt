package org.radarbase.android.util

import android.app.Activity.RESULT_CANCELED
import android.bluetooth.BluetoothAdapter
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.ENABLE_BLUETOOTH_REQUESTS
import org.radarbase.android.RadarService
import org.radarbase.android.util.BluetoothStateReceiver.Companion.bluetoothIsEnabled
import java.util.concurrent.TimeUnit

class BluetoothEnforcer(
        private val context: ComponentActivity,
        private val radarConnection: ManagedServiceConnection<IRadarBinder>
) {
    private val config = context.radarConfig
    private val enableBluetoothRequests: ChangeRunner<Boolean>
    private val bluetoothIsNeeded = ChangeRunner(false)
    private lateinit var bluetoothNeededRegistration: BroadcastRegistration

    private val bluetoothStateReceiver: BluetoothStateReceiver = BluetoothStateReceiver(context) { enabled ->
        if (!enabled) requestEnableBt()
    }

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
        enableBluetoothRequests = ChangeRunner(config.getBoolean(ENABLE_BLUETOOTH_REQUESTS, true))
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
        if (isEnabled && !bluetoothIsEnabled) {
            prefs.edit()
                    .putLong(LAST_REQUEST, System.currentTimeMillis())
                    .apply()

            context.startActivityForResult(Intent().apply {
                action = BluetoothAdapter.ACTION_REQUEST_ENABLE
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, REQUEST_ENABLE_BT)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode == REQUEST_ENABLE_BT) {
            isEnabled = resultCode != RESULT_CANCELED
        }
    }

    companion object {
        const val REQUEST_ENABLE_BT: Int = 6944
        private const val LAST_REQUEST: String = "lastRequest"
        private const val BLUETOOTH_REQUEST_COOLDOWN = "bluetooth_request_cooldown"
    }
}
