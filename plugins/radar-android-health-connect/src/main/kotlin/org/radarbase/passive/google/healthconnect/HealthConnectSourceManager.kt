package org.radarbase.passive.google.healthconnect

import androidx.health.connect.client.records.Record
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import kotlin.reflect.KClass

class HealthConnectSourceManager(service: HealthConnectService) :
    AbstractSourceManager<HealthConnectService, BaseSourceState>(service)
{
    var dataTypes: List<KClass<out Record>> = listOf()

    override fun start(acceptableIds: Set<String>) {
        TODO("Not yet implemented")
    }
}
