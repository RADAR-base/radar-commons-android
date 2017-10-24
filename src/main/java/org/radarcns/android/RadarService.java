package org.radarcns.android;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.*;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.widget.Toast;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AppSource;
import org.radarcns.android.auth.LoginActivity;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.device.DeviceService;
import org.radarcns.android.device.DeviceServiceConnection;
import org.radarcns.android.device.DeviceServiceProvider;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.android.util.Boast;
import org.radarcns.config.ServerConfig;
import org.radarcns.data.TimedInt;
import org.radarcns.producer.rest.SchemaRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.*;

import static android.Manifest.permission.*;
import static org.radarcns.android.RadarConfiguration.*;
import static org.radarcns.android.device.DeviceService.DEVICE_CONNECT_FAILED;
import static org.radarcns.android.device.DeviceService.DEVICE_STATUS_NAME;


public class RadarService extends Service implements ServerStatusListener {
    private static final Logger logger = LoggerFactory.getLogger(RadarService.class);

    public static String RADAR_PACKAGE = RadarService.class.getPackage().getName();

    public static String EXTRA_MAIN_ACTIVITY = RADAR_PACKAGE + ".EXTRA_MAIN_ACTIVITY";
    public static String EXTRA_LOGIN_ACTIVITY = RADAR_PACKAGE + ".EXTRA_LOGIN_ACTIVITY";

    public static String ACTION_CHECK_PERMISSIONS = RADAR_PACKAGE + ".ACTION_CHECK_PERMISSIONS";
    public static String EXTRA_PERMISSIONS = RADAR_PACKAGE + ".EXTRA_PERMISSIONS";

    public static String ACTION_PERMISSIONS_GRANTED = RADAR_PACKAGE + ".ACTION_PERMISSIONS_GRANTED";
    public static String EXTRA_GRANT_RESULTS = RADAR_PACKAGE + ".EXTRA_GRANT_RESULTS";

    private final BroadcastReceiver permissionsBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onPermissionsGranted(intent.getStringArrayExtra(EXTRA_PERMISSIONS), intent.getIntArrayExtra(EXTRA_GRANT_RESULTS));
        }
    };

    private final BroadcastReceiver loginBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateAuthState(AppAuthState.Builder.from(intent.getExtras()).build());
        }
    };

    private IBinder binder;

    private TableDataHandler dataHandler;
    private String mainActivityClass;
    private String loginActivityClass;

    /** Filters to only listen to certain device IDs. */
    private final Map<DeviceServiceConnection, Set<String>> deviceFilters = new HashMap<>();

    private boolean isForcedDisconnected; // TODO: Make working or remove

    /** Defines callbacks for service binding, passed to bindService() */
    private final BroadcastReceiver  bluetoothReceiver = new BroadcastReceiver() {
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

    private final BroadcastReceiver deviceFailedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            if (intent.getAction().equals(DEVICE_CONNECT_FAILED)) {
                Boast.makeText(RadarService.this,
                        "Cannot connect to device " + intent.getStringExtra(DEVICE_STATUS_NAME),
                        Toast.LENGTH_SHORT).show();
            }
        }
    };

    /** Connections. **/
    private final List<DeviceServiceProvider> mConnections = new ArrayList<>();

    /** An overview of how many records have been sent throughout the application. */
    private final Map<DeviceServiceConnection, TimedInt> mTotalRecordsSent = new HashMap<>();
    private String latestTopicSent;
    private final TimedInt latestNumberOfRecordsSent = new TimedInt();

    /** Current server status. */
    private ServerStatusListener.Status serverStatus;
    private AppAuthState authState;

    private final LinkedHashSet<String> needsPermissions = new LinkedHashSet<>();
    private boolean requestedBt;


    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        binder = createBinder();

        authState = AppAuthState.Builder.from(this).build();

        registerReceiver(permissionsBroadcastReceiver,
                new IntentFilter(ACTION_PERMISSIONS_GRANTED));
        registerReceiver(loginBroadcastReceiver,
                new IntentFilter(LoginActivity.ACTION_LOGIN_SUCCESS));
        registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        registerReceiver(deviceFailedReceiver, new IntentFilter(DEVICE_CONNECT_FAILED));

        configure();

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


        final RadarConfiguration radarConfiguration = RadarConfiguration.getInstance();
        radarConfiguration.onFetchComplete(null, new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                if (task.isSuccessful()) {
                    // Once the config is successfully fetched it must be
                    // activated before newly fetched values are returned.
                    radarConfiguration.activateFetched();

                    logger.info("Remote Config: Activate success.");
                    // Set global properties.
                    logger.info("RADAR configuration changed: {}", radarConfiguration);
                    configure();
                    sendBroadcast(new Intent(RadarConfiguration.RADAR_CONFIGURATION_CHANGED));
                } else {
                    Boast.makeText(RadarService.this, "Remote Config: Fetch Failed",
                            Toast.LENGTH_SHORT).show();
                    logger.info("Remote Config: Fetch failed. Stacktrace: {}", task.getException());
                }
            }
        });

        radarConfiguration.fetch();
    }

    protected IBinder createBinder() {
        return new RadarBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mainActivityClass = intent.getStringExtra(EXTRA_MAIN_ACTIVITY);
        loginActivityClass = intent.getStringExtra(EXTRA_LOGIN_ACTIVITY);

        checkPermissions();

        startForeground(1,
                new Notification.Builder(this)
                        .setContentTitle("RADAR")
                        .setContentText("Open RADAR app")
                        .setContentIntent(PendingIntent.getActivity(this, 0, new Intent().setComponent(new ComponentName(this, mainActivityClass)), 0))
                        .build());

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(permissionsBroadcastReceiver);
        unregisterReceiver(loginBroadcastReceiver);
        unregisterReceiver(bluetoothReceiver);
        unregisterReceiver(deviceFailedReceiver);

        for (DeviceServiceProvider provider : mConnections) {
            if (provider.isBound()) {
                logger.info("Unbinding service: {}", provider);
                provider.unbind();
            } else {
                logger.info("Already unbound: {}", provider);
            }
        }

        super.onDestroy();
    }

    protected void configure() {
        RadarConfiguration configuration = RadarConfiguration.getInstance();

        TableDataHandler localDataHandler;
        ServerConfig kafkaConfig = null;
        SchemaRetriever remoteSchemaRetriever = null;
        boolean unsafeConnection = configuration.getBoolean(UNSAFE_KAFKA_CONNECTION, false);

        if (configuration.has(KAFKA_REST_PROXY_URL_KEY)) {
            String urlString = configuration.getString(KAFKA_REST_PROXY_URL_KEY);
            if (!urlString.isEmpty()) {
                try {
                    ServerConfig schemaRegistry = new ServerConfig(configuration.getString(SCHEMA_REGISTRY_URL_KEY));
                    schemaRegistry.setUnsafe(unsafeConnection);
                    remoteSchemaRetriever = new SchemaRetriever(schemaRegistry, 30);
                    kafkaConfig = new ServerConfig(urlString);
                    kafkaConfig.setUnsafe(unsafeConnection);
                } catch (MalformedURLException ex) {
                    logger.error("Malformed Kafka server URL {}", urlString);
                    throw new IllegalArgumentException(ex);
                }
            }
        }

        boolean sendOnlyWithWifi = configuration.getBoolean(SEND_ONLY_WITH_WIFI, true);

        int maxBytes = configuration.getInt(MAX_CACHE_SIZE, Integer.MAX_VALUE);

        boolean newlyCreated;
        synchronized (this) {
            if (dataHandler == null) {
                try {
                    dataHandler = new TableDataHandler(
                            this, kafkaConfig, remoteSchemaRetriever, maxBytes,
                            sendOnlyWithWifi, authState);
                    newlyCreated = true;
                } catch (IOException ex) {
                    logger.error("Failed to instantiate Data Handler", ex);
                    throw new IllegalStateException(ex);
                }
            } else {
                newlyCreated = false;
            }
            localDataHandler = dataHandler;
        }

        if (!newlyCreated) {
            if (kafkaConfig == null) {
                localDataHandler.disableSubmitter();
            } else {
                localDataHandler.setKafkaConfig(kafkaConfig);
                localDataHandler.setSchemaRetriever(remoteSchemaRetriever);
            }
            localDataHandler.setMaximumCacheSize(maxBytes);
            localDataHandler.setAuthState(authState);
        }

        localDataHandler.setSendOnlyWithWifi(sendOnlyWithWifi);
        localDataHandler.setCompression(configuration.getBoolean(SEND_WITH_COMPRESSION, false));

        if (configuration.has(DATA_RETENTION_KEY)) {
            localDataHandler.setDataRetention(
                    configuration.getLong(DATA_RETENTION_KEY));
        }
        if (configuration.has(KAFKA_UPLOAD_RATE_KEY)) {
            localDataHandler.setKafkaUploadRate(
                    configuration.getLong(KAFKA_UPLOAD_RATE_KEY));
        }
        if (configuration.has(KAFKA_RECORDS_SEND_LIMIT_KEY)) {
            localDataHandler.setKafkaRecordsSendLimit(
                    configuration.getInt(KAFKA_RECORDS_SEND_LIMIT_KEY));
        }
        if (configuration.has(SENDER_CONNECTION_TIMEOUT_KEY)) {
            localDataHandler.setSenderConnectionTimeout(
                    configuration.getLong(SENDER_CONNECTION_TIMEOUT_KEY));
        }
        if (configuration.has( DATABASE_COMMIT_RATE_KEY)) {
            localDataHandler.setDatabaseCommitRate(
                    configuration.getLong(DATABASE_COMMIT_RATE_KEY));
        }
        if (configuration.has(KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL)) {
            localDataHandler.setMinimumBatteryLevel(configuration.getFloat(
                    KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL));
        }

        if (newlyCreated) {
            localDataHandler.addStatusListener(this);
            localDataHandler.start();
        } else if (kafkaConfig != null) {
            localDataHandler.enableSubmitter();
        }

        List<DeviceServiceProvider> connections = DeviceServiceProvider.loadProviders(this, RadarConfiguration.getInstance());

        for (DeviceServiceProvider provider : new ArrayList<>(mConnections)) {
            if (!connections.contains(provider)) {
                provider.unbind();
                mConnections.remove(provider);
            }
        }

        for (DeviceServiceProvider provider : connections) {
            if (!mConnections.contains(provider)) {
                mConnections.add(provider);
                DeviceServiceConnection connection = provider.getConnection();
                mTotalRecordsSent.put(connection, new TimedInt());
                deviceFilters.put(connection, Collections.<String>emptySet());
            }
        }

        for (DeviceServiceProvider provider : mConnections) {
            provider.updateConfiguration();
        }

        // TODO: check what sources are available in the authState (if any)
        // if any sources are available:
        //   - only start up providers that DeviceServiceProvider#matches one of the sources
        //   - set that source in the DeviceServiceProvider.
    }

    public TableDataHandler getDataHandler() {
        return dataHandler;
    }

    protected void requestPermissions(String[] permissions) {
        startActivity(new Intent()
                .setComponent(new ComponentName(this, mainActivityClass))
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setAction(ACTION_CHECK_PERMISSIONS)
                .putExtra(EXTRA_PERMISSIONS, permissions));
    }

    protected void onPermissionsGranted(String[] permissions, int[] grantResults) {
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

    protected void startLogin() {
        startActivity(new Intent().setComponent(new ComponentName(this, loginActivityClass)));
    }

    protected void updateAuthState(AppAuthState authState) {
        this.authState = authState;
        RadarConfiguration.getInstance().put(RadarConfiguration.PROJECT_ID_KEY, authState.getProjectId());
        RadarConfiguration.getInstance().put(RadarConfiguration.USER_ID_KEY, authState.getUserId());
        configure();
    }

    public void serviceConnected(DeviceServiceConnection<?> connection) {
        ServerStatusListener.Status status = connection.getServerStatus();
        logger.info("Initial server status: {}", status);
        updateServerStatus(status);
        startScanning();
    }

    public void serviceDisconnected(final DeviceServiceConnection<?> connection) {
            new AsyncTask<DeviceServiceConnection, Void, Void>() {
                @Override
                protected Void doInBackground(DeviceServiceConnection... params) {
                    DeviceServiceProvider provider = getConnectionProvider(connection);
                    logger.info("Rebinding {} after disconnect", provider);
                    if (provider.isBound()) {
                        provider.unbind();
                    }
                    provider.bind();

                    return null;
                }
            }.execute();
    }

    public void updateServerStatus(ServerStatusListener.Status serverStatus) {
        this.serverStatus = serverStatus;
        if (serverStatus == ServerStatusListener.Status.UNAUTHORIZED) {
            synchronized (RadarService.this) {
                // login already started, or was finished up to 3 seconds ago (give time to propagate new auth state.)
                if (authState.isInvalidated() || authState.timeSinceLastUpdate() < 3_000L) {
                    return;
                }
                authState.invalidate(RadarService.this);
            }
            startLogin();
        }
    }

    @Override
    public void updateRecordsSent(String topicName, int numberOfRecords) {

    }

    public void deviceStatusUpdated(final DeviceServiceConnection<?> connection, final DeviceStatusListener.Status status) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
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
                    Boast.makeText(RadarService.this, showRes).show();
                }
            }
        });
    }

    public void updateServerRecordsSent(DeviceServiceConnection<?> connection, String topic, int numberOfRecords) {
        if (numberOfRecords >= 0){
            mTotalRecordsSent.get(connection).add(numberOfRecords);
        }
        latestTopicSent = topic;
        latestNumberOfRecordsSent.set(numberOfRecords);
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
            AppSource source = provider.getSource();
            Set<String> filters;
            if (source != null && source.getExpectedSourceName() != null) {
                String[] expectedIds = source.getExpectedSourceName().split(",");
                filters = new HashSet<>(Arrays.asList(expectedIds));
            } else {
                filters = deviceFilters.get(connection);
            }
            connection.startRecording(filters);
        }
    }

    protected void checkPermissions() {
        Set<String> permissions = new HashSet<>(getServicePermissions());
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

        if (!needsPermissions.isEmpty()) {
            requestPermissions(needsPermissions.toArray(new String[needsPermissions.size()]));
        }
    }

    protected List<String> getServicePermissions() {
        return Arrays.asList(ACCESS_NETWORK_STATE, INTERNET);
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

    /** Configure whether a boot listener should start this application at boot. */
    protected void configureRunAtBoot(@NonNull Class<?> bootReceiver) {
        ComponentName receiver = new ComponentName(
                getApplicationContext(), bootReceiver);
        PackageManager pm = getApplicationContext().getPackageManager();

        boolean startAtBoot = RadarConfiguration.getInstance().getBoolean(RadarConfiguration.START_AT_BOOT, false);
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

    protected class RadarBinder extends Binder implements IRadarService {
        @Override
        public ServerStatusListener.Status getServerStatus() {
             return serverStatus;
        }

        @Override
        public TimedInt getTopicsSent(DeviceServiceConnection connection) {
            return mTotalRecordsSent.get(connection);
        }

        @Override
        public String getLatestTopicSent() {
            return latestTopicSent;
        }

        @Override
        public TimedInt getLatestNumberOfRecordsSent() {
            return latestNumberOfRecordsSent;
        }

        @Override
        public List<DeviceServiceProvider> getConnections() {
            return Collections.unmodifiableList(mConnections);
        }

        @Override
        public AppAuthState getAuthState() {
            return authState;
        }

        @Override
        public void setAllowedDeviceIds(final DeviceServiceConnection connection, Set<String> allowedIds) {
            deviceFilters.put(connection, allowedIds);

            // Do NOT disconnect if input has not changed, is empty or equals the connected device.
            if (connection.hasService() && !connection.isAllowedDevice(allowedIds)) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
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
    }
}
