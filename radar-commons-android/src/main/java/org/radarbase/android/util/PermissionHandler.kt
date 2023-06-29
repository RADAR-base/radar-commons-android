package org.radarbase.android.util

import android.Manifest.permission.*
import android.app.AppOpsManager
import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

fun Context.isPermissionGranted(permission: String): Boolean = when (permission) {
    LOCATION_SERVICE -> applySystemService<LocationManager>(LOCATION_SERVICE) { locationManager ->
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } ?: true
    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> applySystemService<PowerManager>(Context.POWER_SERVICE) { powerManager ->
        powerManager.isIgnoringBatteryOptimizations(applicationContext.packageName)
    } ?: true
    PACKAGE_USAGE_STATS -> applySystemService<AppOpsManager>(Context.APP_OPS_SERVICE) { appOps ->
        @Suppress("DEPRECATION")
        (AppOpsManager.MODE_ALLOWED == appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), packageName))
    } ?: true
    SYSTEM_ALERT_WINDOW -> Settings.canDrawOverlays(this)
    else -> PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, permission)
}

val ACCESS_BACKGROUND_LOCATION_COMPAT: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    ACCESS_BACKGROUND_LOCATION
} else {
    "android.permission.ACCESS_BACKGROUND_LOCATION"
}
