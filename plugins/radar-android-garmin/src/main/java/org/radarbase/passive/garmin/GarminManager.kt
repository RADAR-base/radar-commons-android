package org.radarbase.passive.garmin

import android.util.Log
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.garmin.GarminGenericSteps


class GarminManager internal constructor(service: GarminService) : AbstractSourceManager<GarminService, GarminState>(service) {

    override fun start(acceptableIds: Set<String>) {
        status = SourceStatusListener.Status.READY
        register("Garmin Device", "Garmin device", mapOf())
        val timeStamp: Long = System.currentTimeMillis() + 1000;
        val timeStampNeeded: Double = timeStamp.toDouble();
        val steps: DataCache<ObservationKey, GarminGenericSteps> = createCache("android_garmin_generic_steps", GarminGenericSteps())
        send(steps, GarminGenericSteps(200, 1000, timeStampNeeded, timeStampNeeded))
        for(x in 0 until 1000) {
            val timeStamp1: Long = System.currentTimeMillis() + 1000;
            val timeStampNeeded1: Double = timeStamp1.toDouble();
            Log.d("Garmin manager:",""+timeStampNeeded1)
            val steps1: DataCache<ObservationKey, GarminGenericSteps> = createCache("android_garmin_generic_steps", GarminGenericSteps())
            send(steps1, GarminGenericSteps(200, 1000, timeStampNeeded1, timeStampNeeded1))
        }
    }
}