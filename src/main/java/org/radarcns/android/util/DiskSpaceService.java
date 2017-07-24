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

package org.radarcns.android.util;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.StatFs;
import android.support.annotation.Nullable;

import org.radarcns.android.RadarApplication;
import org.radarcns.android.RadarConfiguration;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.radarcns.android.RadarConfiguration.DISK_SPACE_CHECK_RENOTIFY;
import static org.radarcns.android.RadarConfiguration.DISK_SPACE_CHECK_TIMEOUT;
import static org.radarcns.android.RadarConfiguration.MIN_DISK_SPACE;

/**
 * Service to check the available disk space periodically.
 */
public final class DiskSpaceService extends Service {
    public static final int DISK_SPACE_SERVICE_NOTIFICATION = 926988;

    private final AtomicInteger notificationCounter = new AtomicInteger(0);
    private Handler handler;
    private long lastNotification;

    public static long getAvailableSpace() {
        StatFs statfs = new StatFs(Environment.getRootDirectory().getAbsolutePath());
        return statfs.getAvailableBlocksLong() * statfs.getBlockSizeLong();
    }

    @Override
    public void onCreate() {
        handler = new Handler();
        lastNotification = 0L;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        final int currentCount = notificationCounter.getAndIncrement();

        Bundle extras = intent.getExtras();
        final long minSpace = RadarConfiguration.getLongExtra(extras, MIN_DISK_SPACE);
        final long timeout = TimeUnit.MINUTES.toMillis(
                RadarConfiguration.getLongExtra(extras, DISK_SPACE_CHECK_TIMEOUT));
        final long renotify = RadarConfiguration.getLongExtra(extras, DISK_SPACE_CHECK_RENOTIFY);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // a new notification was added
                if (currentCount != notificationCounter.get()) {
                    return;
                }

                if (System.currentTimeMillis() - lastNotification > renotify
                        && getAvailableSpace() < minSpace) {
                    notifyFull();
                }
                handler.postDelayed(this, timeout);
            }
        }, timeout);

        return null;
    }

    private void notifyFull() {
        Notification.Builder builder = new Notification.Builder(getApplicationContext());
        builder.setContentTitle("Storage almost full");
        builder.setContentText("Your storage space is almost full. Please ensure that you have " +
                "an internet connection to upload the data and/or move unnecessary photos and " +
                "multimedia from your device.");

        ((RadarApplication)getApplication()).updateNotificationAppSettings(builder);

        startForeground(DISK_SPACE_SERVICE_NOTIFICATION, builder.build());
        lastNotification = System.currentTimeMillis();
    }
}
