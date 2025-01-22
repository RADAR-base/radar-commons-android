package org.radarbase.passive.polar

import android.content.Context
import android.content.SharedPreferences
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService

/**
 * A service that manages the Polar manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
class PolarService : SourceService<PolarState>() {

    override val defaultState: PolarState
        get() = PolarState()

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
    }

    override fun createSourceManager() = PolarManager(this)

    override fun configureSourceManager(
        manager: SourceManager<PolarState>,
        config: SingleRadarConfiguration
    ) {
        manager as PolarManager
    }


    fun savePolarDevice(deviceId: String) =
        sharedPreferences
            .edit()
            .putString(SHARED_PREF_KEY, deviceId)
            .apply()

    fun getPolarDevice(): String? =
        sharedPreferences
            .getString(SHARED_PREF_KEY, null)

    companion object {
        val SHARED_PREF_NAME: String = PolarService::class.java.name
        const val SHARED_PREF_KEY = "polar_device_id"
    }
}

