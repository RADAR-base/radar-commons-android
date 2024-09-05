package org.radarbase.passive.polar

import android.content.Context
import android.os.Process
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.android.util.SafeHandler

/**
 * A service that manages the Polar manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
class PolarService : SourceService<PolarState>() {
    private lateinit var handler: SafeHandler

    override val defaultState: PolarState
        get() = PolarState()

    override fun onCreate() {
        super.onCreate()
        handler = SafeHandler.getInstance("Polar", Process.THREAD_PRIORITY_FOREGROUND)
    }

    override fun createSourceManager() = PolarManager(this)

    override fun configureSourceManager(
        manager: SourceManager<PolarState>,
        config: SingleRadarConfiguration
    ) {
        manager as PolarManager
    }

    fun savePolarDevice(deviceId: String) {
        applicationContext.getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SHARED_PREF_KEY, deviceId)
            .apply()
    }

    fun getPolarDevice(): String? {
        return applicationContext.getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
            .getString(SHARED_PREF_KEY, null)
    }

    companion object {
        val SHARED_PREF_NAME: String = PolarService::class.java.name
        const val SHARED_PREF_KEY = "polar_device_id"
    }
}

