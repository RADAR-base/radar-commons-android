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
import org.radarbase.android.RadarService.Companion.applySystemService
import org.slf4j.LoggerFactory


open class PermissionHandler(
    private val activity: AppCompatActivity,
    private val mHandler: SafeHandler,
    private val requestPermissionTimeoutMs: Long,
) {
    private val broadcaster = LocalBroadcastManager.getInstance(activity)

    private val needsPermissions: MutableSet<String> = HashSet()
    private val isRequestingPermissions: MutableSet<String> = HashSet()
    private var isRequestingPermissionsTime = java.lang.Long.MAX_VALUE
    private var requestFuture: SafeHandler.HandlerFuture? = null

    private fun onPermissionRequestResult(permission: String, granted: Boolean) {
        mHandler.execute {
            needsPermissions.remove(permission)

            val result = if (granted) PERMISSION_GRANTED else PERMISSION_DENIED
            broadcaster.send(RadarService.ACTION_PERMISSIONS_GRANTED) {
                putExtra(RadarService.EXTRA_PERMISSIONS, arrayOf(Context.LOCATION_SERVICE))
                putExtra(RadarService.EXTRA_GRANT_RESULTS, intArrayOf(result))
            }

            isRequestingPermissions.remove(permission)
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        mHandler.executeReentrant {
            requestFuture?.cancel()
            requestFuture = mHandler.delay(500L) {
                doRequestPermission()
                requestFuture = null
            }
        }
    }

    private fun doRequestPermission() {
        val externallyGrantedPermissions = needsPermissions.filter { activity.isPermissionGranted(it) }

        if (externallyGrantedPermissions.isNotEmpty()) {
            broadcaster.send(RadarService.ACTION_PERMISSIONS_GRANTED) {
                putExtra(RadarService.EXTRA_PERMISSIONS,
                    externallyGrantedPermissions.toTypedArray())
                putExtra(RadarService.EXTRA_GRANT_RESULTS,
                    IntArray(externallyGrantedPermissions.size) { PERMISSION_GRANTED })
            }
            isRequestingPermissions -= externallyGrantedPermissions
            needsPermissions -= externallyGrantedPermissions
        }

        val currentlyNeeded: Set<String> = HashSet(needsPermissions).apply {
            this -= isRequestingPermissions
            if (
                ACCESS_COARSE_LOCATION in this
                || ACCESS_FINE_LOCATION in this
            ) {
                this -= RadarService.ACCESS_BACKGROUND_LOCATION_COMPAT
            }
        }

        when {
            currentlyNeeded.isEmpty() -> logger.info("Already requested all permissions.")
            Context.LOCATION_SERVICE in currentlyNeeded -> {
                addRequestingPermissions(Context.LOCATION_SERVICE)
                requestLocationProvider()
            }
            SYSTEM_ALERT_WINDOW in currentlyNeeded -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addRequestingPermissions(SYSTEM_ALERT_WINDOW)
                    requestSystemWindowPermissions()
                }
            }
            RadarService.PACKAGE_USAGE_STATS_COMPAT in currentlyNeeded -> {
                addRequestingPermissions(RadarService.PACKAGE_USAGE_STATS_COMPAT)
                requestPackageUsageStats()
            }
            RadarService.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT in currentlyNeeded -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addRequestingPermissions(RadarService.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT)
                    requestDisableBatteryOptimization()
                } else {
                    needsPermissions.remove(RadarService.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT)
                }
            }
            else -> {
                addRequestingPermissions(currentlyNeeded)
                try {
                    ActivityCompat.requestPermissions(
                        activity,
                        currentlyNeeded.toTypedArray(),
                        REQUEST_ENABLE_PERMISSIONS,
                    )
                } catch (ex: IllegalStateException) {
                    logger.warn("Cannot request permission on closing activity")
                }
            }
        }
    }

    private fun resetRequestingPermission() {
        isRequestingPermissions.clear()
        isRequestingPermissionsTime = java.lang.Long.MAX_VALUE
    }

    private fun addRequestingPermissions(permission: String) {
        addRequestingPermissions(setOf(permission))
    }

    private fun addRequestingPermissions(permissions: Set<String>) {
        isRequestingPermissions.addAll(permissions)

        if (isRequestingPermissionsTime != java.lang.Long.MAX_VALUE) {
            isRequestingPermissionsTime = System.currentTimeMillis()
            mHandler.delay(requestPermissionTimeoutMs) {
                resetRequestingPermission()
                requestPermissions()
            }
        }
    }

    private fun alertDialog(configure: AlertDialog.Builder.() -> Unit) {
        try {
            activity.runOnUiThread {
                AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                        .apply(configure)
                        .show()
            }
        } catch (ex: IllegalStateException) {
            logger.warn("Cannot show dialog on closing activity")
        }
    }

    private fun requestLocationProvider() {
        alertDialog {
            setTitle(R.string.enable_location_title)
            setMessage(R.string.enable_location)
            setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.cancel()
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .startActivityForResult(LOCATION_REQUEST_CODE)
            }
            setIcon(android.R.drawable.ic_dialog_alert)
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private fun requestSystemWindowPermissions() {
        // Show alert dialog to the user saying a separate permission is needed
        // Launch the settings activity if the user prefers
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + activity.packageName)
        ).startActivityForResult(ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE)
    }

    private fun Intent.startActivityForResult(code: Int) {
        resolveActivity(activity.packageManager) ?: return
        try {
            activity.startActivityForResult(this, code)
        } catch (ex: ActivityNotFoundException) {
            logger.error("Failed to ask for usage code", ex)
        } catch (ex: IllegalStateException) {
            logger.warn("Cannot start activity on closed app")
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @SuppressLint("BatteryLife")
    private fun requestDisableBatteryOptimization() {
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + activity.packageName)
        ).startActivityForResult(BATTERY_OPT_CODE)
    }


    private fun requestPackageUsageStats() {
        alertDialog {
            setTitle(R.string.enable_package_usage_title)
            setMessage(R.string.enable_package_usage)
            setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.cancel()
                var intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                if (intent.resolveActivity(activity.packageManager) == null) {
                    intent = Intent(Settings.ACTION_SETTINGS)
                }
                intent.startActivityForResult(USAGE_REQUEST_CODE)
            }
            setIcon(android.R.drawable.ic_dialog_alert)
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        when (requestCode) {
            LOCATION_REQUEST_CODE -> onPermissionRequestResult(
                Context.LOCATION_SERVICE,
                resultCode == Activity.RESULT_OK
            )
            USAGE_REQUEST_CODE -> onPermissionRequestResult(
                RadarService.PACKAGE_USAGE_STATS_COMPAT,
                resultCode == Activity.RESULT_OK
            )
            BATTERY_OPT_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val powerManager =
                        activity.getSystemService(Context.POWER_SERVICE) as PowerManager?
                    val granted = resultCode == Activity.RESULT_OK
                            || powerManager?.isIgnoringBatteryOptimizations(activity.applicationContext.packageName) != false
                    onPermissionRequestResult(
                        RadarService.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT,
                        granted
                    )
                }
            }
            ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE -> onPermissionRequestResult(
                SYSTEM_ALERT_WINDOW,
                resultCode == Activity.RESULT_OK
            )
        }
    }

    fun invalidateCache() {
        mHandler.execute {
            if (isRequestingPermissions.isNotEmpty()) {
                val timeToExpire = isRequestingPermissionsTime + requestPermissionTimeoutMs - System.currentTimeMillis()
                if (timeToExpire <= 0L) {
                    resetRequestingPermission()
                } else {
                    mHandler.delay(timeToExpire, ::resetRequestingPermission)
                }
            }
        }
    }


    fun replaceNeededPermissions(newPermissions: Array<out String>?) {
        newPermissions ?: return
        mHandler.execute {
            needsPermissions.clear()

            needsPermissions += ArrayList<String>(newPermissions.size + 1).apply {
                this += newPermissions
                if (ACCESS_FINE_LOCATION in this || ACCESS_COARSE_LOCATION in this) {
                    this += LifecycleService.LOCATION_SERVICE
                }
            }.filterNot { activity.isPermissionGranted(it) }

            requestPermissions()
        }
    }


    fun permissionsGranted(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == REQUEST_ENABLE_PERMISSIONS) {
            broadcaster.send(RadarService.ACTION_PERMISSIONS_GRANTED) {
                putExtra(RadarService.EXTRA_PERMISSIONS, permissions)
                putExtra(RadarService.EXTRA_GRANT_RESULTS, grantResults)
            }
        }
    }

    fun saveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putStringArrayList(
            "isRequestingPermissions", ArrayList(
                isRequestingPermissions
            )
        )
        savedInstanceState.putLong("isRequestingPermissionsTime", isRequestingPermissionsTime)
    }

    fun restoreInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.getStringArrayList("isRequestingPermissions")
            ?.let { isRequestingPermissions += it }

        isRequestingPermissionsTime = savedInstanceState.getLong(
            "isRequestingPermissionsTime",
            java.lang.Long.MAX_VALUE
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PermissionHandler::class.java)

        private const val REQUEST_ENABLE_PERMISSIONS = 2
        // can only use lower 16 bits for request code
        private const val LOCATION_REQUEST_CODE = 232619694 and 0xFFFF
        private const val USAGE_REQUEST_CODE = 232619695 and 0xFFFF
        private const val BATTERY_OPT_CODE = 232619696 and 0xFFFF
        private const val ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 232619697 and 0xFFFF

        fun Context.isPermissionGranted(permission: String): Boolean = when (permission) {
            LifecycleService.LOCATION_SERVICE -> applySystemService<LocationManager>(Context.LOCATION_SERVICE) { locationManager ->
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } ?: true
            RadarService.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT -> applySystemService<PowerManager>(Context.POWER_SERVICE) { powerManager ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || powerManager.isIgnoringBatteryOptimizations(applicationContext.packageName)
            } ?: true
            RadarService.PACKAGE_USAGE_STATS_COMPAT -> applySystemService<AppOpsManager>(Context.APP_OPS_SERVICE) { appOps ->
                @Suppress("DEPRECATION")
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || AppOpsManager.MODE_ALLOWED == appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), packageName)
            } ?: true
            SYSTEM_ALERT_WINDOW -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
            else -> PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, permission)
        }
    }
}
