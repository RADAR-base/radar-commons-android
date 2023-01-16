package org.radarbase.android.util

import android.Manifest.permission.*
import android.app.Activity.LOCATION_SERVICE
import android.app.Activity.RESULT_OK
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings.*
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.radarbase.android.R
import org.radarbase.android.util.PermissionHandler.PermissionResult.Companion.toPermissionResult
import org.slf4j.LoggerFactory
import java.lang.System
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


open class PermissionHandler(
    private val activity: AppCompatActivity,
    private val registry : ActivityResultRegistry,
    private val requestPermissionTimeout: Duration,
): DefaultLifecycleObserver {
    private val needsPermissions = MutableStateFlow<Set<PermissionRequest>>(emptySet())
    private val isRequestingPermissions = MutableStateFlow<Set<PermissionRequest>>(emptySet())

    private val _permissionsGranted = MutableSharedFlow<PermissionGrantResult>(128)
    val permissionsGranted: SharedFlow<PermissionGrantResult> = _permissionsGranted
    private val requestPermissionLauncher = registry.register("request-permissions", ActivityResultContracts.RequestMultiplePermissions()) { results ->
        results.forEach { (permission, granted) ->
            _permissionsGranted.tryEmit(PermissionGrantResult(permission, granted.toPermissionResult()))
        }
    }
    private val customLaunchers = sequence {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            yield(StartSettingsActivityForResult(SYSTEM_ALERT_WINDOW, ACTION_MANAGE_OVERLAY_PERMISSION))
            yield(StartSettingsActivityForResult(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT, ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
        }
        yield(StartSettingsActivityForResult(PACKAGE_USAGE_STATS_COMPAT, ACTION_USAGE_ACCESS_SETTINGS, ACTION_SETTINGS))
        yield(StartSettingsActivityForResult(Context.LOCATION_SERVICE, ACTION_LOCATION_SOURCE_SETTINGS))
    }.associate { resultContract ->
        resultContract.key to registry.register(resultContract.key, resultContract) { _permissionsGranted.tryEmit(it) }
    }

    private class StartSettingsActivityForResult(
        val key: String,
        vararg val activityNames: String,
    ) : ActivityResultContract<Unit, PermissionGrantResult>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            requireNotNull(internalCreateIntent(context))

        private fun internalCreateIntent(context: Context): Intent? = activityNames
            .asSequence()
            .map { Intent(it, Uri.parse("package:${context.packageName}")) }
            .firstOrNull { it.resolveActivity(context.packageManager) != null }

        override fun parseResult(
            resultCode: Int,
            intent: Intent?,
        ): PermissionGrantResult = PermissionGrantResult(
            key,
            if (resultCode == RESULT_OK) PermissionResult.GRANTED else PermissionResult.DENIED,
        )

        override fun getSynchronousResult(
            context: Context,
            input: Unit
        ): SynchronousResult<PermissionGrantResult>? = if (
            context.isPermissionGranted(key) ||
            internalCreateIntent(context) == null
        ) {
            SynchronousResult(PermissionGrantResult(key, PermissionResult.GRANTED))
        } else null
    }

    @OptIn(FlowPreview::class)
    suspend fun monitor() {
        coroutineScope {
            launch {
                permissionsGranted
                    .collectLatest { (permission, status) ->
                        if (status == PermissionResult.GRANTED || activity.isPermissionGranted(permission.permission)) {
                            needsPermissions.value = needsPermissions.value - permission
                        }
                    }
            }

            launch {
                needsPermissions
                    .debounce(5.seconds)
                    .combine(isRequestingPermissions) { needed, requesting ->
                        buildSet {
                            needed.forEach { permission ->
                                if (permission !in requesting && !activity.isPermissionGranted(permission.permission)) {
                                    add(permission.permission)
                                }
                            }
                            if (ACCESS_COARSE_LOCATION in this || ACCESS_FINE_LOCATION in this) {
                                remove(ACCESS_BACKGROUND_LOCATION_COMPAT)
                            }
                        }
                    }
                    .collectLatest { requestPermissions(it) }
            }

            launch {
                isRequestingPermissions
                    .debounce(5.seconds)
                    .onEach { delay(requestPermissionTimeout) }
                    .combine(needsPermissions) { requesting, needed ->
                        requesting.filterTo(HashSet()) { it !in needed || it.isExpired(requestPermissionTimeout) }
                    }
                    .collect {
                        isRequestingPermissions.value = isRequestingPermissions.value - it
                    }
            }
        }
    }

    private fun onPermissionRequestResult(permission: String, granted: Boolean) {
        _permissionsGranted.tryEmit(PermissionGrantResult(permission, granted.toPermissionResult()))
    }

    enum class PermissionResult {
        GRANTED, DENIED;

        companion object {
            fun Boolean.toPermissionResult(): PermissionResult = if (this) GRANTED else DENIED
            fun Int.toPermissionResult(): PermissionResult = if (this == PERMISSION_GRANTED) GRANTED else DENIED
        }
    }

    private fun requestPermissions(permissions: Set<String>) {
        when {
            permissions.isEmpty() -> logger.info("Already requested all permissions.")
            LOCATION_SERVICE in permissions -> invokeLauncher(LOCATION_SERVICE) { invoke, cancel ->
                alertDialog {
                    setTitle(R.string.enable_location_title)
                    setMessage(R.string.enable_location)
                    setPositiveButton(android.R.string.ok) { dialog, _ ->
                        dialog.cancel()
                        invoke()
                    }
                    setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        dialog.cancel()
                        cancel()
                    }
                    setIcon(android.R.drawable.ic_dialog_alert)
                }
            }
            SYSTEM_ALERT_WINDOW in permissions -> invokeLauncher(SYSTEM_ALERT_WINDOW)
            PACKAGE_USAGE_STATS_COMPAT in permissions -> invokeLauncher(PACKAGE_USAGE_STATS_COMPAT) { invoke, cancel ->
                alertDialog {
                    setTitle(R.string.enable_package_usage_title)
                    setMessage(R.string.enable_package_usage)
                    setPositiveButton(android.R.string.ok) { dialog, _ ->
                        dialog.cancel()
                        invoke()
                    }
                    setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        dialog.cancel()
                        cancel()
                    }
                    setIcon(android.R.drawable.ic_dialog_alert)
                }
            }
            REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT in permissions -> invokeLauncher(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT)
            else -> {
                addRequestingPermissions(permissions)
                try {
                    requestPermissionLauncher.launch(permissions.toTypedArray())
                } catch (ex: IllegalStateException) {
                    logger.warn("Cannot request permission on closing activity")
                }
            }
        }
    }

    private fun invokeLauncher(permission: String, invoke: (doInvoke: () -> Unit, doCancel: () -> Unit) -> Unit = { doInvoke, _ -> doInvoke() }) {
        addRequestingPermissions(permission)
        val launcher = customLaunchers[permission]
        val doCancel = { _permissionsGranted.tryEmit(PermissionGrantResult(permission, PermissionResult.GRANTED)) }
        if (launcher == null) {
            doCancel()
        } else {
            invoke({ launcher.launch(Unit) }, doCancel)
        }
    }

    private fun resetRequestingPermission() {
        isRequestingPermissions.value = emptySet()
    }

    private fun addRequestingPermissions(permission: String) {
        addRequestingPermissions(setOf(permission))
    }

    private fun addRequestingPermissions(permissions: Set<String>) {
        isRequestingPermissions.value = buildSet {
            addAll(isRequestingPermissions.value)
            permissions.forEach { permission ->
                add(PermissionRequest(permission))
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

    fun replaceNeededPermissions(newPermissions: Array<out String>?) {
        newPermissions ?: return
        needsPermissions.tryEmit(
            newPermissions
                .asSequence()
                .flatMap {
                    if (activity.isPermissionGranted(it)) {
                        emptySequence()
                    } else {
                        sequence {
                            if (it == ACCESS_FINE_LOCATION || it == ACCESS_COARSE_LOCATION) {
                                yield(LOCATION_SERVICE)
                            }
                            yield(it)
                        }
                    }
                }
                .map { PermissionRequest(it) }
                .toHashSet()
        )
    }


    fun saveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putStringArrayList(
            "isRequestingPermissions", isRequestingPermissions.value.mapTo(ArrayList()) { it.permission }
        )
        savedInstanceState.putLong("isRequestingPermissionsTime", System.currentTimeMillis() - SystemClock.elapsedRealtime() + isRequestingPermissions.value.maxOf { it.time })
    }

    fun restoreInstanceState(savedInstanceState: Bundle) {
        val now = System.currentTimeMillis()
        val time = savedInstanceState.getLong("isRequestingPermissionsTime", now) - now + SystemClock.elapsedRealtime()
        val savedPermissions = savedInstanceState.getStringArrayList("isRequestingPermissions") ?: emptyList()
        isRequestingPermissions.value = isRequestingPermissions.value + savedPermissions.map { PermissionRequest(it, time) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PermissionHandler::class.java)

        fun Context.isPermissionGranted(permission: String): Boolean = when (permission) {
            LOCATION_SERVICE -> applySystemService<LocationManager>(LOCATION_SERVICE) { locationManager ->
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } ?: true
            REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT -> applySystemService<PowerManager>(Context.POWER_SERVICE) { powerManager ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || powerManager.isIgnoringBatteryOptimizations(applicationContext.packageName)
            } ?: true
            PACKAGE_USAGE_STATS_COMPAT -> applySystemService<AppOpsManager>(Context.APP_OPS_SERVICE) { appOps ->
                @Suppress("DEPRECATION")
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || AppOpsManager.MODE_ALLOWED == appOps.checkOpNoThrow("android:get_usage_stats", Process.myUid(), packageName)
            } ?: true
            SYSTEM_ALERT_WINDOW -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M || canDrawOverlays(this)
            else -> PERMISSION_GRANTED == ContextCompat.checkSelfPermission(this, permission)
        }

        val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            REQUEST_IGNORE_BATTERY_OPTIMIZATIONS else "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        val PACKAGE_USAGE_STATS_COMPAT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PACKAGE_USAGE_STATS else "android.permission.PACKAGE_USAGE_STATS"
        val ACCESS_BACKGROUND_LOCATION_COMPAT = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ACCESS_BACKGROUND_LOCATION else "android.permission.ACCESS_BACKGROUND_LOCATION"

        data class PermissionGrantResult(
            val permission: PermissionRequest,
            val status: PermissionResult,
        ) {
            constructor(permission: String, status: PermissionResult) : this(PermissionRequest(permission), status)
        }

        class PermissionRequest(
            val permission: String,
            val time: Long = SystemClock.elapsedRealtime()
        ) {
            fun isExpired(duration: Duration): Boolean = SystemClock.elapsedRealtime() >= time + duration.inWholeMilliseconds

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as PermissionRequest
                return permission == other.permission
            }

            override fun hashCode(): Int = permission.hashCode()
        }
    }
}
