package org.radarbase.passive.polar

import android.os.Process
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.android.util.SafeHandler

/**
 * A service that manages the Polar manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
class PolarVantageV3Service : SourceService<PolarState>() {
    private lateinit var handler: SafeHandler
//    private lateinit var context: Context
    override val defaultState: PolarState
        get() = PolarState()

    override fun onCreate() {
        super.onCreate()
        handler = SafeHandler.getInstance("Polar", Process.THREAD_PRIORITY_FOREGROUND)
    }

    override fun createSourceManager() = PolarVantageV3Manager(this, applicationContext)

    override fun configureSourceManager(manager: SourceManager<PolarState>, config: SingleRadarConfiguration) {
        manager as PolarManager
    }
}

