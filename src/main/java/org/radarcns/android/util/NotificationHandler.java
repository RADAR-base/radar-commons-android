package org.radarcns.android.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import org.radarcns.android.RadarApplication;

import static android.content.Context.NOTIFICATION_SERVICE;

/**
 * Created by joris on 06/03/2018.
 */

public class NotificationHandler {
    private final Context context;
    private final String channel;

    public NotificationHandler(Context context, String channelName, String channelDescription) {
        this.context = context;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel mChannel = new NotificationChannel(channelName, channelName, importance);
            this.channel = channelName;
            mChannel.setDescription(channelDescription);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            manager().createNotificationChannel(mChannel);
        } else {
            channel = null;
        }
    }

    private NotificationManager manager() {
        return (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
    }

    public Notification.Builder builder() {
        RadarApplication app = ((RadarApplication) context.getApplicationContext());
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channel);
        } else {
            builder = new Notification.Builder(context);
        }
        return app.updateNotificationAppSettings(builder);
    }

//    public static void createChannels(Context context) {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            // Create the NotificationChannel
//            int importance = NotificationManager.IMPORTANCE_DEFAULT;
//            NotificationChannel mChannel = new NotificationChannel(channelName, channelName, importance);
//            this.channel = channelName;
//            mChannel.setDescription(channelDescription);
//            // Register the channel with the system; you can't change the importance
//            // or other notification behaviors after this
//            manager().createNotificationChannel(mChannel);
//        } else {
//            channel = null;
//        }
//    }
}
