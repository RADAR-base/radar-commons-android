package org.radarbase.android.util

import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarService
import org.radarbase.android.config.SingleRadarConfiguration

class PeriodicPermissionObserver(private val context: RadarService) {
    private var count = 0
    private val isFirstAlarm: Boolean
        get() = count <= 1

    private val pObserverProcessor = OfflineProcessor(context) {
        process = listOf(this@PeriodicPermissionObserver::observePermissions)
        requestCode = PERIODIC_PERMISSION_OBSERVER_REQUEST_CODE
        requestName = PERIODIC_PERMISSION_OBSERVER_REQUEST_NAME
        wake = false
    }

    private var observerInterval: Long = -1L

    fun start() {
        pObserverProcessor.start()
        configurePermissionObserver(context.radarConfig.latestConfig)
    }

    private fun observePermissions() {
        count += 1
        if (isFirstAlarm) return

        context.nonGrantedPermissions().also { permissions ->
            if (permissions.isNotEmpty()) {
                Firebase.crashlytics.apply {
                    log("User have not granted some permissions")
                    setCustomKey("error_type", "permissions_not_granted")
                    setCustomKey("non_granted_permissions", permissions.joinToString(", ") { it })
                }
            }
        }
    }

    private fun configurePermissionObserver(config: SingleRadarConfiguration) {
        observerInterval = config.getLong(
            PERMISSION_OBSERVER_INTERVAL_SECONDS,
            PERMISSION_OBSERVER_INTERVAL_SECONDS_DEFAULT
        )
    }


    fun stop() {
        pObserverProcessor.close()
    }

    companion object {
        private const val PERIODIC_PERMISSION_OBSERVER_REQUEST_CODE = 683768762
        private const val PERIODIC_PERMISSION_OBSERVER_REQUEST_NAME =
            "org.radarbase.android.util.PeriodicPermissionObserver.PERIODIC_PERMISSION_OBSERVER_REQUEST_NAME"
        private const val PERMISSION_OBSERVER_INTERVAL_SECONDS =
            "org.radarbase.android.util.PeriodicPermissionObserver.PERMISSION_OBSERVER_INTERVAL_SECONDS"
        private const val PERMISSION_OBSERVER_INTERVAL_SECONDS_DEFAULT =
            2L * ((60 * 60) * 24) // 2-DAYS
    }
}