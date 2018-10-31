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

import org.apache.avro.generic.GenericData;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.radarcns.data.Record;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.BackedObjectQueue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Converts records from an AvroTopic for Tape
 */
public class TapeAvroSerializer<K, V> implements BackedObjectQueue.Serializer<Record<K, V>> {
    private static final byte[] EMPTY_HEADER = {0, 0, 0, 0, 0, 0, 0, 0};
    private final EncoderFactory encoderFactory;
    private final DatumWriter keyWriter;
    private final DatumWriter valueWriter;
    private BinaryEncoder encoder;
    private K previousKey;
    private byte[] keyBytes;

    public TapeAvroSerializer(AvroTopic<K, V> topic, GenericData specificData) {
        encoderFactory = EncoderFactory.get();
        keyWriter = specificData.createDatumWriter(topic.getKeySchema());
        valueWriter = specificData.createDatumWriter(topic.getValueSchema());
        encoder = null;

        previousKey = null;
        keyBytes = new byte[0];
    }

    @SuppressWarnings("unchecked")
    public void serialize(Record<K, V> o, OutputStream out) throws IOException {
        // for backwards compatibility
        out.write(EMPTY_HEADER, 0, 8);

        if (!Objects.equals(o.key, previousKey)) {
            ByteArrayOutputStream keyOut = new ByteArrayOutputStream();
            encoder = encoderFactory.binaryEncoder(keyOut, encoder);
            keyWriter.write(o.key, encoder);
            encoder.flush();
            previousKey = o.key;
            keyBytes = keyOut.toByteArray();
        }
        out.write(keyBytes);

        encoder = encoderFactory.binaryEncoder(out, encoder);
        valueWriter.write(o.value, encoder);
        encoder.flush();
    }
}
