package org.radarbase.passive.garmin

import org.radarbase.android.source.AbstractSourceManager

class GarminManager internal constructor(service: GarminService) : AbstractSourceManager<GarminService, GarminState>(service) {
    override fun start(acceptableIds: Set<String>) {
        TODO("Not yet implemented")
    }
}