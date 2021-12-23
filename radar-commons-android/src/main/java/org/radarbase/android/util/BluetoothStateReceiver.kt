package org.radarbase.android.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.STATE_CONNECTED
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.content.IntentFilter
import org.slf4j.LoggerFactory

class BluetoothStateReceiver(
    private val context: Context,
    private val listener: (enabled: Boolean) -> Unit
): SpecificReceiver {

    private val stateCache = ChangeRunner<Boolean>()
    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)

                stateCache.applyIfChanged(state == STATE_CONNECTED) {
                    notifyListener()
                }

                logger.debug("Bluetooth is in state {} (enabled: {})", state, stateCache.value)
            }
        }
    }

    init {
        stateCache.applyIfChanged(context.bluetoothIsEnabled) {
            notifyListener()
        }
    }

    @Synchronized
    override fun register() {
        if (!isRegistered) {
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            isRegistered = true
        }
    }

    override fun notifyListener() {
        listener(stateCache.value)
    }

    @Synchronized
    override fun unregister() {
        if (isRegistered) {
            context.unregisterReceiver(receiver)
            isRegistered = false
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BluetoothStateReceiver::class.java)

        val Context.bluetoothAdapter: BluetoothAdapter?
            get() = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        val Context.bluetoothIsEnabled: Boolean
            get() = bluetoothAdapter?.isEnabled == true
    }
}
