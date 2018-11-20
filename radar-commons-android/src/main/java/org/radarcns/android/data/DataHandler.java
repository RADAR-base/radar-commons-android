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

import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.topic.AvroTopic;

import java.util.List;

public interface DataHandler<K, V> extends ServerStatusListener {

    /** Get all caches. */
    @NonNull
    List<ReadableDataCache> getCaches();

    /** Get caches currently active for sending. */
    @NonNull
    List<DataCacheGroup<?, ?>> getActiveCaches();

    /**
     * Add a measurement using given cache.
     * @param topic topic to add measurement to.
     * @param key key of the measurement
     * @param value value of the measurement.
     */
    <W extends V> void addMeasurement(@NonNull AvroTopic<K, W> topic, K key, W value);

    /**
     * Set maximum number of bytes a single cache may contain.
     * @param numBytes number of bytes
     */
    void setMaximumCacheSize(int numBytes);
}
