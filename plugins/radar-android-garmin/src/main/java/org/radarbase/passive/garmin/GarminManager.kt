package org.radarbase.passive.garmin

import android.util.Log
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.SafeHandler
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.garmin.GarminGenericSteps


class GarminManager(service: GarminService, private val handler: SafeHandler) : AbstractSourceManager<GarminService, GarminState>(service) {
    private val stepsTopic: DataCache<ObservationKey, GarminGenericSteps> = createCache("android_garmin_generic_steps", GarminGenericSteps())

    override fun start(acceptableIds: Set<String>) {
        handler.start()
        handler.execute {
            if (register("Garmin Device 2", "Garmin device 2", mapOf())) {
                Log.w(TAG, "Source Registered")
                // Start scanning devices after setting status READY
                status = SourceStatusListener.Status.READY

                // Set status to CONNECTED once the device is connected
                status = SourceStatusListener.Status.CONNECTED
            }
        }

        // keep sending data at 1 second intervals. This simulates data coming in from the device.
        handler.repeat(1000) {
            val timeStamp1: Long = System.currentTimeMillis()
            val timeStampNeeded1: Double = timeStamp1.toDouble()
            Log.i(TAG, "time = $timeStampNeeded1")
            send(stepsTopic, GarminGenericSteps(200, 1000, timeStampNeeded1, timeStampNeeded1))
        }
    }


    override fun didRegister(source: SourceMetadata) {
        super.didRegister(source)
        Log.i(TAG, "Source Registered $source")
        Log.i(TAG, "Name: $name, SourceId: ${state.id.getSourceId()}")
    }

    companion object {
        private const val TAG = "Garmin Manager"
    }
}