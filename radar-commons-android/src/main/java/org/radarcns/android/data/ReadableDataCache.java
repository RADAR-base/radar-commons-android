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

import android.support.annotation.Nullable;
import android.util.Pair;

import org.radarcns.data.RecordData;
import org.radarcns.topic.AvroTopic;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

public interface ReadableDataCache extends Closeable {
    /**
     * Get all unsent records in the cache.
     *
     * @return records or null if none are found.
     */
    @Nullable
    RecordData<Object, Object> unsentRecords(int limit, int sizeLimit) throws IOException;

    /**
     * Get latest records in the cache, from new to old.
     *
     * @return records or null if none are found.
     */
    @Nullable
    RecordData<Object, Object> getRecords(int limit) throws IOException;

    /**
     * Get a pair with the number of [unsent records], [sent records]
     */
    Pair<Long, Long> numberOfRecords();

    /**
     * Remove oldest records.
     * @param number number of records (inclusive) to remove.
     * @return number of rows removed
     */
    int remove(int number) throws IOException;

    /** Get the topic the cache stores. */
    AvroTopic<Object, Object> getReadTopic();

    File getFile();
}
