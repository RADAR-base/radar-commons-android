package org.radarcns.android;

import android.app.Service;
import android.content.*;
import android.os.IBinder;

public abstract class RadarService extends Service {
    public static String RADAR_PACKAGE = RadarService.class.getPackage().getName();

    public static String EXTRA_CREATOR_CLASS = RADAR_PACKAGE + ".EXTRA_CREATOR_CLASS";

    public static String ACTION_CHECK_PERMISSIONS = RADAR_PACKAGE + ".ACTION_CHECK_PERMISSIONS";
    public static String EXTRA_PERMISSIONS = RADAR_PACKAGE + ".EXTRA_PERMISSIONS";

    public static String ACTION_PERMISSIONS_GRANTED = RADAR_PACKAGE + ".ACTION_PERMISSIONS_GRANTED";
    public static String EXTRA_GRANT_RESULTS = RADAR_PACKAGE + ".EXTRA_GRANT_RESULTS";

    private final BroadcastReceiver configurationBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            configure();
        }
    };

    private final BroadcastReceiver permissionsBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onPermissionsGranted(intent.getStringArrayExtra(EXTRA_PERMISSIONS), intent.getIntArrayExtra(EXTRA_GRANT_RESULTS));
        }
    };

    private String creatorClass;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        registerReceiver(configurationBroadcastReceiver,
                new IntentFilter(RadarConfiguration.RADAR_CONFIGURATION_CHANGED));
        registerReceiver(permissionsBroadcastReceiver,
                new IntentFilter(ACTION_PERMISSIONS_GRANTED));

        configure();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        creatorClass = intent.getStringExtra(EXTRA_CREATOR_CLASS);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(configurationBroadcastReceiver);
        unregisterReceiver(permissionsBroadcastReceiver);

        super.onDestroy();
    }

    protected void configure() {
        RadarConfiguration configuration = RadarConfiguration.getInstance();
    }

    protected void requestPermissions(String[] permissions) {
        startActivity(new Intent()
                .setComponent(new ComponentName(this, creatorClass))
                .setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .setAction(ACTION_CHECK_PERMISSIONS)
                .putExtra(EXTRA_PERMISSIONS, permissions));
    }

    protected void onPermissionsGranted(String[] permissions, int[] grantResults) {
    }
}
