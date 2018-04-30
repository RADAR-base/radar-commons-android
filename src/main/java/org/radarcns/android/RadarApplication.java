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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.RequiresApi;

import org.radarcns.android.device.DeviceService;
import org.slf4j.impl.HandroidLoggerAdapter;

/** Provides the name and some metadata of the main activity */
public abstract class RadarApplication extends Application {
    public static final String NOTIFICATION_CHANNEL_INFO = "RadarApplication.INFO";
    public static final String NOTIFICATION_CHANNEL_NOTIFY = "RadarApplication.NOTIFY";
    public static final String NOTIFICATION_CHANNEL_ALERT = "RadarApplication.ALERT";
    public static final String NOTIFICATION_CHANNEL_FINAL_ALERT = "RadarApplication.FINAL_ALERT";

    public Notification.Builder getAppNotificationBuilder(String channel) {
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channel);
        } else {
            builder = new Notification.Builder(this);
        }
        return updateNotificationAppSettings(builder);
    }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(
                NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            return;
        }

        notificationManager.createNotificationChannel(newNotificationChannel(
                NOTIFICATION_CHANNEL_INFO, NotificationManager.IMPORTANCE_LOW,
                R.string.channel_info_name, R.string.channel_info_description));

        notificationManager.createNotificationChannel(newNotificationChannel(
                NOTIFICATION_CHANNEL_NOTIFY, NotificationManager.IMPORTANCE_DEFAULT,
                R.string.channel_notify_name, R.string.channel_notify_description));

        notificationManager.createNotificationChannel(newNotificationChannel(
                NOTIFICATION_CHANNEL_ALERT, NotificationManager.IMPORTANCE_HIGH,
                R.string.channel_alert_name, R.string.channel_alert_description));

        NotificationChannel importantChannel = newNotificationChannel(
                NOTIFICATION_CHANNEL_FINAL_ALERT, NotificationManager.IMPORTANCE_HIGH,
                R.string.channel_final_alert_name, R.string.channel_final_alert_description);

        Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        importantChannel.setSound(alarmSound, new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build());

        notificationManager.createNotificationChannel(importantChannel);
    }

    /**
     * Creates a notification channel that is not yet added to the notification manager.
     * @param id channel ID
     * @param importance NotificationManager importance constant
     * @param name string resource ID of the human readable name
     * @param description string resource ID of the human readable description.
     * @return not yet added notification channel
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    public NotificationChannel newNotificationChannel(String id, int importance, int name, int description) {
        String nameString = getString(name);
        String descriptionString = getString(description);
        NotificationChannel mChannel = new NotificationChannel(id, nameString, importance);
        mChannel.setDescription(descriptionString);
        return mChannel;
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
