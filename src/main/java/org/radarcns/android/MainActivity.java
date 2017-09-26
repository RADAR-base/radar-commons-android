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
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.LoginActivity;
import org.radarcns.android.device.DeviceServiceConnection;
import org.radarcns.android.device.DeviceServiceProvider;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.android.util.Boast;
import org.radarcns.data.TimedInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_NETWORK_STATE;
import static android.Manifest.permission.BLUETOOTH;
import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;
import static org.radarcns.android.auth.LoginActivity.ACTION_LOGIN;
import static org.radarcns.android.device.DeviceService.DEVICE_CONNECT_FAILED;
import static org.radarcns.android.device.DeviceService.DEVICE_STATUS_NAME;

/** Base MainActivity class. It manages the services to collect the data and starts up a view. To
 * create an application, extend this class and override the abstract methods. */
public abstract class MainActivity extends Activity {
    private static final Logger logger = LoggerFactory.getLogger(MainActivity.class);

    private static final int REQUEST_ENABLE_PERMISSIONS = 2;
    private static final int LOGIN_REQUEST_CODE = 232619693;
    private static final int LOCATION_REQUEST_CODE = 232619694;
    private static final int USAGE_REQUEST_CODE = 232619695;

    /** Filters to only listen to certain device IDs. */
    private final Map<DeviceServiceConnection, Set<String>> deviceFilters;

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
    private boolean isForcedDisconnected;

    /** Defines callbacks for service binding, passed to bindService() */
    private final BroadcastReceiver bluetoothReceiver;
    private final BroadcastReceiver deviceFailedReceiver;

    /** Connections. **/
    private List<DeviceServiceProvider> mConnections;

    /** An overview of how many records have been sent throughout the application. */
    private final Map<DeviceServiceConnection, TimedInt> mTotalRecordsSent;
    private String latestTopicSent;
    private final TimedInt latestNumberOfRecordsSent;

    private RadarConfiguration radarConfiguration;

    /** Current server status. */
    private ServerStatusListener.Status serverStatus;
    private AppAuthState authState;

    private final LinkedHashSet<String> needsPermissions;
    private boolean requestedBt;

    public MainActivity() {
        super();
        isForcedDisconnected = false;
        serverStatus = null;

        mTotalRecordsSent = new HashMap<>();
        deviceFilters = new HashMap<>();

        bluetoothReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();

                if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    logger.info("Bluetooth state {}", state);
                    // Upon state change, restart ui handler and restart Scanning.
                    if (state == BluetoothAdapter.STATE_ON) {
                        logger.info("Bluetooth is on");
                        startScanning();
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        logger.warn("Bluetooth is off");
                        startScanning();
                    }
                }
            }
        };

        deviceFailedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, final Intent intent) {
                if (intent.getAction().equals(DEVICE_CONNECT_FAILED)) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Boast.makeText(MainActivity.this, "Cannot connect to device "
                                    + intent.getStringExtra(DEVICE_STATUS_NAME),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        };

        latestNumberOfRecordsSent = new TimedInt();
        needsPermissions = new LinkedHashSet<>();
    }

    /**
     * Create a RadarConfiguration object. At implementation, the Firebase version needs to be set
     * for this.
     *
     * @return configured RadarConfiguration
     */
    protected abstract RadarConfiguration createConfiguration();

    protected abstract Class<? extends LoginActivity> loginActivity();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        radarConfiguration = createConfiguration();

        authState = AppAuthState.Builder.from(this).build();
        if (!authState.isValid()) {
            startActivity(new Intent(this, loginActivity()));
            finish();
            return;
        }
        radarConfiguration.put(RadarConfiguration.USER_ID_KEY, authState.getUserId());

        onConfigChanged();
        logger.info("RADAR configuration at create: {}", radarConfiguration);

        try {
            mConnections = DeviceServiceProvider.loadProviders(this, radarConfiguration);
            for (DeviceServiceProvider provider : mConnections) {
                DeviceServiceConnection connection = provider.getConnection();
                mTotalRecordsSent.put(connection, new TimedInt());
                deviceFilters.put(connection, Collections.<String>emptySet());
            }
        } catch (IllegalArgumentException ex) {
            logger.error("Cannot instantiate device provider, waiting to fetch to complete", ex);
            mConnections = Collections.emptyList();
        }

        radarConfiguration.onFetchComplete(this, new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                if (task.isSuccessful()) {
                    // Once the config is successfully fetched it must be
                    // activated before newly fetched values are returned.
                    radarConfiguration.activateFetched();
                    for (DeviceServiceProvider provider : mConnections) {
                        provider.updateConfiguration();
                    }
                    logger.info("Remote Config: Activate success.");
                    // Set global properties.
                    logger.info("RADAR configuration at create: {}", radarConfiguration);
                    onConfigChanged();
                } else {
                    Boast.makeText(MainActivity.this, "Remote Config: Fetch Failed",
                            Toast.LENGTH_SHORT).show();
                    logger.info("Remote Config: Fetch failed. Stacktrace: {}", task.getException());
                }
            }
        });

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

        checkPermissions();
    }

    /**
     * Called whenever the RadarConfiguration is changed. This can be at activity start or
     * when the configuration is updated from Firebase.
     */
    protected abstract void onConfigChanged();

    /** Create a view to show the data of this activity. */
    protected abstract MainActivityView createView();

    /** Configure whether a boot listener should start this application at boot. */
    protected void configureRunAtBoot(@NonNull Class<?> bootReceiver) {
        ComponentName receiver = new ComponentName(
                getApplicationContext(), bootReceiver);
        PackageManager pm = getApplicationContext().getPackageManager();

        boolean startAtBoot = radarConfiguration.getBoolean(RadarConfiguration.START_AT_BOOT, false);
        boolean isStartedAtBoot = pm.getComponentEnabledSetting(receiver) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        if (startAtBoot && !isStartedAtBoot) {
            logger.info("From now on, this application will start at boot");
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } else if (!startAtBoot && isStartedAtBoot) {
            logger.info("Not starting application at boot anymore");
            pm.setComponentEnabledSetting(receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    @Override
    protected void onResume() {
        logger.info("mainActivity onResume");
        super.onResume();
        getHandler().post(mViewUpdater);
    }

    @Override
    protected void onPause() {
        logger.info("mainActivity onPause");
        getHandler().removeCallbacks(mViewUpdater);
        super.onPause();
    }

    @Override
    protected void onStart() {
        logger.info("mainActivity onStart");
        super.onStart();
        if (!authState.isValid()) {
            startLogin();
        }
        registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        registerReceiver(deviceFailedReceiver, new IntentFilter(DEVICE_CONNECT_FAILED));

        mHandlerThread = new HandlerThread("Service connection", Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();
        Handler localHandler = new Handler(mHandlerThread.getLooper());
        synchronized (this) {
            mHandler = localHandler;
        }
        mView = createView();

        new AsyncTask<DeviceServiceProvider, Void, Void>() {
            @Override
            protected Void doInBackground(DeviceServiceProvider... params) {
                for (DeviceServiceProvider provider : params) {
                    if (!provider.isBound()) {
                        logger.info("Binding to service: {}", provider);
                        provider.bind();
                    } else {
                        logger.info("Already bound: {}", provider);
                    }
                }
                return null;
            }
        }.execute(mConnections.toArray(new DeviceServiceProvider[mConnections.size()]));

        radarConfiguration.fetch();
    }

    @Override
    protected void onStop() {
        logger.info("mainActivity onStop");
        super.onStop();
        unregisterReceiver(deviceFailedReceiver);
        unregisterReceiver(bluetoothReceiver);
        synchronized (this) {
            mHandler = null;
        }
        mHandlerThread.quitSafely();
        mView = null;

        for (DeviceServiceProvider provider : mConnections) {
            if (provider.isBound()) {
                logger.info("Unbinding service: {}", provider);
                provider.unbind();
            } else {
                logger.info("Already unbound: {}", provider);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent result) {
        if (requestCode == LOGIN_REQUEST_CODE) {
            if (resultCode != RESULT_OK) {
                throw new IllegalStateException("Login should not be cancellable");
            }
            authState = AppAuthState.Builder.from(result.getExtras()).build();
            radarConfiguration.put(RadarConfiguration.USER_ID_KEY, authState.getUserId());
            onConfigChanged();
            for (DeviceServiceProvider provider : mConnections) {
                provider.updateConfiguration();
            }
        } else if (requestCode == LOCATION_REQUEST_CODE || requestCode == USAGE_REQUEST_CODE) {
            checkPermissions();
        }
    }

    /** Get background handler. */
    private synchronized Handler getHandler() {
        return mHandler;
    }

    /** Disconnect from all services. */
    protected void disconnect() {
        for (DeviceServiceProvider provider : mConnections) {
            disconnect(provider.getConnection());
        }
    }

    /** Disconnect from given service. */
    public void disconnect(DeviceServiceConnection connection) {
        if (connection.isRecording()) {
            connection.stopRecording();
        }
    }

    /**
     * If no Service is scanning, and ask one to start scanning.
     */
    protected void startScanning() {
        if (isForcedDisconnected) {
            return;
        }
        requestedBt = false;
        for (DeviceServiceProvider<?> provider : mConnections) {
            DeviceServiceConnection connection = provider.getConnection();
            if (!connection.hasService() || connection.isRecording() || !checkPermissions(provider)) {
                continue;
            }

            logger.info("Starting recording on connection {}", connection);
            connection.startRecording(deviceFilters.get(connection));
        }
    }

    protected boolean checkPermissions(DeviceServiceProvider<?> provider) {
        List<String> providerPermissions = provider.needsPermissions();

        if (providerPermissions.contains(BLUETOOTH)) {
            if (requestedBt || requestEnableBt()) {
                logger.info("Cannot start scanning on service {} until bluetooth is turned on.",
                        provider.getConnection());
                requestedBt = true;
                return false;
            }
        }
        for (String permission : providerPermissions) {
            if (needsPermissions.contains(permission)) {
                // cannot start
                return false;
            }
        }
        return true;
    }

    public void serviceConnected(final DeviceServiceConnection<?> connection) {
        ServerStatusListener.Status status = connection.getServerStatus();
        logger.info("Initial server status: {}", status);
        updateServerStatus(status);
        startScanning();
    }

    public synchronized void serviceDisconnected(DeviceServiceConnection<?> connection) {
        if (getHandler() != null) {
            new AsyncTask<DeviceServiceConnection, Void, Void>() {
                @Override
                protected Void doInBackground(DeviceServiceConnection... params) {
                    DeviceServiceProvider provider = getConnectionProvider(params[0]);
                    logger.info("Rebinding {} after disconnect", provider);
                    if (provider.isBound()) {
                        provider.unbind();
                    }
                    provider.bind();

                    return null;
                }
            }.execute(connection);
        }
    }

    public void deviceStatusUpdated(final DeviceServiceConnection connection, final DeviceStatusListener.Status status) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                int showRes = -1;
                switch (status) {
                    case READY:
                        showRes = R.string.device_ready;
                        break;
                    case CONNECTED:
                        showRes = R.string.device_connected;
                        break;
                    case CONNECTING:
                        showRes = R.string.device_connecting;
                        logger.info( "Device name is {} while connecting.", connection.getDeviceName());
                        break;
                    case DISCONNECTED:
                        showRes = R.string.device_disconnected;
                        startScanning();
                        break;
                    default:
                        break;
                }
                if (showRes != -1) {
                    Boast.makeText(MainActivity.this, showRes).show();
                }
            }
        });
    }

    /**
     * Sends an intent to request bluetooth to be turned on.
     * @return whether a request was sent
     */
    protected boolean requestEnableBt() {
        BluetoothAdapter btAdaptor = BluetoothAdapter.getDefaultAdapter();
        if (!btAdaptor.isEnabled()) {
            Intent btIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            btIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getApplicationContext().startActivity(btIntent);
            return true;
        } else {
            return false;
        }
    }

    protected List<String> getActivityPermissions() {
        return Arrays.asList(ACCESS_NETWORK_STATE, INTERNET);
    }

    protected void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.addAll(getActivityPermissions());
        for (DeviceServiceProvider<?> provider : mConnections) {
            permissions.addAll(provider.needsPermissions());
        }

        needsPermissions.clear();

        for (String permission : permissions) {
            if (permission.equals(ACCESS_FINE_LOCATION) || permission.equals(ACCESS_COARSE_LOCATION)) {
                LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

                //Start your Activity if location was enabled:
                if (!isGpsEnabled && !isNetworkEnabled) {
                    needsPermissions.add(LOCATION_SERVICE);
                    needsPermissions.add(permission);
                }
            }

            if (permission.equals(PACKAGE_USAGE_STATS)) {
                AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                int mode = appOps.checkOpNoThrow(
                        "android:get_usage_stats", android.os.Process.myUid(), getPackageName());

                if (mode != AppOpsManager.MODE_ALLOWED) {
                    needsPermissions.add(permission);
                }
            } else if (ContextCompat.checkSelfPermission(this, permission) != PackageManager
                    .PERMISSION_GRANTED) {
                logger.info("Need to request permission for {}", permission);
                needsPermissions.add(permission);
            }
        }

        if (needsPermissions.contains(LOCATION_SERVICE)) {
            requestLocationProvider();
        } else if (needsPermissions.contains(PACKAGE_USAGE_STATS)) {
            requestPackageUsageStats();
        } else if (!needsPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needsPermissions.toArray(new String[needsPermissions.size()]),
                    REQUEST_ENABLE_PERMISSIONS);
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
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    logger.info("Granted permission {}", permissions[i]);
                    needsPermissions.remove(permissions[i]);
                } else {
                    logger.info("Denied permission {}", permissions[i]);
                    return;
                }
            }
            // Permission granted.
            startScanning();
        }
    }

    protected boolean hasPermission(String permissionName) {
        return !needsPermissions.contains(permissionName);
    }

    public void updateServerStatus(final ServerStatusListener.Status status) {
        this.serverStatus = status;

        if (status == ServerStatusListener.Status.UNAUTHORIZED) {
            synchronized (this) {
                // login already started, or was finished up to 3 seconds ago (give time to propagate new auth state.)
                if (authState.isInvalidated() || authState.timeSinceLastUpdate() < 3_000L) {
                    return;
                }
                authState.invalidate(this);
            }
            startLogin();
        }
    }

    protected void startLogin() {
        Intent intent = new Intent(this, loginActivity());
        intent.setAction(ACTION_LOGIN);
        startActivityForResult(intent, LOGIN_REQUEST_CODE);
    }

    public void updateServerRecordsSent(DeviceServiceConnection<?> connection, String topic,
                                        int numberOfRecords) {
        if (numberOfRecords >= 0){
            mTotalRecordsSent.get(connection).add(numberOfRecords);
        }
        latestTopicSent = topic;
        latestNumberOfRecordsSent.set(numberOfRecords);
    }

    public void setAllowedDeviceIds(final DeviceServiceConnection connection, Set<String> allowedIds) {
        deviceFilters.put(connection, allowedIds);

        // Do NOT disconnect if input has not changed, is empty or equals the connected device.
        if (connection.hasService() && !connection.isAllowedDevice(allowedIds)) {
            getHandler().post(new Runnable() {
                @Override
                public void run() {
                    if (connection.isRecording()) {
                        connection.stopRecording();
                        // will restart recording once the status is set to disconnected.
                    }
                }
            });
        }
    }

    protected DeviceServiceProvider getConnectionProvider(DeviceServiceConnection<?> connection) {
        for (DeviceServiceProvider provider : mConnections) {
            if (provider.getConnection().equals(connection)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("DeviceServiceConnection "
                + connection.getServiceClassName() + " not set");
    }

    public ServerStatusListener.Status getServerStatus() {
        return serverStatus;
    }

    public TimedInt getTopicsSent(DeviceServiceConnection connection) {
        return mTotalRecordsSent.get(connection);
    }

    public String getLatestTopicSent() {
        return latestTopicSent;
    }

    public TimedInt getLatestNumberOfRecordsSent() {
        return latestNumberOfRecordsSent;
    }

    public List<DeviceServiceProvider> getConnections() {
        return Collections.unmodifiableList(mConnections);
    }

    public RadarConfiguration getRadarConfiguration() {
        return radarConfiguration;
    }

    public AppAuthState getAuthState() {
        return authState;
    }
}
