package org.radarbase.passive.polarh10

import android.os.Process
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.android.util.SafeHandler

/**
 * A service that manages the Polar manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
class PolarH10Service : SourceService<PolarH10State>() {
    private lateinit var handler: SafeHandler
//    private lateinit var context: Context
    override val defaultState: PolarH10State
        get() = PolarH10State()

    override fun onCreate() {
        super.onCreate()
        handler = SafeHandler.getInstance("Polar", Process.THREAD_PRIORITY_FOREGROUND)
    }

    override fun createSourceManager() = PolarH10Manager(this, applicationContext)

    override fun configureSourceManager(manager: SourceManager<PolarH10State>, config: SingleRadarConfiguration) {
        manager as PolarH10Manager
    }
}

