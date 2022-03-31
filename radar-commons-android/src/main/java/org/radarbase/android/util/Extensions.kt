package org.radarbase.android.util

import android.app.PendingIntent
import android.os.Build

fun String.takeTrimmedIfNotEmpty(): String? = trim { it <= ' ' }
            .takeUnless(String::isEmpty)

fun Int.toPendingIntentFlag(mutable: Boolean = false) = this or when {
    mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
    !mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> PendingIntent.FLAG_IMMUTABLE
    else -> 0
}
