package org.radarbase.android.util

import android.app.PendingIntent
import android.content.Context
import android.os.Build

fun String.takeTrimmedIfNotEmpty(): String? = trim { it <= ' ' }
            .takeUnless(String::isEmpty)

/**
 * Converts an integer to a PendingIntent flag with appropriate mutability settings.
 *
 * Android 14 (API level 34) introduces stricter security requirements for `PendingIntents`.
 * - By default, `PendingIntents` should be immutable (`FLAG_IMMUTABLE`) unless explicitly required to be mutable.
 * - Using the mutable flag without necessity may lead to security vulnerabilities, as mutable `PendingIntents`
 *   can be modified by other apps if granted.
 *
 * This function checks the Android version to set flags appropriately:
 * - For API level 34 and above (`UPSIDE_DOWN_CAKE`), includes `FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT` to bypass the
 *   implicit intent restriction if `mutable` is true.
 * - For API level 31 (Android 12, `S`) to API level 33, `FLAG_MUTABLE` is used when `mutable` is true.
 * - For any other case or if `mutable` is false, the flag defaults to `FLAG_IMMUTABLE`.
 *
 * @param mutable Determines if the `PendingIntent` needs to be mutable (default: false).
 * @return The calculated `PendingIntent` flag with the correct mutability based on API level.
 */
fun Int.toPendingIntentFlag(mutable: Boolean = false) = this or when {
    mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
    mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> PendingIntent.FLAG_MUTABLE
    !mutable -> PendingIntent.FLAG_IMMUTABLE
    else -> 0
}

inline fun <reified T> Context.applySystemService(type: String, callback: (T) -> Boolean): Boolean? {
    return (getSystemService(type) as T?)?.let(callback)
}
