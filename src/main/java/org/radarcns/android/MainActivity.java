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
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.os.Process;
import android.provider.Settings;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static android.Manifest.permission.PACKAGE_USAGE_STATS;
import static org.radarcns.android.auth.LoginActivity.ACTION_LOGIN;

/** Base MainActivity class. It manages the services to collect the data and starts up a view. To
 * create an application, extend this class and override the abstract methods. */
@SuppressWarnings({"unused", "WeakerAccess"})
public abstract class MainActivity extends Activity {
    private static final Logger logger = LoggerFactory.getLogger(MainActivity.class);

    private static final int REQUEST_ENABLE_PERMISSIONS = 2;
    private static final int LOGIN_REQUEST_CODE = 232619693;
    private static final int LOCATION_REQUEST_CODE = 232619694;
    private static final int USAGE_REQUEST_CODE = 232619695;

    private BroadcastReceiver configurationBroadcastReceiver;

    /** Time between refreshes. */
    private long uiRefreshRate;

    /**
     * Background handler thread, to do the service orchestration. Having this in the background
     * is important to avoid any lags in the UI. It is shutdown whenever the activity is not
     * running.
     */
    private HandlerThread mHandlerThread;
    /** Hander in the background. It is set to null whenever the activity is not running. */
    private Handler mHandler;

    /** The UI to show the service data. */
    private Runnable mViewUpdater;
    private MainActivityView mView;

    private AppAuthState authState;

    private Set<String> needsPermissions = Collections.emptySet();
    private boolean requestedBt;

    private IRadarService radarService;

    private final ServiceConnection radarServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            radarService = (IRadarService) service;
            mView = createView();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            radarService = null;
        }
    };

    protected abstract Class<? extends LoginActivity> loginActivity();

    @Override
    protected final void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        authState = AppAuthState.Builder.from(this).build();
        if (!authState.isValid()) {
            startLogin(false);
            return;
        }

        create();
    }

    @CallSuper
    protected void create() {
        RadarConfiguration radarConfiguration = RadarConfiguration.getInstance();

        if (authState.getProjectId() != null) {
            radarConfiguration.put(RadarConfiguration.PROJECT_ID_KEY, authState.getProjectId());
        }
        radarConfiguration.put(RadarConfiguration.USER_ID_KEY, authState.getUserId());
        logger.info("RADAR configuration at create: {}", radarConfiguration);
        onConfigChanged();

        configurationBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onConfigChanged();
            }
        };
        registerReceiver(configurationBroadcastReceiver,
                new IntentFilter(RadarConfiguration.RADAR_CONFIGURATION_CHANGED));

        startService(new Intent(this, radarService())
                .putExtra(RadarService.EXTRA_MAIN_ACTIVITY, getClass().getName())
                .putExtra(RadarService.EXTRA_LOGIN_ACTIVITY, loginActivity().getName()));


        // Start the UI thread
        uiRefreshRate = radarConfiguration.getLong(RadarConfiguration.UI_REFRESH_RATE_KEY);
        mViewUpdater = new Runnable() {
            @Override
            public void run() {
                try {
                    // Update all rows in the UI with the data from the connections
                    MainActivityView localView = mView;
                    if (localView != null) {
                        localView.update();
                    }
                } finally {
                    Handler handler = getHandler();
                    if (handler != null) {
                        handler.postDelayed(mViewUpdater, uiRefreshRate);
                    }
                }
            }
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (configurationBroadcastReceiver != null) {
            unregisterReceiver(configurationBroadcastReceiver);
        }
    }

    protected Class<? extends RadarService> radarService() {
        return RadarService.class;
    }

    /**
     * Called whenever the RadarConfiguration is changed. This can be at activity start or
     * when the configuration is updated from Firebase.
     */
    @CallSuper
    protected void onConfigChanged() {

    }

    /** Create a view to show the data of this activity. */
    protected abstract MainActivityView createView();

    @Override
    protected void onResume() {
        logger.info("mainActivity onResume");
        super.onResume();

        bindService(new Intent().setComponent(new ComponentName(this, radarService())), radarServiceConnection, 0);
        getHandler().post(mViewUpdater);
    }

    @Override
    protected void onPause() {
        logger.info("mainActivity onPause");
        getHandler().removeCallbacks(mViewUpdater);
        unbindService(radarServiceConnection);
        super.onPause();
    }

    @Override
    protected void onStart() {
        logger.info("mainActivity onStart");
        super.onStart();
        if (!authState.isValid()) {
            startLogin(true);
        }

        mHandlerThread = new HandlerThread("Service connection", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        Handler localHandler = new Handler(mHandlerThread.getLooper());
        synchronized (this) {
            mHandler = localHandler;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (RadarService.ACTION_CHECK_PERMISSIONS.equals(intent.getAction())) {
            String[] permissions = intent.getStringArrayExtra(RadarService.EXTRA_PERMISSIONS);
            needsPermissions = new HashSet<>(Arrays.asList(permissions));
            checkPermissions();
        }

        super.onNewIntent(intent);
    }

    @Override
    protected void onStop() {
        logger.info("mainActivity onStop");
        super.onStop();

        synchronized (this) {
            mHandler = null;
        }
        mHandlerThread.quitSafely();
        mView = null;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent result) {
        switch (requestCode) {
            case LOGIN_REQUEST_CODE: {
                if (resultCode != RESULT_OK) {
                    throw new IllegalStateException("Login should not be cancellable");
                }
                authState = AppAuthState.Builder.from(result.getExtras()).build();
                RadarConfiguration.getInstance().put(RadarConfiguration.PROJECT_ID_KEY, authState.getProjectId());
                RadarConfiguration.getInstance().put(RadarConfiguration.USER_ID_KEY, authState.getUserId());
                onConfigChanged();
                break;
            }
            case LOCATION_REQUEST_CODE: {
                onPermissionRequestResult(LOCATION_SERVICE, resultCode == RESULT_OK);
                break;
            }
            case USAGE_REQUEST_CODE: {
                onPermissionRequestResult(PACKAGE_USAGE_STATS, resultCode == RESULT_OK);
                break;
            }
        }
    }

    private void onPermissionRequestResult(String permission, boolean granted) {
        needsPermissions.remove(permission);

        int result = granted ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent()
                        .setAction(RadarService.ACTION_PERMISSIONS_GRANTED)
                        .putExtra(RadarService.EXTRA_PERMISSIONS, new String[]{LOCATION_SERVICE})
                        .putExtra(RadarService.EXTRA_GRANT_RESULTS, new int[]{result}));

        checkPermissions();
    }

    /** Get background handler. */
    private synchronized Handler getHandler() {
        return mHandler;
    }


    protected void checkPermissions() {
        if (needsPermissions.contains(LOCATION_SERVICE)) {
            requestLocationProvider();
        } else if (needsPermissions.contains(PACKAGE_USAGE_STATS)) {
            requestPackageUsageStats();
        } else if (!needsPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needsPermissions.toArray(new String[needsPermissions.size()]),
                    REQUEST_ENABLE_PERMISSIONS);
        } else {
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent()
                            .setAction(RadarService.ACTION_PERMISSIONS_GRANTED)
                            .putExtra(RadarService.EXTRA_PERMISSIONS, new String[0])
                            .putExtra(RadarService.EXTRA_GRANT_RESULTS, new int[0]));
        }
    }

    private void requestLocationProvider() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle(R.string.enable_location_title)
                .setMessage(R.string.enable_location)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        if (intent.resolveActivity(getPackageManager()) == null) {
                            intent = new Intent(Settings.ACTION_SETTINGS);
                        }
                        startActivityForResult(intent, LOCATION_REQUEST_CODE);
                        dialog.cancel();
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void requestPackageUsageStats() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle(R.string.enable_package_usage_title)
                .setMessage(R.string.enable_package_usage)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        if (intent.resolveActivity(getPackageManager()) == null) {
                            intent = new Intent(Settings.ACTION_SETTINGS);
                        }
                        startActivityForResult(intent, USAGE_REQUEST_CODE);
                        dialog.cancel();
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, @NonNull final String[] permissions, @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_ENABLE_PERMISSIONS) {
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent()
                            .setAction(RadarService.ACTION_PERMISSIONS_GRANTED)
                            .putExtra(RadarService.EXTRA_PERMISSIONS, permissions)
                            .putExtra(RadarService.EXTRA_GRANT_RESULTS, grantResults));
        }
    }

    protected boolean hasPermission(String permissionName) {
        return !needsPermissions.contains(permissionName);
    }


    protected void startLogin(boolean forResult) {
        Intent intent = new Intent(this, loginActivity());

        if (forResult) {
            intent.setAction(ACTION_LOGIN);
            startActivityForResult(intent, LOGIN_REQUEST_CODE);
        } else {
            startActivity(intent);
            finish();
        }
    }

    public IRadarService getRadarService() {
        return radarService;
    }
}
