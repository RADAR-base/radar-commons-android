package org.radarbase.android.util

import android.Manifest.permission.*
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.R
import org.radarbase.android.RadarService
import org.slf4j.LoggerFactory

fun Context.isPermissionGranted(permission: String): Boolean = when (permission) {
    LifecycleService.LOCATION_SERVICE -> applySystemService<LocationManager>(Context.LOCATION_SERVICE) { locationManager ->
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
