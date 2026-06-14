package org.radarbase.passive.polar

import android.content.Context
import android.content.SharedPreferences
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import androidx.core.content.edit

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
        sharedPreferences = getSharedPreferences(POLAR_SHARED_PREF_NAME, Context.MODE_PRIVATE)
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
            .edit {
                putString(POLAR_SHARED_PREF_KEY, deviceId)
            }

    fun getPolarDevice(): String? =
        sharedPreferences
            .getString(POLAR_SHARED_PREF_KEY, null)

    /** Persist whether the user has started data collection, so it survives reconnects and restarts. */
    fun setCollectionStarted(started: Boolean) =
        sharedPreferences
            .edit {
                putBoolean(POLAR_COLLECTION_STARTED_KEY, started)
            }

    fun isCollectionStarted(): Boolean =
        sharedPreferences
            .getBoolean(POLAR_COLLECTION_STARTED_KEY, false)

    /**
     * Persist whether the user has asked to connect the device. Once set, the manager auto-connects
     * on every start, so the connection continues across reconnects and app restarts until cleared.
     */
    fun setConnectionRequested(requested: Boolean) =
        sharedPreferences
            .edit {
                putBoolean(POLAR_CONNECTION_REQUESTED_KEY, requested)
            }

    fun isConnectionRequested(): Boolean =
        sharedPreferences
            .getBoolean(POLAR_CONNECTION_REQUESTED_KEY, false)

    companion object {
        val POLAR_SHARED_PREF_NAME: String = PolarService::class.java.name
        const val POLAR_SHARED_PREF_KEY = "polar_device_id"
        const val POLAR_COLLECTION_STARTED_KEY = "polar_collection_started"
        const val POLAR_CONNECTION_REQUESTED_KEY = "polar_connection_requested"
    }
}

