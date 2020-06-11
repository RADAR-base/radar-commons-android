package org.radarbase.android.util

import android.bluetooth.BluetoothAdapter

fun String.takeTrimmedIfNotEmpty(): String? = trim { it <= ' ' }
            .takeUnless(String::isEmpty)
