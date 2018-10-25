/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.android;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;

import com.crashlytics.android.Crashlytics;

import org.radarcns.android.device.DeviceService;
import org.radarcns.android.util.NotificationHandler;
import org.slf4j.impl.HandroidLoggerAdapter;

import io.fabric.sdk.android.Fabric;

/** Provides the name and some metadata of the main activity */
public abstract class RadarApplication extends Application {
    private NotificationHandler notificationHandler;

    /** Large icon bitmap. */
    public abstract Bitmap getLargeIcon();
    /** Small icon drawable resource ID. */
    public abstract int getSmallIcon();

    public void configureProvider(RadarConfiguration config, Bundle bundle) {}
    public void onDeviceServiceInvocation(DeviceService service, Bundle bundle, boolean isNew) {}
    public void onDeviceServiceDestroy(DeviceService service) {}

    @Override
    @CallSuper
    public void onCreate() {
        super.onCreate();
        setupLogging();
        createConfiguration();
        notificationHandler = new NotificationHandler(this);
    }

    /** Get a notification handler. */
    public static NotificationHandler getNotificationHandler(Context context) {
        Context applicationContext = context.getApplicationContext();
        if (applicationContext instanceof RadarApplication) {
            return ((RadarApplication) applicationContext).notificationHandler;
        } else {
            return new NotificationHandler(applicationContext);
        }
    }

    protected void setupLogging() {
        // initialize crashlytics
        Fabric.with(this, new Crashlytics());
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG;
        HandroidLoggerAdapter.APP_NAME = getPackageName();
        HandroidLoggerAdapter.enableLoggingToCrashlytics();
    }

    /**
     * Create a RadarConfiguration object. At implementation, the Firebase version needs to be set
     * for this.
     *
     * @return configured RadarConfiguration
     */
    protected abstract RadarConfiguration createConfiguration();

    @NonNull
    public abstract Class<? extends Activity> getMainActivity();

    @NonNull
    public abstract Class<? extends Activity> getLoginActivity();

    @NonNull
    public Class<? extends Service> getRadarService() {
        return RadarService.class;
    }
}
