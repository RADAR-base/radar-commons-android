package org.radarbase.android.util

import android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.Manifest.permission.SYSTEM_ALERT_WINDOW
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LifecycleService

data class PermissionRequester(
    val permissions: Set<String>,
    val contract: ActivityResultContract<Set<String>, Set<String>> = DefaultPermissionRequestContract,
    val grantChecker: Context.(Set<String>) -> Set<String> = Context::defaultPermissionGrantChecker,
)

fun Context.defaultPermissionGrantChecker(permissions: Set<String>): Set<String> = permissions.filterTo(HashSet()) {
    PermissionChecker.checkSelfPermission(this, it) == PermissionChecker.PERMISSION_GRANTED
}

object DefaultPermissionRequestContract : ActivityResultContract<Set<String>, Set<String>>() {
    private val subcontract = ActivityResultContracts.RequestMultiplePermissions()
    override fun createIntent(context: Context, input: Set<String>): Intent {
        return subcontract.createIntent(context, input.toTypedArray())
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Set<String> {
        return subcontract.parseResult(resultCode, intent)
            .entries
            .mapNotNullTo(HashSet()) { (permission, granted) -> permission.takeIf { granted } }
    }
}

class StartActivityForPermission(val permission: String, val builder: Context.() -> Intent) : ActivityResultContract<Set<String>, Set<String>>() {
    override fun createIntent(context: Context, input: Set<String>): Intent = context.builder()

    override fun parseResult(resultCode: Int, intent: Intent?): Set<String> =
        if (resultCode == Activity.RESULT_OK) setOf(permission) else setOf()
}

object PermissionRequesters {
    @SuppressLint("BatteryLife")
    val ignoreBatteryOptimization = PermissionRequester(
        permissions = setOf(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
        contract = StartActivityForPermission(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
        },
        grantChecker = singleGrantChecker(REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            val powerManager = getSystemService(LifecycleService.POWER_SERVICE) as? PowerManager
                ?: return@singleGrantChecker false
            powerManager.isIgnoringBatteryOptimizations(packageName)
        }
    )

    val systemOverlay = PermissionRequester(
        setOf(SYSTEM_ALERT_WINDOW),
        StartActivityForPermission(SYSTEM_ALERT_WINDOW) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        },
        singleGrantChecker(SYSTEM_ALERT_WINDOW) {
            Settings.canDrawOverlays(this)
        }
    )

    private fun singleGrantChecker(
        permission: String,
        predicate: Context.() -> Boolean
    ): Context.(Set<String>) -> Set<String> = { permissions ->
        if (permission in permissions && predicate()) setOf(permission) else setOf()
    }
}
