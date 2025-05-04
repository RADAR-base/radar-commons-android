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

package org.radarbase.android.data

import kotlinx.coroutines.flow.StateFlow
import org.apache.avro.Schema
import org.radarbase.android.data.serialization.SerializationFactory
import org.radarbase.data.RecordData
import org.radarbase.topic.AvroTopic
import java.io.File
import java.io.IOException

interface ReadableDataCache {
    /** Get the topic the cache stores.  */
    val readTopic: AvroTopic<Any, Any>
    val serialization: SerializationFactory
    val readUserIdField: Schema.Field?

    val file: File
    /**
     * Get unsent records from the cache.
     *
     * @param limit maximum number of records.
     * @param sizeLimit maximum serialized size of those records.
     * @return records or null if none are found.
     */
    @Throws(IOException::class)
    suspend fun getUnsentRecords(limit: Int, sizeLimit: Long): RecordData<Any, Any>?

    /**
     * Get latest records in the cache, from new to old.
     *
     * @return records or null if none are found.
     */
    @Throws(IOException::class)
    suspend fun getRecords(limit: Int): RecordData<Any, Any>?

    /**
     * Number of unsent records in cache.
     */
    val numberOfRecords: StateFlow<Long>

    /**
     * Remove oldest records.
     * @param number number of records (inclusive) to remove.
     */
    @Throws(IOException::class)
    suspend fun remove(number: Int)

    suspend fun stop()
}
