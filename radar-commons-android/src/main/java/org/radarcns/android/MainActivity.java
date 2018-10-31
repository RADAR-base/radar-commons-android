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

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.LocalBroadcastManager;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.util.NetworkConnectedReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.radarcns.android.RadarConfiguration.MANAGEMENT_PORTAL_URL_KEY;
import static org.radarcns.android.RadarConfiguration.UNSAFE_KAFKA_CONNECTION;
import static org.radarcns.android.RadarService.ACTION_BLUETOOTH_NEEDED_CHANGED;
import static org.radarcns.android.auth.LoginActivity.ACTION_LOGIN;

/** Base MainActivity class. It manages the services to collect the data and starts up a view. To
 * create an application, extend this class and override the abstract methods. */
@SuppressWarnings({"unused", "WeakerAccess"})
public abstract class MainActivity extends Activity implements NetworkConnectedReceiver.NetworkConnectedListener {
    private static final Logger logger = LoggerFactory.getLogger(MainActivity.class);

    private static final int REQUEST_ENABLE_PERMISSIONS = 2;
    private static final int LOGIN_REQUEST_CODE = 232619693;
    private static final int LOCATION_REQUEST_CODE = 232619694;
    private static final int USAGE_REQUEST_CODE = 232619695;
    private static final long REQUEST_PERMISSION_TIMEOUT_MS = 3_600_000L;

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
    private final Set<String> isRequestingPermissions = new HashSet<>();
    private long isRequestingPermissionsTime = Long.MAX_VALUE;

    private boolean requestedBt;

    private IRadarBinder radarService;

    /** Defines callbacks for service binding, passed to bindService() */
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(intent.getAction(), BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                logger.debug("Bluetooth state {}", state);
                // Upon state change, restart ui handler and restart Scanning.
                if (state == BluetoothAdapter.STATE_OFF) {
                    requestEnableBt();
                }
            }
        }
    };

    private final BroadcastReceiver bluetoothNeededReceiverImpl = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(intent.getAction(), ACTION_BLUETOOTH_NEEDED_CHANGED)) {
                testBindBluetooth();
            }
        }
    };
    private BroadcastReceiver bluetoothNeededReceiver;

    private volatile boolean bluetoothReceiverIsEnabled;
    protected RadarConfiguration configuration;

    /**
     * Sends an intent to request bluetooth to be turned on.
     */
    protected void requestEnableBt() {
        BluetoothAdapter btAdaptor = BluetoothAdapter.getDefaultAdapter();
        if (btAdaptor.isEnabled()) {
            return;
        }

        Intent btIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        btIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getApplicationContext().startActivity(btIntent);
    }

    private NetworkConnectedReceiver networkReceiver;

    private final ServiceConnection radarServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            radarService = (IRadarBinder) service;
            mView = createView();
            testBindBluetooth();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            radarService = null;
        }
    };

    private void testBindBluetooth() {
        boolean needsBluetooth = radarService != null && radarService.needsBluetooth();

        if (needsBluetooth == bluetoothReceiverIsEnabled) {
            return;
        }

        if (needsBluetooth) {
            registerReceiver(bluetoothReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            bluetoothReceiverIsEnabled = true;
            requestEnableBt();
        } else {
            bluetoothReceiverIsEnabled = false;
            unregisterReceiver(bluetoothReceiver);
        }
    }

    @Override
    protected final void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bluetoothReceiverIsEnabled = false;

        configuration = ((RadarApplication)getApplication()).getConfiguration();

        if (getIntent() == null || getIntent().getExtras() == null) {
            authState = AppAuthState.Builder.from(this).build();
        } else {
            Bundle extras = getIntent().getExtras();
            extras.setClassLoader(MainActivity.class.getClassLoader());
            authState = AppAuthState.Builder.from(extras).build();
        }

        if (!authState.isValid()) {
            startLogin(false);
            return;
        }

        networkReceiver = new NetworkConnectedReceiver(this, this);
        create();
    }

    @CallSuper
    protected void create() {
        logger.info("RADAR configuration at create: {}", configuration);
        onConfigChanged();

        configurationBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onConfigChanged();
            }
        };
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(configurationBroadcastReceiver,
                        new IntentFilter(RadarConfiguration.RADAR_CONFIGURATION_CHANGED));

        networkReceiver.register();

        Bundle extras = new Bundle();
        authState.addToBundle(extras);
        startService(new Intent(this, ((RadarApplication)getApplication()).getRadarService()).putExtras(extras));

        // Start the UI thread
        uiRefreshRate = configuration.getLong(RadarConfiguration.UI_REFRESH_RATE_KEY);
        mViewUpdater = () -> {
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
        };
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (configurationBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this)
                    .unregisterReceiver(configurationBroadcastReceiver);
        }
        if (networkReceiver != null) {
            networkReceiver.unregister();
        }
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
        super.onResume();
        getHandler().post(mViewUpdater);
    }

    @Override
    protected void onPause() {
        getHandler().removeCallbacks(mViewUpdater);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!authState.isValid()) {
            startLogin(true);
        }

        mHandlerThread = new HandlerThread("Service connection", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        Handler localHandler = new Handler(mHandlerThread.getLooper());
        synchronized (this) {
            mHandler = localHandler;
            if (!isRequestingPermissions.isEmpty()) {
                long now = SystemClock.elapsedRealtime();
                long expires = isRequestingPermissionsTime + getRequestPermissionTimeoutMs();
                if (expires <= now) {
                    resetRequestingPermission();
                } else {
                    mHandler.postDelayed(this::resetRequestingPermission, expires - now);
                }
            }
        }
        bindService(new Intent(this, ((RadarApplication)getApplication()).getRadarService()), radarServiceConnection, 0);
        testBindBluetooth();
        bluetoothNeededReceiver = bluetoothNeededReceiverImpl;
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(bluetoothNeededReceiver,
                        new IntentFilter(ACTION_BLUETOOTH_NEEDED_CHANGED));
    }

    private synchronized void resetRequestingPermission() {
        isRequestingPermissions.clear();
        isRequestingPermissionsTime = Long.MAX_VALUE;
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
    public void onNetworkConnectionChanged(boolean isConnected, boolean isWifiOrEthernet) {
        if (isConnected && !authState.isValid()) {
            startLogin(false);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        unbindService(radarServiceConnection);

        synchronized (this) {
            mHandler = null;
        }
        mHandlerThread.quitSafely();
        mView = null;
        if (bluetoothNeededReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(bluetoothNeededReceiver);
            bluetoothNeededReceiver = null;
        }
        if (bluetoothReceiverIsEnabled) {
            bluetoothReceiverIsEnabled = false;
            unregisterReceiver(bluetoothReceiver);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent result) {
        switch (requestCode) {
            case LOGIN_REQUEST_CODE: {
                if (resultCode != RESULT_OK) {
                    logger.error("Login should not be cancellable. Opening login again");
                    startLogin(true);
                }
                if (result != null && result.getExtras() != null) {
                    Bundle extras = result.getExtras();
                    extras.setClassLoader(MainActivity.class.getClassLoader());
                    authState = AppAuthState.Builder.from(extras).build();
                } else {
                    authState = AppAuthState.Builder.from(this).build();
                }
                onConfigChanged();
                break;
            }
            case LOCATION_REQUEST_CODE: {
                onPermissionRequestResult(LOCATION_SERVICE, resultCode == RESULT_OK);
                break;
            }
            case USAGE_REQUEST_CODE: {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    onPermissionRequestResult(
                            Manifest.permission.PACKAGE_USAGE_STATS,
                            resultCode == RESULT_OK);
                }
                break;
            }
        }
    }

    private void onPermissionRequestResult(String permission, boolean granted) {
        needsPermissions.remove(permission);
        synchronized (this) {
            isRequestingPermissions.remove(permission);
        }

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
        if (needsPermissions.isEmpty()) {
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent()
                            .setAction(RadarService.ACTION_PERMISSIONS_GRANTED)
                            .putExtra(RadarService.EXTRA_PERMISSIONS, new String[0])
                            .putExtra(RadarService.EXTRA_GRANT_RESULTS, new int[0]));
            return;
        }

        Set<String> currentlyNeeded = new HashSet<>(needsPermissions);
        synchronized (this) {
            currentlyNeeded.removeAll(isRequestingPermissions);
        }
        if (currentlyNeeded.isEmpty()) {
            return;
        }
        if (currentlyNeeded.contains(LOCATION_SERVICE)) {
            addRequestingPermissions(LOCATION_SERVICE);
            requestLocationProvider();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && currentlyNeeded.contains(Manifest.permission.PACKAGE_USAGE_STATS)) {
            addRequestingPermissions(Manifest.permission.PACKAGE_USAGE_STATS);
            requestPackageUsageStats();
        } else {
            addRequestingPermissions(currentlyNeeded);
            ActivityCompat.requestPermissions(this,
                    currentlyNeeded.toArray(new String[0]), REQUEST_ENABLE_PERMISSIONS);
        }
    }

    private void addRequestingPermissions(String permission) {
        addRequestingPermissions(Collections.singleton(permission));
    }

    private synchronized void addRequestingPermissions(Set<String> permissions) {
        isRequestingPermissions.addAll(permissions);

        if (mHandler != null && isRequestingPermissionsTime != Long.MAX_VALUE) {
            isRequestingPermissionsTime = SystemClock.elapsedRealtime();
            mHandler.postDelayed(() -> {
                resetRequestingPermission();
                checkPermissions();
            }, getRequestPermissionTimeoutMs());
        }
    }

    private void requestLocationProvider() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle(R.string.enable_location_title)
                .setMessage(R.string.enable_location)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    if (intent.resolveActivity(getPackageManager()) == null) {
                        intent = new Intent(Settings.ACTION_SETTINGS);
                    }
                    startActivityForResult(intent, LOCATION_REQUEST_CODE);
                    dialog.cancel();
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();
    }

    private void requestPackageUsageStats() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle(R.string.enable_package_usage_title)
                .setMessage(R.string.enable_package_usage)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    dialog.cancel();
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    if (intent.resolveActivity(getPackageManager()) == null) {
                        intent = new Intent(Settings.ACTION_SETTINGS);
                    }
                    startActivityForResult(intent, USAGE_REQUEST_CODE);
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
        Class<?> loginActivity = ((RadarApplication) getApplication()).getLoginActivity();
        Intent intent = new Intent(this, loginActivity);

        Bundle extras = new Bundle();
        configuration.putExtras(extras, MANAGEMENT_PORTAL_URL_KEY, UNSAFE_KAFKA_CONNECTION);
        intent.putExtras(extras);

        if (forResult) {
            intent.setAction(ACTION_LOGIN);
            startActivityForResult(intent, LOGIN_REQUEST_CODE);
        } else {
            startActivity(intent);
            finish();
        }
    }

    protected long getRequestPermissionTimeoutMs() {
        return REQUEST_PERMISSION_TIMEOUT_MS;
    }

    public IRadarBinder getRadarService() {
        return radarService;
    }

    public String getUserId() {
        return configuration.getString(RadarConfiguration.USER_ID_KEY, null);
    }

    public String getProjectId() {
        return configuration.getString(RadarConfiguration.PROJECT_ID_KEY, null);
    }
}
