package org.radarcns.android.util;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.RequiresApi;

import org.radarcns.android.R;
import org.radarcns.android.RadarApplication;

import static android.app.Notification.DEFAULT_SOUND;
import static android.app.Notification.DEFAULT_VIBRATE;
import static android.content.Context.NOTIFICATION_SERVICE;

/**
 * Handle notifications and notification channels.
 */
public class NotificationHandler {
    /** Notification channel ID for informational messages. No user response required. */
    public static final String NOTIFICATION_CHANNEL_INFO = "NotificationHandler.INFO";
    /**
     * Notification channel ID for tasks and active notification messages.
     * User response is requested.
     */
    public static final String NOTIFICATION_CHANNEL_NOTIFY = "NotificationHandler.NOTIFY";
    /**
     * Notification channel ID for missed tasks and app failure notification messages.
     * User response is strongly requested.
     */
    public static final String NOTIFICATION_CHANNEL_ALERT = "NotificationHandler.ALERT";
    /**
     * Notification channel ID for missed tasks and app failure notification messages.
     * User response is required.
     */
    public static final String NOTIFICATION_CHANNEL_FINAL_ALERT = "NotificationHandler.FINAL_ALERT";

    private final Context context;

    public NotificationHandler(Context context) {
        this.context = context;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels();
        }
    }

    private NotificationManager manager() {
        return (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        NotificationManager notificationManager = manager();

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
        String nameString = context.getString(name);
        String descriptionString = context.getString(description);
        NotificationChannel mChannel = new NotificationChannel(id, nameString, importance);
        mChannel.setDescription(descriptionString);
        return mChannel;
    }

    public Notification.Builder builder(String channel, boolean includeIntent) {
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder = new Notification.Builder(context, channel);
        } else {
            builder = new Notification.Builder(context);
            switch (channel) {
                case NOTIFICATION_CHANNEL_INFO:
                    builder.setPriority(Notification.PRIORITY_LOW);
                    break;
                case NOTIFICATION_CHANNEL_NOTIFY:
                    builder.setPriority(Notification.PRIORITY_DEFAULT);
                    break;
                case NOTIFICATION_CHANNEL_ALERT:
                    builder.setDefaults(DEFAULT_VIBRATE)
                            .setPriority(Notification.PRIORITY_HIGH);
                    break;
                case NOTIFICATION_CHANNEL_FINAL_ALERT:
                    builder.setDefaults(DEFAULT_SOUND | DEFAULT_VIBRATE)
                            .setPriority(Notification.PRIORITY_HIGH);
                    break;
                default:
                    // no further action
                    break;
            }
            if (channel.equals(NOTIFICATION_CHANNEL_ALERT)) {
                builder.setDefaults(DEFAULT_VIBRATE)
                        .setPriority(Notification.PRIORITY_HIGH);
            } else if (channel.equals(NOTIFICATION_CHANNEL_FINAL_ALERT)) {
                builder.setDefaults(DEFAULT_SOUND | DEFAULT_VIBRATE)
                        .setPriority(Notification.PRIORITY_HIGH);
            }
        }
        return updateNotificationAppSettings(builder, includeIntent);
    }

    public Notification.Builder updateNotificationAppSettings(Notification.Builder builder, boolean includeIntent) {
        Context applicationContext = context.getApplicationContext();
        if (applicationContext instanceof RadarApplication) {
            RadarApplication app = (RadarApplication) applicationContext;
            builder.setLargeIcon(app.getLargeIcon())
                    .setSmallIcon(app.getSmallIcon());
        }

        if (includeIntent) {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
            builder.setContentIntent(pendingIntent);
        }

        return builder.setWhen(System.currentTimeMillis());
    }
}
