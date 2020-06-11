package org.radarbase.android.util

import android.bluetooth.BluetoothAdapter

object BluetoothHelper {
    val bluetoothIsEnabled: Boolean
        get() = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

}
