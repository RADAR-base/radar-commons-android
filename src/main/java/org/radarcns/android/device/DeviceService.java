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

package org.radarcns.android.device;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.R;
import org.radarcns.android.RadarApplication;
import org.radarcns.android.RadarConfiguration;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.data.DataCache;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.android.util.DiskSpaceService;
import org.radarcns.config.ServerConfig;
import org.radarcns.data.Record;
import org.radarcns.key.MeasurementKey;
import org.radarcns.producer.SchemaRetriever;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.radarcns.android.RadarConfiguration.DATABASE_COMMIT_RATE_KEY;
import static org.radarcns.android.RadarConfiguration.DATA_RETENTION_KEY;
import static org.radarcns.android.RadarConfiguration.DEFAULT_GROUP_ID_KEY;
import static org.radarcns.android.RadarConfiguration.DISK_SPACE_CHECK_ENABLE;
import static org.radarcns.android.RadarConfiguration.KAFKA_CLEAN_RATE_KEY;
import static org.radarcns.android.RadarConfiguration.KAFKA_RECORDS_SEND_LIMIT_KEY;
import static org.radarcns.android.RadarConfiguration.KAFKA_REST_PROXY_URL_KEY;
import static org.radarcns.android.RadarConfiguration.KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL;
import static org.radarcns.android.RadarConfiguration.KAFKA_UPLOAD_RATE_KEY;
import static org.radarcns.android.RadarConfiguration.MAX_CACHE_SIZE;
import static org.radarcns.android.RadarConfiguration.SCHEMA_REGISTRY_URL_KEY;
import static org.radarcns.android.RadarConfiguration.SENDER_CONNECTION_TIMEOUT_KEY;
import static org.radarcns.android.RadarConfiguration.SEND_ONLY_WITH_WIFI;
import static org.radarcns.android.RadarConfiguration.SEND_WITH_COMPRESSION;
import static org.radarcns.android.RadarConfiguration.UNSAFE_KAFKA_CONNECTION;

/**
 * A service that manages a DeviceManager and a TableDataHandler to send store the data of a
 * wearable device and send it to a Kafka REST proxy.
 *
 * Specific wearables should extend this class.
 */
public abstract class DeviceService extends Service implements DeviceStatusListener, ServerStatusListener {
    private static final int ONGOING_NOTIFICATION_ID = 11;
    private static final String PREFIX = "org.radarcns.android.";
    public static final String SERVER_STATUS_CHANGED = PREFIX + "ServerStatusListener.Status";
    public static final String SERVER_RECORDS_SENT_TOPIC = PREFIX + "ServerStatusListener.topic";
    public static final String SERVER_RECORDS_SENT_NUMBER = PREFIX + "ServerStatusListener.lastNumberOfRecordsSent";
    public static final String CACHE_TOPIC = PREFIX + "DataCache.topic";
    public static final String CACHE_RECORDS_UNSENT_NUMBER = PREFIX + "DataCache.numberOfRecords.first";
    public static final String CACHE_RECORDS_SENT_NUMBER = PREFIX + "DataCache.numberOfRecords.second";
    public static final String DEVICE_SERVICE_CLASS = PREFIX + "DeviceService.getClass";
    public static final String DEVICE_STATUS_CHANGED = PREFIX + "DeviceStatusListener.Status";
    public static final String DEVICE_STATUS_NAME = PREFIX + "Devicemanager.getName";
    public static final String DEVICE_CONNECT_FAILED = PREFIX + "DeviceStatusListener.deviceFailedToConnect";

    /** Stops the device when bluetooth is disabled. */
    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_TURNING_OFF: case BluetoothAdapter.STATE_OFF:
                        logger.warn("Bluetooth is off");
                        stopDeviceManager(unsetDeviceManager());
                        break;
                    default:
                        logger.debug("Bluetooth is in state {}", state);
                        break;
                }
            }
        }
    };

    private static final Logger logger = LoggerFactory.getLogger(DeviceService.class);
    private TableDataHandler dataHandler;
    private DeviceManager deviceScanner;
    private LocalBinder mBinder;
    private final AtomicInteger numberOfActivitiesBound = new AtomicInteger(0);
    private boolean isInForeground;
    private boolean isConnected;
    private int latestStartId = -1;
    private String userId;
    private ServiceConnection diskSpaceChecker;

    @CallSuper
    @Override
    public void onCreate() {
        logger.info("Creating DeviceService {}", this);
        super.onCreate();
        mBinder = createBinder();

        // Register for broadcasts on BluetoothAdapter state change
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothReceiver, filter);

        diskSpaceChecker = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // noop
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // noop
            }
        };

        synchronized (this) {
            numberOfActivitiesBound.set(0);
            isInForeground = false;
            deviceScanner = null;
        }
    }

    @CallSuper
    @Override
    public void onDestroy() {
        logger.info("Destroying DeviceService {}", this);
        super.onDestroy();
        // Unregister broadcast listeners
        unregisterReceiver(mBluetoothReceiver);
        stopDeviceManager(unsetDeviceManager());

        unbindService(diskSpaceChecker);

        try {
            dataHandler.close();
        } catch (IOException e) {
            // do nothing
        }
    }

    @CallSuper
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        logger.info("Starting DeviceService {}", this);
        synchronized (this) {
            latestStartId = startId;
        }
        if (intent != null) {
            onInvocation(intent.getExtras());
        }
        // If we get killed, after returning from here, restart
        // keep all the configuration from the previous iteration
        return START_REDELIVER_INTENT;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        onRebind(intent);
        return mBinder;
    }

    @CallSuper
    @Override
    public void onRebind(Intent intent) {
        logger.info("Received (re)bind in {}", this);
        numberOfActivitiesBound.incrementAndGet();
        if (intent != null) {
            Bundle extras = intent.getExtras();
            onInvocation(extras);

            if (RadarConfiguration.getBooleanExtra(extras, DISK_SPACE_CHECK_ENABLE, false)) {
                Intent diskSpaceIntent = new Intent(this, DiskSpaceService.class);
                diskSpaceIntent.putExtras(extras);
                bindService(diskSpaceIntent, diskSpaceChecker, BIND_AUTO_CREATE);
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        logger.info("Received unbind in {}", this);
        int startId = -1;
        synchronized (this) {
            if (numberOfActivitiesBound.decrementAndGet() == 0 && !isConnected) {
                startId = latestStartId;
            }
        }
        if (startId != -1) {
            logger.info("Stopping self if latest start ID was {}", latestStartId);
            stopSelf(latestStartId);
        }
        return true;
    }

    @Override
    public void deviceFailedToConnect(String deviceName) {
        Intent statusChanged = new Intent(DEVICE_CONNECT_FAILED);
        statusChanged.putExtra(DEVICE_SERVICE_CLASS, getClass().getName());
        statusChanged.putExtra(DEVICE_STATUS_NAME, deviceName);
        sendBroadcast(statusChanged);
    }

    @Override
    public void deviceStatusUpdated(DeviceManager deviceManager,
                                    DeviceStatusListener.Status status) {
        Intent statusChanged = new Intent(DEVICE_STATUS_CHANGED);
        statusChanged.putExtra(DEVICE_STATUS_CHANGED, status.ordinal());
        statusChanged.putExtra(DEVICE_SERVICE_CLASS, getClass().getName());
        if (deviceManager.getName() != null) {
            statusChanged.putExtra(DEVICE_STATUS_NAME, deviceManager.getName());
        }
        sendBroadcast(statusChanged);

        switch (status) {
            case CONNECTED:
                synchronized (this) {
                    isConnected = true;
                }
                startBackgroundListener();
                break;
            case DISCONNECTED:
                synchronized (this) {
                    deviceScanner = null;
                    isConnected = false;
                }
                stopBackgroundListener();
                stopDeviceManager(deviceManager);
                int startId = -1;
                synchronized (this) {
                    if (numberOfActivitiesBound.get() == 0) {
                        startId = latestStartId;
                    }
                }
                if (startId != -1) {
                    stopSelf(startId);
                }
                break;
            default:
                // do nothing
                break;
        }
    }

    public void startBackgroundListener() {
        synchronized (this) {
            if (isInForeground) {
                return;
            }
            isInForeground = true;
        }
        Context context = getApplicationContext();
        Intent notificationIntent = new Intent(context, DeviceService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, 0);

        startForeground(ONGOING_NOTIFICATION_ID, createBackgroundNotification(pendingIntent));
    }

    protected Notification createBackgroundNotification(PendingIntent intent) {
        Notification.Builder notificationBuilder = new Notification.Builder(
                getApplicationContext());
        notificationBuilder
                .setContentIntent(intent)
                .setTicker(getText(R.string.service_notification_ticker))
                .setContentText(getText(R.string.service_notification_text))
                .setContentTitle(getText(R.string.service_notification_title));

        ((RadarApplication)getApplication()).updateNotificationAppSettings(notificationBuilder);

        return notificationBuilder.build();
    }

    public void stopBackgroundListener() {
        synchronized (this) {
            if (!isInForeground) {
                return;
            }
            isInForeground = false;
        }
        stopForeground(true);
    }

    private synchronized DeviceManager unsetDeviceManager() {
        DeviceManager tmpManager = deviceScanner;
        deviceScanner = null;
        return tmpManager;
    }

    private void stopDeviceManager(DeviceManager deviceManager) {
        if (deviceManager != null) {
            if (!deviceManager.isClosed()) {
                try {
                    deviceManager.close();
                } catch (IOException e) {
                    logger.warn("Failed to close device scanner", e);
                }
            }
            if (deviceManager.getState().getStatus() != DeviceStatusListener.Status.DISCONNECTED) {
                deviceStatusUpdated(deviceManager, DeviceStatusListener.Status.DISCONNECTED);
            }
        }
    }

    @Override
    public void updateServerStatus(ServerStatusListener.Status status) {
        Intent statusIntent = new Intent(SERVER_STATUS_CHANGED);
        statusIntent.putExtra(SERVER_STATUS_CHANGED, status.ordinal());
        statusIntent.putExtra(DEVICE_SERVICE_CLASS, getClass().getName());
        sendBroadcast(statusIntent);
    }

    @Override
    public void updateRecordsSent(String topicName, int numberOfRecords) {
        Intent recordsIntent = new Intent(SERVER_RECORDS_SENT_TOPIC);
        // Signal that a certain topic changed, the key of the map retrieved by getRecordsSent().
        recordsIntent.putExtra(SERVER_RECORDS_SENT_TOPIC, topicName);
        recordsIntent.putExtra(SERVER_RECORDS_SENT_NUMBER, numberOfRecords);
        recordsIntent.putExtra(DEVICE_SERVICE_CLASS, getClass().getName());
        sendBroadcast(recordsIntent);
    }

    /**
     * New device manager for the current device.
     */
    protected abstract DeviceManager createDeviceManager();

    /**
     * Default state when no device manager is active.
     */
    protected abstract BaseDeviceState getDefaultState();

    /** Kafka topics that this service will send data to. */
    protected abstract DeviceTopics getTopics();

    /**
     * Topics that should cache information. This implementation returns all topics in
     * getTopics().getTopics().
     */
    protected List<AvroTopic<MeasurementKey, ? extends SpecificRecord>> getCachedTopics() {
        return getTopics().getTopics();
    }

    public synchronized void setUserId(@NonNull String userId) {
        Objects.requireNonNull(userId);
        this.userId = userId;
        if (deviceScanner != null) {
            deviceScanner.getState().getId().setUserId(userId);
        }
    }

    public BaseDeviceState startRecording(@NonNull Set<String> acceptableIds) {
        DeviceManager localManager = getDeviceManager();
        if (getUserId() == null) {
            throw new IllegalStateException("Cannot start recording: user ID is not set.");
        }
        if (localManager == null) {
            logger.info("Starting recording");
            localManager = createDeviceManager();
            boolean didSet;
            synchronized (this) {
                if (deviceScanner == null) {
                    deviceScanner = localManager;
                    didSet = true;
                } else {
                    didSet = false;
                }
            }
            if (didSet) {
                localManager.start(acceptableIds);
            }
        }
        return getDeviceManager().getState();
    }

    public void stopRecording() {
        stopDeviceManager(unsetDeviceManager());
    }

    private class LocalBinder extends Binder implements DeviceServiceBinder {
        @Override
        public <V extends SpecificRecord> List<Record<MeasurementKey, V>> getRecords(
                @NonNull AvroTopic<MeasurementKey, V> topic, int limit) throws IOException {
            return getDataHandler().getCache(topic).getRecords(limit);
        }

        @Override
        public BaseDeviceState getDeviceStatus() {
            DeviceManager localManager = getDeviceManager();
            if (localManager == null) {
                return getDefaultState();
            } else {
                return localManager.getState();
            }
        }

        @Override
        public String getDeviceName() {
            DeviceManager localManager = getDeviceManager();
            if (localManager == null) {
                return null;
            } else {
                return localManager.getName();
            }
        }

        @Override
        public BaseDeviceState startRecording(@NonNull Set<String> acceptableIds) {
            return DeviceService.this.startRecording(acceptableIds);
        }

        @Override
        public void stopRecording() {
            DeviceService.this.stopRecording();
        }

        @Override
        public ServerStatusListener.Status getServerStatus() {
            return getDataHandler().getStatus();
        }

        @Override
        public Map<String,Integer> getServerRecordsSent() {
            return getDataHandler().getRecordsSent();
        }

        @Override
        public void updateConfiguration(Bundle bundle) {
            onInvocation(bundle);
        }

        @Override
        public Pair<Long, Long> numberOfRecords() {
            long unsent = -1L;
            long sent = -1L;
            for (DataCache<?, ?> cache : getDataHandler().getCaches().values()) {
                Pair<Long, Long> pair = cache.numberOfRecords();
                if (pair.first != -1L) {
                    if (unsent == -1L) {
                        unsent = pair.first;
                    } else {
                        unsent += pair.first;
                    }
                }
                if (pair.second != -1L) {
                    if (sent == -1L) {
                        sent = pair.second;
                    } else {
                        sent += pair.second;
                    }
                }
            }
            return new Pair<>(unsent, sent);
        }

        @Override
        public void setUserId(@NonNull String userId) {
            DeviceService.this.setUserId(userId);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Override this function to get any parameters from the given intent.
     * Also call the superclass.
     * @param bundle intent extras that the activity provided.
     */
    protected void onInvocation(Bundle bundle) {
        TableDataHandler localDataHandler;

        ServerConfig kafkaConfig = null;
        SchemaRetriever remoteSchemaRetriever = null;
        boolean unsafeConnection = RadarConfiguration.getBooleanExtra(bundle, UNSAFE_KAFKA_CONNECTION, false);
        if (RadarConfiguration.hasExtra(bundle, KAFKA_REST_PROXY_URL_KEY)) {
            String urlString = RadarConfiguration.getStringExtra(bundle, KAFKA_REST_PROXY_URL_KEY);
            if (!urlString.isEmpty()) {
                try {
                    ServerConfig schemaRegistry = new ServerConfig(RadarConfiguration.getStringExtra(bundle, SCHEMA_REGISTRY_URL_KEY));
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

        boolean sendOnlyWithWifi = RadarConfiguration.getBooleanExtra(
                bundle, SEND_ONLY_WITH_WIFI, true);

        AppAuthState authState = new AppAuthState(bundle);
        int maxBytes = RadarConfiguration.getIntExtra(
                bundle, MAX_CACHE_SIZE, Integer.MAX_VALUE);

        boolean newlyCreated;
        synchronized (this) {
            if (dataHandler == null) {
                try {
                    dataHandler = new TableDataHandler(
                            this, kafkaConfig, remoteSchemaRetriever, getCachedTopics(), maxBytes,
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
        localDataHandler.setCompression(RadarConfiguration.getBooleanExtra(
                bundle, SEND_WITH_COMPRESSION, false));

        if (RadarConfiguration.hasExtra(bundle, DATA_RETENTION_KEY)) {
            localDataHandler.setDataRetention(
                    RadarConfiguration.getLongExtra(bundle, DATA_RETENTION_KEY));
        }
        if (RadarConfiguration.hasExtra(bundle, KAFKA_UPLOAD_RATE_KEY)) {
            localDataHandler.setKafkaUploadRate(
                    RadarConfiguration.getLongExtra(bundle, KAFKA_UPLOAD_RATE_KEY));
        }
        if (RadarConfiguration.hasExtra(bundle, KAFKA_CLEAN_RATE_KEY)) {
            localDataHandler.setKafkaCleanRate(
                    RadarConfiguration.getLongExtra(bundle, KAFKA_CLEAN_RATE_KEY));
        }
        if (RadarConfiguration.hasExtra(bundle, KAFKA_RECORDS_SEND_LIMIT_KEY)) {
            localDataHandler.setKafkaRecordsSendLimit(
                    RadarConfiguration.getIntExtra(bundle, KAFKA_RECORDS_SEND_LIMIT_KEY));
        }
        if (RadarConfiguration.hasExtra(bundle, SENDER_CONNECTION_TIMEOUT_KEY)) {
            localDataHandler.setSenderConnectionTimeout(
                    RadarConfiguration.getLongExtra(bundle, SENDER_CONNECTION_TIMEOUT_KEY));
        }
        if (RadarConfiguration.hasExtra(bundle, DATABASE_COMMIT_RATE_KEY)) {
            localDataHandler.setDatabaseCommitRate(
                    RadarConfiguration.getLongExtra(bundle, DATABASE_COMMIT_RATE_KEY));
        }
        if (RadarConfiguration.hasExtra(bundle, DEFAULT_GROUP_ID_KEY)) {
            setUserId(RadarConfiguration.getStringExtra(bundle, DEFAULT_GROUP_ID_KEY));
        }
        if (RadarConfiguration.hasExtra(bundle, KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL)) {
            localDataHandler.setMinimumBatteryLevel(RadarConfiguration.getFloatExtra(bundle,
                    KAFKA_UPLOAD_MINIMUM_BATTERY_LEVEL));
        }

        if (newlyCreated) {
            localDataHandler.addStatusListener(this);
            localDataHandler.start();
        } else if (kafkaConfig != null) {
            localDataHandler.enableSubmitter();
        }
    }

    public synchronized TableDataHandler getDataHandler() {
        return dataHandler;
    }

    public synchronized DeviceManager getDeviceManager() {
        return deviceScanner;
    }

    /** Get the service local binder. */
    protected LocalBinder createBinder() {
        return new LocalBinder();
    }

    /** User ID to send data for. */
    public String getUserId() {
        return userId;
    }
}
