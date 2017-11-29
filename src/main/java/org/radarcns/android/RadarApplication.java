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
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.widget.Toast;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import org.radarcns.android.device.DeviceService;
import org.radarcns.android.util.Boast;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Provides the name and some metadata of the main activity */
public abstract class RadarApplication extends Application {
    private static final Logger logger = LoggerFactory.getLogger(RadarApplication.class);

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
        createConfiguration();
    }

    /**
     * Create a RadarConfiguration object. At implementation, the Firebase version needs to be set
     * for this.
     *
     * @return configured RadarConfiguration
     */
    protected abstract RadarConfiguration createConfiguration();
}
