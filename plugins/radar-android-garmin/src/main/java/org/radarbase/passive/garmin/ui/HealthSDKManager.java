package org.radarbase.passive.garmin.ui;

import android.content.Context;

import com.garmin.health.AbstractGarminHealth;
import com.garmin.health.GarminHealth;
import com.garmin.health.GarminHealthInitializationException;
import com.google.common.util.concurrent.ListenableFuture;

import org.radarbase.passive.garmin.R;

/**
 * Manages Health SDK initialization
 * Created by morajkar on 2/2/2018.
 */

public class HealthSDKManager {
    /**
     * Initializes the health SDK for streaming
     * License should be acquired by contacting Garmin. Each license has restriction on the type of data that can be accessed.
     * @param context
     * @throws GarminHealthInitializationException
     */
    public static ListenableFuture<Boolean> initializeHealthSDK(Context context) throws GarminHealthInitializationException
    {
        if(!GarminHealth.isInitialized())
        {
            GarminHealth.setLoggingLevel(AbstractGarminHealth.LoggingLevel.VERBOSE);
        }

        return GarminHealth.initialize(context, context.getString(R.string.companion_license));
    }
}
