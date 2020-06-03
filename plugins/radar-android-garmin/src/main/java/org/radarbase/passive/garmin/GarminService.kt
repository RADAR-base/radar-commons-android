package org.radarbase.garmin

import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService

class GarminService : SourceService<GarminState>() {
    override val defaultState: GarminState
        get() = TODO("Not yet implemented")

    override fun createSourceManager(): SourceManager<GarminState> {
        TODO("Not yet implemented")
    }
}