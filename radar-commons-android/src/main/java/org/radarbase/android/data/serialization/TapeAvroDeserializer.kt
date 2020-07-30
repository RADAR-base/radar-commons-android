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


package org.radarbase.android.data.serialization

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.io.BinaryDecoder
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DecoderFactory
import org.radarbase.data.Record
import org.radarbase.topic.AvroTopic
import org.radarbase.util.BackedObjectQueue

import java.io.IOException
import java.io.InputStream

/**
 * Converts records from an AvroTopic for Tape
 */
class TapeAvroDeserializer<K, V>(topic: AvroTopic<*, *>, private val avroData: GenericData) : BackedObjectQueue.Deserializer<Record<K, V>> {
    private val decoderFactory: DecoderFactory = DecoderFactory.get()
    private val keyReader: DatumReader<K>
    private val valueReader: DatumReader<V>
    private val topicName: String = topic.name
    private val keySchema: Schema = topic.keySchema
    private val valueSchema: Schema = topic.valueSchema
    private var decoder: BinaryDecoder? = null

    init {
        @Suppress("UNCHECKED_CAST")
        keyReader = avroData.createDatumReader(keySchema) as DatumReader<K>
        @Suppress("UNCHECKED_CAST")
        valueReader = avroData.createDatumReader(valueSchema) as DatumReader<V>
    }

    @Throws(IOException::class)
    override fun deserialize(input: InputStream): Record<K, V> {
        // for backwards compatibility
        input.skipFully(8L)

        decoder = decoderFactory.binaryDecoder(input, decoder)

        val key: K
        val value: V
        try {
            key = keyReader.read(null, decoder)
            value = valueReader.read(null, decoder)
        } catch (ex: RuntimeException) {
            throw IOException("Failed to deserialize object", ex)
        }

        require(avroData.validate(keySchema, key)
                && avroData.validate(valueSchema, value)) {
            "Failed to validate given record in topic $topicName\n\tkey: $key\n\tvalue: $value"
        }
        return Record(key, value)
    }

    companion object {
        fun InputStream.skipFully(n: Long) {
            var numRead = 0L
            do {
                numRead += skip(n - numRead)
            } while (numRead < n)
        }
    }
}
