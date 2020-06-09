package org.radarbase.passive.garmin
import org.radarbase.android.source.SourceService

class GarminService : SourceService<GarminState>() {
    override val defaultState: GarminState = GarminState()
    override fun createSourceManager() = GarminManager(this)
}