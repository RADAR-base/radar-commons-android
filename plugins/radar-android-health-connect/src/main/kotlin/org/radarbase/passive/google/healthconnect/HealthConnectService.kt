package org.radarbase.passive.google.healthconnect

import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService

class HealthConnectService : SourceService<BaseSourceState>() {
    override val defaultState: BaseSourceState
        get() = BaseSourceState()

    override fun createSourceManager(): SourceManager<BaseSourceState> =
        HealthConnectSourceManager(this)
}
