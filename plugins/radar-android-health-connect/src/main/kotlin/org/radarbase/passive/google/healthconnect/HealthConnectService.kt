package org.radarbase.passive.google.healthconnect

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.android.source.UnavailableSourceManager
import kotlin.time.Duration.Companion.seconds

class HealthConnectService : SourceService<BaseSourceState>() {
    override val defaultState: BaseSourceState
        get() = BaseSourceState()

    override fun createSourceManager(): SourceManager<BaseSourceState> {
        // TODO: Available from version 1.1.0-alpha01
        // HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            HealthConnectClient.sdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
        ) {
            HealthConnectManager(this)
        } else {
            UnavailableSourceManager("Health Connect", state)
        }
    }

    override fun configureSourceManager(
        manager: SourceManager<BaseSourceState>,
        config: SingleRadarConfiguration
    ) {
        if (manager !is HealthConnectManager) return
        config.optString(HEALTH_CONNECT_DATA_TYPES) {
            manager.dataTypes = it.toHealthConnectTypes()
                .toHashSet()
        }
        config.optDouble(HEALTH_CONNECT_INTERVAL_SECONDS) {
            manager.interval = it.seconds
        }
    }

    companion object {
        const val HEALTH_CONNECT_DATA_TYPES = "health_connect_data_types"
        const val HEALTH_CONNECT_INTERVAL_SECONDS = "health_connect_interval_seconds"
    }
}
