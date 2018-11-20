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

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.radarcns.data.Record;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.BackedObjectQueue;

import java.io.IOException;
import java.io.InputStream;

/**
 * Converts records from an AvroTopic for Tape
 */
public class TapeAvroDeserializer<K, V>
        implements BackedObjectQueue.Deserializer<Record<K, V>> {
    private final DecoderFactory decoderFactory;
    private final DatumReader keyReader;
    private final DatumReader valueReader;
    private final GenericData specificData;
    private final String topicName;
    private final Schema keySchema;
    private final Schema valueSchema;
    private BinaryDecoder decoder;

    public TapeAvroDeserializer(@NonNull AvroTopic<?, ?> topic, @NonNull GenericData specificData) {
        this.specificData = specificData;
        topicName = topic.getName();
        decoderFactory = DecoderFactory.get();
        keySchema = topic.getKeySchema();
        valueSchema = topic.getValueSchema();
        keyReader = specificData.createDatumReader(keySchema);
        valueReader = specificData.createDatumReader(valueSchema);
        decoder = null;
    }

    @SuppressWarnings("unchecked")
    public Record<K, V> deserialize(InputStream in) throws IOException {
        // for backwards compatibility
        int numRead = 0;
        do {
            numRead += in.skip(8 - numRead);
        } while (numRead < 8);

        decoder = decoderFactory.binaryDecoder(in, decoder);

        Object key;
        Object value;
        try {
            key = keyReader.read(null, decoder);
            value = valueReader.read(null, decoder);
        } catch (RuntimeException ex) {
            throw new IOException("Failed to deserialize object", ex);
        }

        if (!specificData.validate(keySchema, key)
                || !specificData.validate(valueSchema, value)) {
            throw new IllegalArgumentException("Failed to validate given record in topic "
                    + topicName + "\n\tkey: " + key + "\n\tvalue: " + value);
        }
        return new Record<>((K)key, (V)value);
    }
}
