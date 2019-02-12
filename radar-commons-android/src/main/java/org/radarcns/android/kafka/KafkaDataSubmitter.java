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

package org.radarcns.android.kafka;

import android.support.annotation.NonNull;

import org.apache.avro.Schema;
import org.apache.avro.SchemaValidationException;
import org.apache.avro.generic.IndexedRecord;
import org.radarcns.android.data.DataCacheGroup;
import org.radarcns.android.data.DataHandler;
import org.radarcns.android.data.ReadableDataCache;
import org.radarcns.android.util.SafeHandler;
import org.radarcns.data.RecordData;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.KafkaSender;
import org.radarcns.producer.KafkaTopicSender;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

/**
 * Separate thread to read from the database and send it to the Kafka server. It cleans the
 * database.
 *
 * It uses a set of timers to addMeasurement data and clean the databases.
 */
public class KafkaDataSubmitter implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaDataSubmitter.class);
    public static final int SIZE_LIMIT_DEFAULT = 5_000_000;  // 5 MB

    private final DataHandler<?, ?> dataHandler;
    private final KafkaSender sender;
    private final Map<String, KafkaTopicSender<Object, Object>> topicSenders;
    private final KafkaConnectionChecker connection;
    private final AtomicInteger sendLimit;
    private final SafeHandler mHandler;

    private SafeHandler.HandlerFuture uploadFuture;
    private SafeHandler.HandlerFuture uploadIfNeededFuture;
    /** Upload rate in milliseconds. */
    private long uploadRate;
    private String userId;
    private final AtomicInteger sizeLimit;

    public KafkaDataSubmitter(@NonNull DataHandler<?, ?> dataHandler, @NonNull
            KafkaSender sender, int sendLimit, long uploadRate, String userId) {
        this.dataHandler = dataHandler;
        this.sender = sender;
        this.userId = userId;
        topicSenders = new HashMap<>();
        this.sendLimit = new AtomicInteger(sendLimit);
        this.sizeLimit = new AtomicInteger(SIZE_LIMIT_DEFAULT);

        mHandler = new SafeHandler("data-submitter", THREAD_PRIORITY_BACKGROUND);
        mHandler.start();

        logger.debug("Started data submission executor");

        connection = new KafkaConnectionChecker(sender, mHandler, dataHandler, uploadRate * 5);

        mHandler.post(() -> {
            try {
                if (KafkaDataSubmitter.this.sender.isConnected()) {
                    KafkaDataSubmitter.this.dataHandler.updateServerStatus(ServerStatusListener.Status.CONNECTED);
                    connection.didConnect();
                } else {
                    KafkaDataSubmitter.this.dataHandler.updateServerStatus(ServerStatusListener.Status.DISCONNECTED);
                    connection.didDisconnect(null);
                }
            } catch (AuthenticationException ex) {
                connection.didDisconnect(ex);
            }
        });

        synchronized (this) {
            uploadFuture = null;
            uploadIfNeededFuture = null;
            setUploadRate(uploadRate);
        }
    }

    /** Set upload rate in seconds. */
    public final synchronized void setUploadRate(long period) {
        long newUploadRate = period * 1000L;
        if (this.uploadRate == newUploadRate) {
            return;
        }
        this.uploadRate = newUploadRate;
        if (uploadFuture != null) {
            uploadFuture.cancel();
            uploadIfNeededFuture.cancel();
        }
        // Get upload frequency from system property
        uploadFuture = mHandler.postDelayed(() -> {
            final Set<String> topicsToSend = new HashSet<>();
            for (DataCacheGroup<?, ?> group : dataHandler.getActiveCaches()) {
                topicsToSend.add(group.getTopicName());
            }
            while (connection.isConnected() && !topicSenders.isEmpty()) {
                uploadCaches(topicsToSend);
            }
            return true;
        }, uploadRate);

        uploadIfNeededFuture = mHandler.postDelayed(() -> {
            while (connection.isConnected()) {
                boolean sendAgain = uploadCachesIfNeeded();
                if (!sendAgain) {
                    break;
                }
            }
            return true;
        }, uploadRate / 5);
    }

    public void setSizeLimit(int limit) {
        sizeLimit.set(limit);
    }

    public void setSendLimit(int limit) {
        sendLimit.set(limit);
    }

    /**
     * Close the submitter eventually. This does not flush any caches.
     */
    @Override
    public synchronized void close() {
        mHandler.stop(() -> {
            for (Map.Entry<String, KafkaTopicSender<Object, Object>> topicSender : topicSenders.entrySet()) {
                try {
                    topicSender.getValue().close();
                } catch (IOException e) {
                    logger.warn("failed to stop topicSender for topic {}", topicSender.getKey(), e);
                }
            }
            topicSenders.clear();

            try {
                sender.close();
            } catch (IOException e1) {
                logger.warn("failed to addMeasurement latest batches", e1);
            }
        });
    }

    /** Get a sender for a topic. Per topic, only ONE thread may use this. */
    private KafkaTopicSender<Object, Object> sender(AvroTopic<Object, Object> topic) throws IOException, SchemaValidationException {
        KafkaTopicSender<Object, Object> topicSender = topicSenders.get(topic.getName());
        if (topicSender == null) {
            topicSender = sender.sender(topic);
            topicSenders.put(topic.getName(), topicSender);
        }
        return topicSender;
    }

    /**
     * Check the connection status eventually.
     */
    public void checkConnection() {
        connection.check();
    }

    /**
     * Upload the caches if they would cause the buffer to overflow
     */
    private boolean uploadCachesIfNeeded() {
        boolean uploadingNotified = false;
        int currentSendLimit = sendLimit.get();
        int currentSizeLimit = sizeLimit.get();
        boolean sendAgain = false;

        try {
            for (DataCacheGroup<?, ?> entry : dataHandler.getActiveCaches()) {
                long unsent = entry.getActiveDataCache().numberOfRecords().first;
                if (unsent > currentSendLimit) {
                    //noinspection unchecked
                    int sent = uploadCache(entry.getActiveDataCache(),
                            currentSendLimit, currentSizeLimit, uploadingNotified);
                    if (!uploadingNotified) {
                        uploadingNotified = true;
                    }
                    if (!sendAgain && unsent - sent > currentSendLimit && sent != -1) {
                        sendAgain = true;
                    }
                }
            }
            if (uploadingNotified) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.CONNECTED);
                connection.didConnect();
            }
        } catch (IOException | SchemaValidationException ex) {
            connection.didDisconnect(ex);
            sendAgain = false;
        }
        return sendAgain;
    }

    /**
     * Upload a limited amount of data stored in the database which is not yet sent.
     */
    private void uploadCaches(Set<String> toSend) {
        boolean uploadingNotified = false;
        int currentSendLimit = sendLimit.get();
        int currentSizeLimit = sizeLimit.get();
        try {
            for (DataCacheGroup<?, ?> entry : dataHandler.getActiveCaches()) {
                if (!toSend.contains(entry.getTopicName())) {
                    continue;
                }
                boolean hasFullCache = false;
                int sent = uploadCache(entry.getActiveDataCache(), currentSendLimit,
                        currentSizeLimit, uploadingNotified);
                if (sent == currentSendLimit) {
                    hasFullCache = true;
                }
                if (!uploadingNotified && sent > 0) {
                    uploadingNotified = true;
                }

                boolean hasEmptyDeprecatedCache = false;
                for (ReadableDataCache cache : entry.getDeprecatedCaches()) {
                    sent = uploadCache(cache, currentSendLimit, currentSizeLimit, uploadingNotified);
                    if (sent == currentSendLimit) {
                        hasFullCache = true;
                    } else {
                        hasEmptyDeprecatedCache = true;
                    }
                    if (!uploadingNotified && sent > 0) {
                        uploadingNotified = true;
                    }
                }
                if (hasEmptyDeprecatedCache) {
                    entry.deleteEmptyCaches();
                }
                if (!hasFullCache) {
                    toSend.remove(entry.getTopicName());
                }
            }
            if (uploadingNotified) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.CONNECTED);
                connection.didConnect();
            }
        } catch (IOException | SchemaValidationException ex) {
            connection.didDisconnect(ex);
        }
    }

    /**
     * Upload some data from a single table.
     * @return number of records sent.
     */
    private int uploadCache(ReadableDataCache cache, int limit, int sizeLimit,
                            boolean uploadingNotified) throws IOException, SchemaValidationException {
        RecordData<Object, Object> data = cache.unsentRecords(limit, sizeLimit);
        if (data == null) {
            return 0;
        }

        int size = data.size();

        AvroTopic<Object, Object> topic = cache.getReadTopic();
        String keyUserId = null;

        if (topic.getKeySchema().getType() == Schema.Type.RECORD) {
            Schema.Field userIdField = topic.getKeySchema().getField("userId");
            if (userIdField != null) {
                keyUserId = ((IndexedRecord)data.getKey()).get(userIdField.pos()).toString();
            }
        }
        if (keyUserId == null || keyUserId.equals(userId)) {
            KafkaTopicSender<Object, Object> cacheSender = sender(topic);

            if (!uploadingNotified) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.UPLOADING);
            }

            try {
                cacheSender.send(data);
                cacheSender.flush();
            } catch (AuthenticationException ex) {
                dataHandler.updateRecordsSent(topic.getName(), -1);
                throw ex;
            } catch (IOException | SchemaValidationException e) {
                dataHandler.updateServerStatus(ServerStatusListener.Status.UPLOADING_FAILED);
                dataHandler.updateRecordsSent(topic.getName(), -1);
                throw e;
            }

            dataHandler.updateRecordsSent(topic.getName(), size);

            logger.debug("uploaded {} {} records", size, topic.getName());
        }

        cache.remove(size);

        return size;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
