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

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;
import org.radarcns.data.Record;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.BackedObjectQueue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Converts records from an AvroTopic for Tape
 */
public class TapeAvroConverter<K extends SpecificRecord, V extends SpecificRecord>
        implements BackedObjectQueue.Converter<Record<K, V>> {
    private static final byte[] EMPTY_HEADER = {0, 0, 0, 0, 0, 0, 0, 0};
    private final EncoderFactory encoderFactory;
    private final DecoderFactory decoderFactory;
    private final SpecificDatumWriter<K> keyWriter;
    private final SpecificDatumWriter<V> valueWriter;
    private final SpecificDatumReader<K> keyReader;
    private final SpecificDatumReader<V> valueReader;
    private BinaryEncoder encoder;
    private BinaryDecoder decoder;

    public TapeAvroConverter(AvroTopic<K, V> topic) throws IOException {
        encoderFactory = EncoderFactory.get();
        decoderFactory = DecoderFactory.get();
        keyWriter = new SpecificDatumWriter<>(topic.getKeySchema());
        valueWriter = new SpecificDatumWriter<>(topic.getValueSchema());
        keyReader = new SpecificDatumReader<>(topic.getKeySchema());
        valueReader = new SpecificDatumReader<>(topic.getValueSchema());
        encoder = null;
        decoder = null;
    }

    public Record<K, V> deserialize(InputStream in) throws IOException {
        // for backwards compatibility
        int numRead = 0;
        do {
            numRead += in.skip(8 - numRead);
        } while (numRead < 8);

        decoder = decoderFactory.binaryDecoder(in, decoder);

        try {
            K key = keyReader.read(null, decoder);
            V value = valueReader.read(null, decoder);
            return new Record<>(key, value);
        } catch (RuntimeException ex) {
            throw new IOException("Failed to deserialize object", ex);
        }
    }

    public void serialize(Record<K, V> o, OutputStream out) throws IOException {
        // for backwards compatibility
        out.write(EMPTY_HEADER, 0, 8);
        encoder = encoderFactory.binaryEncoder(out, encoder);
        keyWriter.write(o.key, encoder);
        valueWriter.write(o.value, encoder);
        encoder.flush();
    }
}
