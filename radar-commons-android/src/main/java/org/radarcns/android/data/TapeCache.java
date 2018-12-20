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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import com.crashlytics.android.Crashlytics;

import org.apache.avro.generic.GenericData;
import org.radarcns.android.util.SingleThreadExecutorFactory;
import org.radarcns.data.AvroRecordData;
import org.radarcns.data.Record;
import org.radarcns.data.RecordData;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.BackedObjectQueue;
import org.radarcns.util.QueueFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.radarcns.android.kafka.KafkaDataSubmitter.SIZE_LIMIT_DEFAULT;

/**
 * Caches measurement on a BackedObjectQueue. Internally, all data is first cached on a local queue,
 * before being written in batches to the BackedObjectQueue, using a single-threaded
 * ExecutorService. Data is retrieved and removed from the queue in a blocking way using that same
 * ExecutorService. Sent messages are not kept, they are immediately removed.
 *
 * @param <K> measurement key type
 * @param <V> measurement value type
 */
public class TapeCache<K, V> implements DataCache<K, V> {
    private static final Logger logger = LoggerFactory.getLogger(TapeCache.class);

    private final AvroTopic<K, V> topic;
    private final ScheduledExecutorService executor;
    private final List<Record<K, V>> measurementsToAdd;
    private final File file;
    private final BackedObjectQueue.Serializer<Record<K, V>> serializer;
    private final BackedObjectQueue.Deserializer<Record<Object, Object>> deserializer;
    private final int maxBytes;
    private final AvroTopic<Object, Object> outputTopic;
    private QueueFile queueFile;

    private BackedObjectQueue<Record<K, V>, Record<Object, Object>> queue;
    private Future<?> addMeasurementFuture;
    private long timeWindowMillis;

    private final AtomicLong queueSize;

    /**
     * TapeCache to cache measurements with
     * @param topic Kafka Avro topic to write data for.
     * @param executorFactory factory to get a single-threaded {@link ScheduledExecutorService}
     *                        from.
     * @throws IOException if a BackedObjectQueue cannot be created.
     */
    public TapeCache(@NonNull File file, @NonNull AvroTopic<K, V> topic,
                     @NonNull AvroTopic<Object, Object> outputTopic,
                     @NonNull SingleThreadExecutorFactory executorFactory,
                     @NonNull GenericData inputFormat,
                     @NonNull GenericData outputFormat)
            throws IOException {
        this.topic = Objects.requireNonNull(topic);
        this.outputTopic = Objects.requireNonNull(outputTopic);
        this.timeWindowMillis = 10_000L;
        this.maxBytes = 450_000_000;
        this.file = file;
        try {
            queueFile = QueueFile.newMapped(Objects.requireNonNull(file), maxBytes);
        } catch (IOException ex) {
            logger.error("TapeCache " + file + " was corrupted. Removing old cache.");
            if (file.delete()) {
                queueFile = QueueFile.newMapped(file, maxBytes);
            } else {
                throw ex;
            }
        }
        this.queueSize = new AtomicLong(queueFile.size());

        this.executor = Objects.requireNonNull(executorFactory).getScheduledExecutorService();

        this.measurementsToAdd = new ArrayList<>();

        this.serializer = new TapeAvroSerializer<>(topic, Objects.requireNonNull(inputFormat));
        this.deserializer = new TapeAvroDeserializer<>(outputTopic, Objects.requireNonNull(outputFormat));
        this.queue = new BackedObjectQueue<>(queueFile, serializer, deserializer);
    }

    @Override
    public RecordData<Object, Object> unsentRecords(final int limit, final int sizeLimit)
            throws IOException {
        logger.debug("Trying to retrieve records from topic {}", topic);
        try {
            return executor.submit(() -> {
                try {
                    List<Record<Object, Object>> records = queue.peek(limit, sizeLimit);
                    if (records.isEmpty()) {
                        return null;
                    }
                    Object currentKey = records.get(0).key;
                    List<Object> values = new ArrayList<>(records.size());
                    for (Record<Object, Object> record : records) {
                        if (!currentKey.equals(record.key)) {
                            break;
                        }
                        values.add(record.value);
                    }
                    return new AvroRecordData<>(outputTopic, currentKey, values);
                } catch (IOException | IllegalStateException ex) {
                    fixCorruptQueue();
                    return null;
                }
            }).get();
        } catch (InterruptedException ex) {
            logger.warn("unsentRecords was interrupted, returning an empty list", ex);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException ex) {
            logger.warn("Failed to retrieve records for topic {}", topic, ex);
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else {
                throw new IOException("Unknown error occurred", ex);
            }
        }
    }

    @Override
    public RecordData<Object, Object> getRecords(int limit) throws IOException {
        return unsentRecords(limit, SIZE_LIMIT_DEFAULT);
    }

    @NonNull
    @Override
    public Pair<Long, Long> numberOfRecords() {
        return new Pair<>(queueSize.get(), 0L);
    }

    @Override
    public synchronized void setTimeWindow(long timeWindowMillis) {
        this.timeWindowMillis = timeWindowMillis;
    }

    @Override
    public void setMaximumSize(final int numBytes) {
        try {
            executor.submit(() -> queueFile.setMaximumFileSize(numBytes)).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Failed to update maximum size");
        }
    }

    @Override
    public int remove(final int number) throws IOException {
        try {
            return executor.submit(() -> {
                int actualNumber = Math.min(number, queue.size());
                if (actualNumber == 0) {
                    return 0;
                }
                logger.debug("Removing {} records from topic {}", actualNumber, topic.getName());
                queue.remove(actualNumber);
                queueSize.addAndGet(-actualNumber);
                return actualNumber;
            }).get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("Failed to mark record as sent. May resend those at a later time.", ex);
            return 0;
        } catch (ExecutionException ex) {
            logger.warn("Failed to mark sent records for topic {}", topic, ex);
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) {
                return 0;
            } else if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new IOException("Unknown error occurred", ex);
            }
        }
    }

    @Override
    public synchronized void addMeasurement(@Nullable final K key, @Nullable final V value) {
        measurementsToAdd.add(new Record<>(key, value));

        if (addMeasurementFuture == null) {
            addMeasurementFuture = executor.schedule(this::doFlush,
                    timeWindowMillis, TimeUnit.MILLISECONDS);
        }
    }

    @NonNull
    @Override
    public AvroTopic<Object, Object> getReadTopic() {
        return outputTopic;
    }

    @NonNull
    @Override
    public File getFile() {
        return file;
    }

    @NonNull
    @Override
    public AvroTopic<K, V> getTopic() {
        return topic;
    }

    @Override
    public void close() throws IOException {
        flush();
        queue.close();
    }

    @Override
    public void flush() {
        Future<?> flushFuture;
        synchronized (this) {
            if (addMeasurementFuture != null) {
                addMeasurementFuture.cancel(false);
                addMeasurementFuture = null;
            }
            // no measurements in cache
            if (measurementsToAdd.isEmpty()) {
                return;
            }
            flushFuture = executor.submit(this::doFlush);
        }
        try {
            flushFuture.get();
        } catch (InterruptedException e) {
            logger.warn("Did not wait for adding measurements to complete.");
        } catch (ExecutionException ex) {
            logger.warn("Failed to execute flush task", ex);
        }
    }

    private void doFlush() {
        List<Record<K, V>> localList;

        synchronized (this) {
            addMeasurementFuture = null;
            localList = new ArrayList<>(measurementsToAdd);
            measurementsToAdd.clear();
        }

        try {
            logger.info("Writing {} records to file in topic {}", localList.size(), topic.getName());
            queue.addAll(localList);
            queueSize.addAndGet(localList.size());
        } catch (IOException ex) {
            logger.error("Failed to add records", ex);
            queueSize.set(queue.size());
            throw new RuntimeException(ex);
        } catch (IllegalStateException ex) {
            logger.error("Queue {} is full, not adding records", topic.getName());
            queueSize.set(queue.size());
        } catch (IllegalArgumentException ex) {
            logger.error("Failed to validate all records; adding individual records instead: {}", ex.getMessage());
            try {
                logger.info("Writing {} records to file in topic {}", localList.size(), topic.getName());
                for (Record<K, V> record : localList) {
                    try {
                        queue.add(record);
                    } catch (IllegalArgumentException ex2) {
                        Crashlytics.logException(ex2);
                    }
                }
                queueSize.addAndGet(localList.size());
            } catch (IllegalStateException illEx) {
                logger.error("Queue {} is full, not adding records", topic.getName());
                queueSize.set(queue.size());
            } catch (IOException ex2) {
                logger.error("Failed to add record", ex);
                queueSize.set(queue.size());
                throw new RuntimeException(ex);
            }
        }
    }

    private void fixCorruptQueue() throws IOException {
        logger.error("Queue {} was corrupted. Removing cache.", topic.getName());
        try {
            queue.close();
        } catch (IOException ioex) {
            logger.warn("Failed to close corrupt queue", ioex);
        }
        if (file.delete()) {
            queueFile = QueueFile.newMapped(file, maxBytes);
            queueSize.set(queueFile.size());
            queue = new BackedObjectQueue<>(queueFile, serializer, deserializer);
        } else {
            throw new IOException("Cannot create new cache.");
        }
    }
}
