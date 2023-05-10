package org.radarbase.android.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAdapter.STATE_CONNECTED
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
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
            try {
                context.unregisterReceiver(receiver)
            } catch (ex: Exception) {
                logger.debug("Failed to unregister BluetoothStateReceiver: {}", ex.toString())
            }
            isRegistered = false
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BluetoothStateReceiver::class.java)

        val Context.bluetoothAdapter: BluetoothAdapter?
            get() = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        val Context.bluetoothIsEnabled: Boolean
            get() = bluetoothAdapter?.isEnabled == true

        val bluetoothPermissionList: List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        }

        val Context.hasBluetoothPermission: Boolean
            get() = bluetoothPermissionList.all { permission ->
                ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
    }
}
