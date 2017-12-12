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

import android.app.Application;
import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import org.radarcns.android.device.DeviceService;
import org.slf4j.impl.HandroidLoggerAdapter;

/** Provides the name and some metadata of the main activity */
public abstract class RadarApplication extends Application {
    public Notification.Builder updateNotificationAppSettings(Notification.Builder builder) {
        return builder
                .setWhen(System.currentTimeMillis())
                .setLargeIcon(getLargeIcon())
                .setSmallIcon(getSmallIcon());
    }

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
    }

    protected void setupLogging() {
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG;
        HandroidLoggerAdapter.APP_NAME = getPackageName();
    }

    /**
     * Create a RadarConfiguration object. At implementation, the Firebase version needs to be set
     * for this.
     *
     * @return configured RadarConfiguration
     */
    protected abstract RadarConfiguration createConfiguration();
}
