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

package org.radarcns.android.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.annotation.NonNull;

import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.kafka.KafkaDataSubmitter;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.android.util.AndroidThreadFactory;
import org.radarcns.android.util.AtomicFloat;
import org.radarcns.config.ServerConfig;
import org.radarcns.data.SpecificRecordEncoder;
import org.radarcns.key.MeasurementKey;
import org.radarcns.producer.SchemaRetriever;
import org.radarcns.producer.rest.RestSender;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import static android.content.Intent.ACTION_BATTERY_CHANGED;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.EXTRA_NO_CONNECTIVITY;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_SCALE;

/**
 * Stores data in databases and sends it to the server.
 */
public class TableDataHandler implements DataHandler<MeasurementKey, SpecificRecord> {
    private static final Logger logger = LoggerFactory.getLogger(TableDataHandler.class);

    public static final long DATA_RETENTION_DEFAULT = 86400000L;
    public static final long DATABASE_COMMIT_RATE_DEFAULT = 2500L;
    public static final int SEND_LIMIT_DEFAULT = 1000;
    public static final long CLEAN_RATE_DEFAULT = 3600L;
    public static final long UPLOAD_RATE_DEFAULT = 10L;
    public static final long SENDER_CONNECTION_TIMEOUT_DEFAULT = 10L;
    public static final float MINIMUM_BATTERY_LEVEL = 0.1f;

    private final Context context;
    private final ThreadFactory threadFactory;
    private final Map<AvroTopic<MeasurementKey, ? extends SpecificRecord>, DataCache<MeasurementKey, ? extends SpecificRecord>> tables;
    private final Set<ServerStatusListener> statusListeners;
    private final BroadcastReceiver connectivityReceiver;
    private ServerConfig kafkaConfig;
    private SchemaRetriever schemaRetriever;
    private int kafkaRecordsSendLimit;
    private final AtomicLong dataRetention;
    private long kafkaUploadRate;
    private long kafkaCleanRate;
    private long senderConnectionTimeout;
    private boolean dataIsConnected;
    private boolean batteryIsPlugged;
    private float batteryFactor;

    private ServerStatusListener.Status status;
    private Map<String, Integer> lastNumberOfRecordsSent = new TreeMap<>();
    private KafkaDataSubmitter<MeasurementKey, SpecificRecord> submitter;
    private RestSender<MeasurementKey, SpecificRecord> sender;
    private final AtomicFloat minimumBatteryLevel;

    /**
     * Create a data handler. If kafkaConfig is null, data will only be stored to disk, not uploaded.
     */
    public TableDataHandler(Context context, ServerConfig kafkaUrl, SchemaRetriever schemaRetriever,
                            List<AvroTopic<MeasurementKey, ? extends SpecificRecord>> topics)
            throws IOException {
        this.context = context;
        this.kafkaConfig = kafkaUrl;
        this.schemaRetriever = schemaRetriever;
        this.kafkaUploadRate = UPLOAD_RATE_DEFAULT;
        this.kafkaCleanRate = CLEAN_RATE_DEFAULT;
        this.kafkaRecordsSendLimit = SEND_LIMIT_DEFAULT;
        this.senderConnectionTimeout = SENDER_CONNECTION_TIMEOUT_DEFAULT;
        this.minimumBatteryLevel = new AtomicFloat(MINIMUM_BATTERY_LEVEL);

        tables = new HashMap<>(topics.size() * 2);
        for (AvroTopic<MeasurementKey, ? extends SpecificRecord> topic : topics) {
//            tables.put(topic, new MeasurementTable<>(context, topic, DATABASE_COMMIT_RATE_DEFAULT));
            tables.put(topic, new TapeCache<>(context, topic, DATABASE_COMMIT_RATE_DEFAULT));
        }
        dataRetention = new AtomicLong(DATA_RETENTION_DEFAULT);

        submitter = null;
        sender = null;

        statusListeners = new HashSet<>();
        this.threadFactory = new AndroidThreadFactory("DataHandler", android.os.Process.THREAD_PRIORITY_BACKGROUND);

        connectivityReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(CONNECTIVITY_ACTION)) {
                    updateNetworkStatus(intent);
                } else if (intent.getAction().equals(ACTION_BATTERY_CHANGED)) {
                    updateBatteryStatus(intent);
                } else {
                    return;
                }

                if (isStarted()) {
                    if (!dataIsConnected) {
                        logger.info("Network was disconnected, stopping data sending");
                        stop();
                    } else if (batteryFactor < minimumBatteryLevel.get() && !batteryIsPlugged) {
                        logger.info("Battery level getting low, stopping data sending");
                        stop();
                    }
                } else {
                    // Just try to start: the start method will not do anything if the parameters
                    // are not right.
                    start();
                }
            }
        };

        if (kafkaUrl != null) {
            doEnableSubmitter();
        } else {
            updateServerStatus(Status.DISABLED);
        }
    }

    private void updateBatteryStatus(Intent intent) {
        if (intent == null) {
            return;
        }
        int level = intent.getIntExtra(EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(EXTRA_SCALE, -1);

        batteryFactor = level / (float)scale;
        batteryIsPlugged = intent.getIntExtra(EXTRA_PLUGGED, 0) > 0;
    }

    private void updateNetworkStatus(Intent intent) {
        if (intent == null) {
            return;
        }
        dataIsConnected = !intent.getBooleanExtra(EXTRA_NO_CONNECTIVITY, false);
    }

    /**
     * Start submitting data to the server.
     *
     * This will not do anything if there is not already a submitter running, if it is disabled,
     * if the network is not connected or if the battery is running too low.
     */
    public synchronized void start() {
        if (isStarted()
                || status == Status.DISABLED
                || !dataIsConnected
                || (batteryFactor < minimumBatteryLevel.get() && !batteryIsPlugged)) {
            return;
        }

        updateServerStatus(Status.CONNECTING);
        this.sender = new RestSender<>(kafkaConfig, schemaRetriever,
                new SpecificRecordEncoder(false), new SpecificRecordEncoder(false),
                senderConnectionTimeout);
        this.submitter = new KafkaDataSubmitter<>(this, sender, threadFactory,
                kafkaRecordsSendLimit, kafkaUploadRate, kafkaCleanRate);
    }

    public synchronized boolean isStarted() {
        return submitter != null;
    }

    /**
     * Pause sending any data.
     * This waits for any remaining data to be sent.
     */
    public synchronized void stop() {
        if (submitter != null) {
            this.submitter.close();
            this.submitter = null;
            this.sender = null;
        }
        if (status == Status.DISABLED) {
            return;
        }
        updateServerStatus(Status.READY);
    }

    public synchronized void disableSubmitter() {
        if (status != Status.DISABLED) {
            updateServerStatus(Status.DISABLED);
            if (isStarted()) {
                stop();
            }
            context.unregisterReceiver(connectivityReceiver);
        }
    }

    public synchronized void enableSubmitter() {
        if (status == Status.DISABLED) {
            doEnableSubmitter();
            start();
        }
    }

    private void doEnableSubmitter() {
        dataIsConnected = false;
        batteryFactor = 1.0f;
        batteryIsPlugged = true;

        IntentFilter networkFilter = new IntentFilter(CONNECTIVITY_ACTION);
        updateNetworkStatus(context.registerReceiver(connectivityReceiver, networkFilter));

        IntentFilter batteryFilter = new IntentFilter(ACTION_BATTERY_CHANGED);
        updateBatteryStatus(context.registerReceiver(connectivityReceiver, batteryFilter));

        updateServerStatus(Status.READY);
    }

    /**
     * Sends any remaining data and closes the tables and connections.
     * @throws IOException if the tables cannot be flushed
     */
    public synchronized void close() throws IOException {
        if (status != Status.DISABLED) {
            context.unregisterReceiver(connectivityReceiver);
        }
        if (this.submitter != null) {
            try {
                this.submitter.close();
                this.submitter.join(5_000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                this.submitter = null;
                this.sender = null;
            }
        }
        clean();
        for (DataCache<MeasurementKey, ? extends SpecificRecord> table : tables.values()) {
            table.close();
        }
    }

    @Override
    public void clean() {
        long timestamp = (System.currentTimeMillis() - dataRetention.get());
        for (DataCache<MeasurementKey, ? extends SpecificRecord> table : tables.values()) {
            table.removeBeforeTimestamp(timestamp);
        }
    }

    public synchronized boolean trySend(AvroTopic topic, long offset, MeasurementKey deviceId, SpecificRecord record) {
        return submitter != null && submitter.trySend(topic, offset, deviceId, record);
    }

    /**
     * Check the connection with the server.
     *
     * Updates will be given to any listeners registered to
     * {@link #addStatusListener(ServerStatusListener)}.
     */
    public synchronized void checkConnection() {
        if (status == Status.DISABLED) {
            return;
        }
        if (submitter == null) {
            start();
        }
        if (submitter != null) {
            submitter.checkConnection();
        }
    }

    /**
     * Get the table of a given topic
     */
    @SuppressWarnings("unchecked")
    @Override
    public <V extends SpecificRecord> DataCache<MeasurementKey, V> getCache(AvroTopic<MeasurementKey, V> topic) {
        return (DataCache<MeasurementKey, V>)this.tables.get(topic);
    }

    @Override
    public <V extends SpecificRecord> void addMeasurement(DataCache<MeasurementKey, V> table, MeasurementKey key, V value) {
        table.addMeasurement(key, value);
    }

    public Map<AvroTopic<MeasurementKey, ? extends SpecificRecord>, DataCache<MeasurementKey, ? extends SpecificRecord>> getCaches() {
        return tables;
    }

    public void addStatusListener(ServerStatusListener listener) {
        synchronized (statusListeners) {
            statusListeners.add(listener);
        }
    }
    public void removeStatusListener(ServerStatusListener listener) {
        synchronized (statusListeners) {
            statusListeners.remove(listener);
        }
    }

    @Override
    public void updateServerStatus(ServerStatusListener.Status status) {
        synchronized (statusListeners) {
            for (ServerStatusListener listener : statusListeners) {
                listener.updateServerStatus(status);
            }
            this.status = status;
        }
    }

    public ServerStatusListener.Status getStatus() {
        synchronized (statusListeners) {
            return this.status;
        }
    }

    @Override
    public void updateRecordsSent(String topicName, int numberOfRecords) {
        synchronized (statusListeners) {
            for (ServerStatusListener listener : statusListeners) {
                listener.updateRecordsSent(topicName, numberOfRecords);
            }
            // Overwrite key-value if exists. Only stores the last
            this.lastNumberOfRecordsSent.put(topicName, numberOfRecords );

            if (numberOfRecords < 0 ) {
                String message = String.format(Locale.US, "%1$45s has FAILED uploading", topicName);
                logger.warn(message);
            } else {
                String message = String.format(Locale.US, "%1$45s uploaded %2$4d records",
                        topicName, numberOfRecords);
                logger.info(message);
            }
        }
    }

    public Map<String, Integer> getRecordsSent() {
        synchronized (statusListeners) {
            return this.lastNumberOfRecordsSent;
        }
    }

    public void setDatabaseCommitRate(long period) {
        for (DataCache<?, ?> table : tables.values()) {
            table.setTimeWindow(period);
        }
    }

    public synchronized void setKafkaRecordsSendLimit(int kafkaRecordsSendLimit) {
        if (submitter != null) {
            submitter.setSendLimit(kafkaRecordsSendLimit);
        }
        this.kafkaRecordsSendLimit = kafkaRecordsSendLimit;
    }

    public synchronized void setKafkaUploadRate(long kafkaUploadRate) {
        if (this.kafkaUploadRate == kafkaUploadRate) {
            return;
        }
        if (submitter != null) {
            submitter.setUploadRate(kafkaUploadRate);
        }
        this.kafkaUploadRate = kafkaUploadRate;
    }

    public synchronized void setKafkaCleanRate(long kafkaCleanRate) {
        if (submitter != null) {
            submitter.setCleanRate(kafkaCleanRate);
        }
        this.kafkaCleanRate = kafkaCleanRate;
    }

    public synchronized void setSenderConnectionTimeout(long senderConnectionTimeout) {
        if (sender != null) {
            sender.setConnectionTimeout(senderConnectionTimeout);
        }
        this.senderConnectionTimeout = senderConnectionTimeout;
    }

    public synchronized void setKafkaConfig(@NonNull ServerConfig kafkaUrl) {
        if (sender != null) {
            sender.setKafkaConfig(kafkaUrl);
        }
        this.kafkaConfig = kafkaUrl;
    }

    public synchronized void setSchemaRetriever(@NonNull SchemaRetriever schemaRetriever) {
        if (sender != null) {
            sender.setSchemaRetriever(schemaRetriever);
        }
        this.schemaRetriever = schemaRetriever;
    }

    public void setDataRetention(long dataRetention) {
        this.dataRetention.set(dataRetention);
    }

    public void setMinimumBatteryLevel(float minimumBatteryLevel) {
        this.minimumBatteryLevel.set(minimumBatteryLevel);
        start();
    }
}
