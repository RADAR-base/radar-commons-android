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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.radarcns.android.data.TapeAvroConverter;
import org.radarcns.data.Record;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.topic.AvroTopic;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by joris on 27/07/2017.
 */
public class BackedObjectQueueTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testBinaryObject() throws IOException {
        File file = folder.newFile();
        Random random = new Random();
        byte[] data = new byte[176482];
        random.nextBytes(data);

        assertTrue(file.delete());
        AvroTopic<ObservationKey, ActiveAudioRecording> topic = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), ActiveAudioRecording.getClassSchema(),
                ObservationKey.class, ActiveAudioRecording.class);
        try (BackedObjectQueue<Record<ObservationKey, ActiveAudioRecording>> queue = new BackedObjectQueue<>(
                QueueFile.newMapped(file, 450000000), new TapeAvroConverter<>(topic))) {

            ByteBuffer buffer = ByteBuffer.wrap(data);
            Record<ObservationKey, ActiveAudioRecording> record = new Record<>(
                    0L, new ObservationKey("test", "a", "b"), new ActiveAudioRecording(buffer));

            queue.add(record);
        }

        try (BackedObjectQueue<Record<ObservationKey, ActiveAudioRecording>> queue = new BackedObjectQueue<>(
                QueueFile.newMapped(file, 450000000), new TapeAvroConverter<>(topic))) {
            Record<ObservationKey, ActiveAudioRecording> result = queue.peek();

            assertArrayEquals(data, result.value.getData().array());
        }
    }

    @Test
    public void testRegularObject() throws IOException {
        File file = folder.newFile();
        assertTrue(file.delete());
        AvroTopic<ObservationKey, ObservationKey> topic = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), ObservationKey.getClassSchema(),
                ObservationKey.class, ObservationKey.class);

        BackedObjectQueue<Record<ObservationKey, ObservationKey>> queue;
        queue = new BackedObjectQueue<>(
                QueueFile.newMapped(file, 10000), new TapeAvroConverter<>(topic));

        Record<ObservationKey, ObservationKey> record = new Record<>(
                0L, new ObservationKey("test", "a", "b"),
                new ObservationKey("test", "c", "d"));

        queue.add(record);
        queue.peek();
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes, int offset, int count) {
        char[] hexChars = new char[count * 2];
        for ( int j = 0; j < count; j++ ) {
            int v = bytes[j + offset] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}
