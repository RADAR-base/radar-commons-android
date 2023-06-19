package org.radarbase.passive.google.healthconnect

import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.passive.google.healthconnect.HealthConnectProvider.Companion.toHealthConnectTypes

class HealthConnectService : SourceService<BaseSourceState>() {
    override val defaultState: BaseSourceState
        get() = BaseSourceState()

    override fun createSourceManager(): SourceManager<BaseSourceState> =
        HealthConnectSourceManager(this)

    override fun configureSourceManager(
        manager: SourceManager<BaseSourceState>,
        config: SingleRadarConfiguration
    ) {
        manager as HealthConnectSourceManager
        manager.dataTypes = config.getString(HealthConnectProvider.HEALTH_CONNECT_DATA_TYPES, "")
            .toHealthConnectTypes()
    }
}
