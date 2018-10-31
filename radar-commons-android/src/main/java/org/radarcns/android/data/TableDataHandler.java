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

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Pair;

import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.kafka.KafkaDataSubmitter;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.android.util.AtomicFloat;
import org.radarcns.android.util.BatteryLevelReceiver;
import org.radarcns.android.util.NetworkConnectedReceiver;
import org.radarcns.config.ServerConfig;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.producer.rest.RestClient;
import org.radarcns.producer.rest.RestSender;
import org.radarcns.producer.rest.SchemaRetriever;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.radarcns.android.device.DeviceService.CACHE_RECORDS_SENT_NUMBER;
import static org.radarcns.android.device.DeviceService.CACHE_RECORDS_UNSENT_NUMBER;
import static org.radarcns.android.device.DeviceService.CACHE_TOPIC;

/**
 * Stores data in databases and sends it to the server.
 */
public class TableDataHandler implements DataHandler<ObservationKey, SpecificRecord>, BatteryLevelReceiver.BatteryLevelListener, NetworkConnectedReceiver.NetworkConnectedListener {
    private static final Logger logger = LoggerFactory.getLogger(TableDataHandler.class);

    private static final long DATA_RETENTION_DEFAULT = 86400000L;
    private static final int SEND_LIMIT_DEFAULT = 1000;
    private static final long UPLOAD_RATE_DEFAULT = 10L;
    private static final long SENDER_CONNECTION_TIMEOUT_DEFAULT = 10L;
    private static final float MINIMUM_BATTERY_LEVEL = 0.1f;
    private static final float REDUCED_BATTERY_LEVEL = 0.2f;

    private final Map<String, DataCacheGroup<ObservationKey, ? extends SpecificRecord>> tables = new ConcurrentHashMap<>();
    private ServerStatusListener statusListener;
    private final Object STATUS_SYNC = new Object();
    private final BatteryLevelReceiver batteryLevelReceiver;
    private final NetworkConnectedReceiver networkConnectedReceiver;
    private final AtomicBoolean sendOnlyWithWifi;
    private final Context context;
    private final SpecificData specificData;
    private Handler handler;
    private final HandlerThread handlerThread;
    private int maxBytes;
    private AppAuthState authState;
    private ServerConfig kafkaConfig;
    private SchemaRetriever schemaRetriever;
    private boolean hasBinaryContent;
    private final AtomicBoolean sendOverDataHighPriority;
    private final Set<String> highPriorityTopics;
    private int kafkaRecordsSendLimit;
    private final AtomicLong dataRetention;
    private long kafkaUploadRate;
    private long senderConnectionTimeout;

    private ServerStatusListener.Status status;
    private final Map<String, Integer> lastNumberOfRecordsSent = new TreeMap<>();
    private KafkaDataSubmitter submitter;
    private RestSender sender;
    private final AtomicFloat minimumBatteryLevel;
    private boolean useCompression;

    /**
     * Create a data handler. If kafkaConfig is null, data will only be stored to disk, not uploaded.
     */
    public TableDataHandler(Context context, ServerConfig kafkaUrl, SchemaRetriever schemaRetriever,
                            int maxBytes, boolean sendOnlyWithWifi, boolean hasBinaryContent,
                            boolean sendOverDataHighPriority, Set<String> highPriorityTopics,
                            AppAuthState authState) {
        this.context =  context;
        this.kafkaConfig = kafkaUrl;
        this.schemaRetriever = schemaRetriever;
        this.hasBinaryContent = hasBinaryContent;
        this.sendOverDataHighPriority = new AtomicBoolean(sendOverDataHighPriority);
        this.highPriorityTopics = new HashSet<>(highPriorityTopics);
        this.kafkaUploadRate = UPLOAD_RATE_DEFAULT;
        this.kafkaRecordsSendLimit = SEND_LIMIT_DEFAULT;
        this.senderConnectionTimeout = SENDER_CONNECTION_TIMEOUT_DEFAULT;
        this.minimumBatteryLevel = new AtomicFloat(MINIMUM_BATTERY_LEVEL);
        this.handlerThread = new HandlerThread("TableDataHandler");
        this.handlerThread.start();
        this.handler = new Handler(handlerThread.getLooper());
        this.handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                for (ReadableDataCache cache : getCaches()) {
                    Pair<Long, Long> records = cache.numberOfRecords();
                    Intent numberCached = new Intent(CACHE_TOPIC);
                    numberCached.putExtra(CACHE_TOPIC, cache.getReadTopic().getName());
                    numberCached.putExtra(CACHE_RECORDS_UNSENT_NUMBER, records.first);
                    numberCached.putExtra(CACHE_RECORDS_SENT_NUMBER, records.second);
                    context.sendBroadcast(numberCached);
                }
                synchronized (TableDataHandler.this) {
                    if (handler != null) {
                        handler.postDelayed(this, 10_000);
                    }
                }
            }
        }, 10_000L);

        this.batteryLevelReceiver = new BatteryLevelReceiver(context, this);
        this.networkConnectedReceiver = new NetworkConnectedReceiver(context, this);
        this.sendOnlyWithWifi = new AtomicBoolean(sendOnlyWithWifi);
        this.useCompression = false;
        this.authState = authState;
        this.specificData = CacheStore.get().getSpecificData();

        dataRetention = new AtomicLong(DATA_RETENTION_DEFAULT);

        submitter = null;
        sender = null;

        statusListener = null;

        if (kafkaUrl != null) {
            doEnableSubmitter();
        } else {
            updateServerStatus(Status.DISABLED);
        }

        this.maxBytes = maxBytes;
    }

    private synchronized void updateUploadRate() {
        if (submitter != null) {
            submitter.setUploadRate(getPreferredUploadRate());
        }
    }

    private long getPreferredUploadRate() {
        if (batteryLevelReceiver.hasMinimumLevel(REDUCED_BATTERY_LEVEL)) {
            return kafkaUploadRate;
        } else {
            return kafkaUploadRate * 5;
        }
    }

    /**
     * Start submitting data to the server.
     *
     * This will not do anything if there is not already a submitter running, if it is disabled,
     * if the network is not connected or if the battery is running too low.
     */
    public synchronized void start() {
        if (isStarted()
                || authState == null
                || status == Status.DISABLED
                || !networkConnectedReceiver.hasConnection(sendOnlyWithWifi.get())
                || !batteryLevelReceiver.hasMinimumLevel(minimumBatteryLevel.get())) {
            return;
        }

        updateServerStatus(Status.CONNECTING);
        RestClient client = RestClient.global()
                .server(kafkaConfig)
                .gzipCompression(useCompression)
                .timeout(senderConnectionTimeout, TimeUnit.SECONDS)
                .build();
        this.sender = new RestSender.Builder()
                .httpClient(client)
                .schemaRetriever(schemaRetriever)
                .headers(authState.getOkHttpHeaders())
                .hasBinaryContent(hasBinaryContent)
                .build();
        this.submitter = new KafkaDataSubmitter(this, sender, kafkaRecordsSendLimit,
                getPreferredUploadRate(), authState.getUserId());
    }

    private synchronized boolean isStarted() {
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
        if (status != Status.DISABLED) {
            updateServerStatus(Status.READY);
        }
    }

    /** Do not submit any data, only cache it. If it is already disabled, this does nothing. */
    public synchronized void disableSubmitter() {
        if (status != Status.DISABLED) {
            updateServerStatus(Status.DISABLED);
            if (isStarted()) {
                stop();
            }
            networkConnectedReceiver.unregister();
            batteryLevelReceiver.unregister();
        }
    }

    /** Start submitting data. If it is already submitting data, this does nothing. */
    public synchronized void enableSubmitter() {
        if (status == Status.DISABLED) {
            doEnableSubmitter();
            start();
        }
    }

    private void doEnableSubmitter() {
        networkConnectedReceiver.register();
        batteryLevelReceiver.register();
        updateServerStatus(Status.READY);
    }

    /**
     * Sends any remaining data and closes the tables and connections.
     * @throws IOException if the tables cannot be flushed
     */
    public synchronized void close() throws IOException {
        handler = null;
        handlerThread.quitSafely();

        if (status != Status.DISABLED) {
            networkConnectedReceiver.unregister();
            batteryLevelReceiver.unregister();
        }
        if (this.submitter != null) {
            this.submitter.close();  // will also close sender
            this.submitter = null;
            this.sender = null;
        }
        for (DataCacheGroup<ObservationKey, ? extends SpecificRecord> table : tables.values()) {
            table.close();
        }
    }

    /**
     * Check the connection with the server.
     *
     * Updates will be given to any listeners registered to
     * {@link #setStatusListener(ServerStatusListener)}.
     */
    public synchronized void checkConnection() {
        if (status == Status.DISABLED) {
            return;
        }
        if (!isStarted()) {
            start();
        }
        if (isStarted()) {
            submitter.checkConnection();
        }
    }

    /**
     * Get the table of a given topic
     */
    @SuppressWarnings("unchecked")
    public <V extends SpecificRecord> DataCache<ObservationKey, V> getCache(String topic) {
        return (DataCache<ObservationKey, V>) tables.get(topic).getActiveDataCache();
    }

    @Override
    public <W extends SpecificRecord> void addMeasurement(AvroTopic<ObservationKey, W> topic, ObservationKey key, W value) {
        checkRecord(topic, key, value);
        getCache(topic.getName()).addMeasurement(key, value);
    }

    private void checkRecord(AvroTopic topic, ObservationKey key, SpecificRecord value) {
        if (!specificData.validate(topic.getKeySchema(), key)
                || !specificData.validate(topic.getValueSchema(), value)) {
            throw new IllegalArgumentException("Cannot send invalid record to topic " + topic
                    + " with {key: " + key + ", value: " + value + "}");
        }
    }

    @Override
    public void setMaximumCacheSize(int numBytes) {
        maxBytes = numBytes;
        for (DataCacheGroup<?, ?> cache : tables.values()) {
            cache.getActiveDataCache().setMaximumSize(numBytes);
        }
    }

    public List<ReadableDataCache> getCaches() {
        List<ReadableDataCache> caches = new ArrayList<>(tables.size());
        for (DataCacheGroup<?, ?> table : tables.values()) {
            caches.add(table.getActiveDataCache());
            caches.addAll(table.getDeprecatedCaches());
        }
        return caches;
    }

    public List<DataCacheGroup<?, ?>> getActiveCaches() {
        if (submitter == null) {
            return Collections.emptyList();
        } else if (networkConnectedReceiver.hasWifiOrEthernet() || !sendOverDataHighPriority.get()) {
            return new ArrayList<>(tables.values());
        } else {
            List<DataCacheGroup<?, ?>> filteredCaches = new ArrayList<>(tables.size());
            for (DataCacheGroup<?, ?> table : tables.values()) {
                if (highPriorityTopics.contains(table.getTopicName())) {
                    filteredCaches.add(table);
                }
            }
            return filteredCaches;
        }
    }

    /** Add a listener for ServerStatus updates. */
    public void setStatusListener(ServerStatusListener listener) {
        synchronized (STATUS_SYNC) {
            statusListener = listener;
        }
    }

    @Override
    public void updateServerStatus(ServerStatusListener.Status status) {
        synchronized (STATUS_SYNC) {
            if (statusListener != null) {
                statusListener.updateServerStatus(status);
            }
            this.status = status;
        }
    }

    /** Get the latest server status. */
    public ServerStatusListener.Status getStatus() {
        synchronized (STATUS_SYNC) {
            return this.status;
        }
    }

    @Override
    public void updateRecordsSent(String topicName, int numberOfRecords) {
        synchronized (STATUS_SYNC) {
            if (statusListener != null) {
                statusListener.updateRecordsSent(topicName, numberOfRecords);
            }
            // Overwrite key-value if exists. Only stores the last
            this.lastNumberOfRecordsSent.put(topicName, numberOfRecords );

            if (logger.isInfoEnabled()) {
                if (numberOfRecords < 0) {
                    String message = String.format(Locale.US, "%1$45s has FAILED uploading", topicName);
                    logger.warn(message);
                } else {
                    String message = String.format(Locale.US, "%1$45s uploaded %2$4d records",
                            topicName, numberOfRecords);
                    logger.info(message);
                }
            }
        }
    }

    public Map<String, Integer> getRecordsSent() {
        synchronized (STATUS_SYNC) {
            return this.lastNumberOfRecordsSent;
        }
    }

    public void setDatabaseCommitRate(long period) {
        for (DataCacheGroup<?, ?> table : tables.values()) {
            table.getActiveDataCache().setTimeWindow(period);
        }
    }

    public synchronized void setKafkaRecordsSendLimit(int kafkaRecordsSendLimit) {
        if (submitter != null) {
            submitter.setSendLimit(kafkaRecordsSendLimit);
        }
        this.kafkaRecordsSendLimit = kafkaRecordsSendLimit;
    }

    public synchronized void setKafkaUploadRate(long kafkaUploadRate) {
        this.kafkaUploadRate = kafkaUploadRate;
        updateUploadRate();
    }

    public synchronized void setAuthState(AppAuthState state) {
        boolean authStateWasNull = this.authState == null;
        this.authState = state;
        logger.info("Updating data handler auth state to {}", authState.getOkHttpHeaders());
        if (sender != null) {
            sender.setHeaders(authState.getOkHttpHeaders());
        }
        if (submitter != null) {
            submitter.setUserId(authState.getUserId());
        }
        if (authStateWasNull) {
            start();
        }
    }

    public synchronized void setHasBinaryContent(boolean hasBinaryContent) {
        if (this.hasBinaryContent == hasBinaryContent) {
            return;
        }
        this.hasBinaryContent = hasBinaryContent;
        if (sender != null) {
            if (hasBinaryContent) {
                sender.useLegacyEncoding(RestSender.KAFKA_REST_ACCEPT_ENCODING, RestSender.KAFKA_REST_BINARY_ENCODING, true);
            } else {
                sender.useLegacyEncoding(RestSender.KAFKA_REST_ACCEPT_ENCODING, RestSender.KAFKA_REST_AVRO_ENCODING, false);
            }
        }
    }

    public synchronized void setSenderConnectionTimeout(long senderConnectionTimeout) {
        if (sender != null) {
            sender.setConnectionTimeout(senderConnectionTimeout, TimeUnit.SECONDS);
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

    public synchronized void setCompression(boolean useCompression) {
        if (sender != null) {
            sender.setCompression(useCompression);
        }
        this.useCompression = useCompression;
    }

    public void setDataRetention(long dataRetention) {
        this.dataRetention.set(dataRetention);
    }

    public void setMinimumBatteryLevel(float minimumBatteryLevel) {
        this.minimumBatteryLevel.set(minimumBatteryLevel);
        start();
    }

    public void setSendOnlyWithWifi(boolean sendOnlyWithWifi) {
        this.sendOnlyWithWifi.set(sendOnlyWithWifi);
        // trigger network connection setting update
        this.onNetworkConnectionChanged(
                networkConnectedReceiver.isConnected(),
                networkConnectedReceiver.hasWifiOrEthernet());
    }

    @Override
    public void onNetworkConnectionChanged(boolean isConnected, boolean hasWifiOrEthernet) {
        if (isStarted()) {
            if (!isConnected || (!hasWifiOrEthernet && sendOnlyWithWifi.get())) {
                logger.info("Network was disconnected, stopping data sending");
                stop();
            }
        } else {
            // Just try to start: the start method will not do anything if the parameters
            // are not right.
            start();
        }
    }

    @Override
    public void onBatteryLevelChanged(float level, boolean isPlugged) {
        if (isStarted()) {
            if (level < minimumBatteryLevel.get() && !isPlugged) {
                logger.info("Battery level getting low, stopping data sending");
                stop();
            } else {
                updateUploadRate();
            }
        } else {
            // Just try to start: the start method will not do anything if the parameters
            // are not right.
            start();
        }
    }

    public void registerTopic(AvroTopic<ObservationKey, ? extends SpecificRecord> topic) throws IOException {
        if (tables.containsKey(topic.getName())) {
            return;
        }
        DataCacheGroup<ObservationKey, ? extends SpecificRecord> cache = CacheStore.get()
                .getOrCreateCaches(context.getApplicationContext(), topic);
        cache.getActiveDataCache().setMaximumSize(maxBytes);
        tables.put(topic.getName(), cache);
    }

    public synchronized void setTopicsHighPriority(Set<String> topicsHighPriority) {
        if (!topicsHighPriority.equals(this.highPriorityTopics)) {
            this.highPriorityTopics.clear();
            this.highPriorityTopics.addAll(topicsHighPriority);
        }
    }

    public void setSendOverDataHighPriority(boolean sendOverDataHighPriority) {
        this.sendOverDataHighPriority.set(sendOverDataHighPriority);
    }
}
