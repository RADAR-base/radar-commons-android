package org.radarbase.android.util

sealed class NeedsBluetoothState

object BluetoothNeeded: NeedsBluetoothState()
object BluetoothNotNeeded: NeedsBluetoothState()