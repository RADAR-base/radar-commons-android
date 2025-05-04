package org.radarbase.android.util

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.Logger

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

inline fun <reified T> Context.applySystemService(
    type: String,
    callback: (T) -> Boolean
): Boolean? {
    return (getSystemService(type) as T?)?.let(callback)
}


internal fun Map<String, Any>.toJson(): JSONObject = buildJson {
    forEach { (k, v) -> this@buildJson.put(k, v) }
}

internal inline fun buildJson(config: JSONObject.() -> Unit): JSONObject {
    return JSONObject().apply(config)
}

internal inline fun buildJsonArray(config: JSONArray.() -> Unit): JSONArray {
    return JSONArray().apply(config)
}

internal fun JSONObject.optNonEmptyString(key: String): String? =
    if (isNull(key)) null else optString(key).takeTrimmedIfNotEmpty()?.takeIf { it != "null" }

internal fun JSONObject.toStringMap(): Map<String, String> = buildMap {
    this@toStringMap.keys().forEach { key ->
        this@buildMap.put(key, getString(key))
    }
}

inline fun <T> Logger.runSafeOrNull(block: () -> T) = try {
    block()
} catch (ex: Exception) {
    this.error("Failed to complete task", ex)
    null
}

internal fun JSONArray.asJSONObjectSequence(): Sequence<JSONObject> = sequence {
    for (i in 0 until length()) {
        yield(getJSONObject(i))
    }
}

internal fun JSONObject.putAll(map: Map<String, Any?>) {
    for (entry in map) {
        put(entry.key, entry.value)
    }
}

internal inline fun <reified T : Any> T.equalTo(other: Any?, vararg fields: T.() -> Any?): Boolean {
    if (this === other) return true
    if (other == null || javaClass !== other.javaClass) return false
    other as T
    return fields.all { field -> field() == other.field() }
}

//
//inline fun <T, R> LiveData<T>.map(crossinline transform: (T) -> R): LiveData<R> =
//    Transformations.map(this) { transform(it) }
//
//fun <T> LiveData<T>.distinctUntilChanged(): LiveData<T> =
//    Transformations.distinctUntilChanged(this)
//
//inline fun <T, R> LiveData<T>.switchMap(crossinline transform: (T) -> LiveData<R>): LiveData<R> =
//    Transformations.switchMap(this) { transform(it) }
