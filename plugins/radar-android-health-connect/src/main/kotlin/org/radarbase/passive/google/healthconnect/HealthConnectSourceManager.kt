package org.radarbase.passive.google.healthconnect

import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState

class HealthConnectSourceManager(service: HealthConnectService) :
    AbstractSourceManager<HealthConnectService, BaseSourceState>(service)
{

    override fun start(acceptableIds: Set<String>) {
        TODO("Not yet implemented")
    }

}
