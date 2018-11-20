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

import org.radarcns.topic.AvroTopic;

import java.io.Flushable;

public interface DataCache<K, V> extends Flushable, ReadableDataCache {
    /** Add a new measurement to the cache. */
    void addMeasurement(@Nullable K key, @Nullable V value);

    /** Get the topic the cache stores. */
    @NonNull
    AvroTopic<K, V> getTopic();

    /** Set the time until data is committed to disk. */
    void setTimeWindow(long period);

    /** Set the maximum size the data cache may have in bytes. */
    void setMaximumSize(int bytes);
}
