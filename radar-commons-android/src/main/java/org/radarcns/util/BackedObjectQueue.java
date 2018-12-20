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

package org.radarcns.util;

import com.crashlytics.android.Crashlytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A queue-like object queue that is backed by a file storage.
 * @param <S> type of objects to store.
 * @param <T> type of objects to retrieve.
 */
public class BackedObjectQueue<S, T> implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(BackedObjectQueue.class);

    private final Serializer<S> serializer;
    private final Deserializer<T> deserializer;
    private final QueueFile queueFile;

    /**
     * Creates a new object queue from given file.
     * @param queueFile file to write objects to
     * @param serializer way to serialize from given objects
     * @param deserializer way to deserialize to objects from a stream
     */
    public BackedObjectQueue(QueueFile queueFile, Serializer<S> serializer,
                             Deserializer<T> deserializer) {
        this.queueFile = queueFile;
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    /** Number of elements in the queue. */
    public int size() {
        return this.queueFile.size();
    }

    /**
     * Add a new element to the queue.
     * @param entry element to add
     * @throws IOException if the backing file cannot be accessed, or the element
     * cannot be converted.
     * @throws IllegalArgumentException if given entry is not a valid object for serialization.
     * @throws IllegalStateException if the queue is full
     */
    public void add(S entry) throws IOException {
        try (QueueFileOutputStream out = queueFile.elementOutputStream()) {
            serializer.serialize(entry, out);
        }
    }

    /**
     * Add a collection of new element to the queue.
     * @param entries elements to add
     * @throws IOException if the backing file cannot be accessed or the element
     *                     cannot be converted.
     * @throws IllegalArgumentException if given entry is not a valid object for serialization.
     * @throws IllegalStateException if the queue is full
     */
    public void addAll(Collection<? extends S> entries) throws IOException {
        try (QueueFileOutputStream out = queueFile.elementOutputStream()) {
            for (S entry : entries) {
                serializer.serialize(entry, out);
                out.next();
            }
        }
    }

    /**
     * Get the front-most object in the queue. This does not remove the element.
     * @return front-most element or null if none is available
     * @throws IOException if the element could not be read or deserialized
     * @throws IllegalStateException if the element that was read was invalid.
     */
    public T peek() throws IOException {
        try (InputStream in = queueFile.peek()) {
            return deserializer.deserialize(in);
        }
    }

    /**
     * Get at most {@code n} front-most objects in the queue. This does not remove the elements.
     * Elements that were found to be invalid according to the current schema are logged. This
     * method will try to read at least one record. After that, no more than {@code n} records are
     * read, and their collective serialized size is no larger than {@code sizeLimit}.
     * @param n number of elements to retrieve at most.
     * @param sizeLimit limit for the size of read data.
     * @return list of elements, with at most {@code n} elements.
     * @throws IOException if the element could not be read or deserialized
     * @throws IllegalStateException if the element could not be read
     */
    public List<T> peek(int n, int sizeLimit) throws IOException {
        Iterator<InputStream> iter = queueFile.iterator();
        int curSize = 0;
        List<T> results = new ArrayList<>(n);
        for (int i = 0; i < n && iter.hasNext(); i++) {
            try (InputStream in = iter.next()) {
                curSize += in.available();
                if (i > 0 && curSize > sizeLimit) {
                    break;
                }
                try {
                    results.add(deserializer.deserialize(in));
                } catch (IllegalStateException ex) {
                    Crashlytics.logException(ex);
                    logger.warn("Invalid record ignored: {}", ex.getMessage());
                    results.add(null);
                }
            }
        }
        return results;
    }

    /**
     * Remove the first element from the queue.
     * @throws IOException when the element could not be removed
     * @throws NoSuchElementException if more than the available elements are requested to be removed
     */
    public void remove() throws IOException {
        remove(1);
    }

    /**
     * Remove the first {@code n} elements from the queue.
     *
     * @throws IOException when the elements could not be removed
     * @throws NoSuchElementException if more than the available elements are requested to be removed
     */
    public void remove(int n) throws IOException {
        queueFile.remove(n);
    }

    /** Returns {@code true} if this queue contains no entries. */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Close the queue. This also closes the backing file.
     * @throws IOException if the file cannot be closed.
     */
    @Override
    public void close() throws IOException {
        queueFile.close();
    }

    /** Converts objects into streams. */
    public interface Serializer<S> {
        /**
         * Serialize an object to given output stream.
         * @param out output, which will not be closed after this call.
         * @throws IOException if a valid object could not be serialized to the stream
         * @throws IllegalStateException if the underlying queue is full.
         */
        void serialize(S value, OutputStream out) throws IOException;
    }

    /** Converts streams into objects. */
    public interface Deserializer<T> {
        /**
         * Deserialize an object from given input stream.
         * @param in input, which will not be closed after this call.
         * @return deserialized object
         * @throws IOException if a valid object could not be deserialized from the stream
         */
        T deserialize(InputStream in) throws IOException;
    }
}
