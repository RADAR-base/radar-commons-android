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

import android.util.Pair;

import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.specific.SpecificData;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.radarcns.android.util.AndroidThreadFactory;
import org.radarcns.android.util.SharedSingleThreadExecutorFactory;
import org.radarcns.data.RecordData;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.monitor.application.ApplicationUptime;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.ActiveAudioRecording;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.slf4j.impl.HandroidLoggerAdapter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TapeCacheTest {
    private SharedSingleThreadExecutorFactory executorFactory;
    private TapeCache<ObservationKey, ApplicationUptime> tapeCache;
    private ObservationKey key;
    private ApplicationUptime value;
    private SpecificData specificData = CacheStore.get().getSpecificData();
    private GenericData genericData = CacheStore.get().getGenericData();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void setUpClass() {
        System.err.println("Setting up TapeCacheTest tests");
        HandroidLoggerAdapter.APP_NAME = "Test";
    }

    @Before
    public void setUp() throws IOException {
        AvroTopic<ObservationKey, ApplicationUptime> topic = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), ApplicationUptime.getClassSchema(),
                ObservationKey.class, ApplicationUptime.class);
        AvroTopic<Object, Object> outputTopic = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), ApplicationUptime.getClassSchema(),
                Object.class, Object.class);

        executorFactory = new SharedSingleThreadExecutorFactory(
                new AndroidThreadFactory("test", THREAD_PRIORITY_BACKGROUND));
        tapeCache = new TapeCache<>(folder.newFile(), topic,
                outputTopic, executorFactory, specificData, genericData);
        tapeCache.setMaximumSize(4096);
        tapeCache.setTimeWindow(100);

        key = new ObservationKey("test", "a", "b");
        double time = System.currentTimeMillis() / 1000d;
        value = new ApplicationUptime(time, System.nanoTime() / 1_000_000_000d);
    }

    @After
    public void tearDown() throws IOException {
        tapeCache.close();
        executorFactory.close();
    }

    @Test
    public void addMeasurement() throws Exception {
        assertEquals(null, tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());
        assertEquals(null, tapeCache.getRecords(100));

        tapeCache.addMeasurement(key, value);

        assertEquals(null, tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());

        Thread.sleep(100);

        RecordData<Object, Object> unsent = tapeCache.unsentRecords(100);
        assertNotNull(unsent);
        assertEquals(1, unsent.size());
        assertEquals(new Pair<>(1L, 0L), tapeCache.numberOfRecords());
        unsent = tapeCache.unsentRecords(100);
        assertNotNull(unsent);
        assertEquals(1, unsent.size());
        assertEquals(new Pair<>(1L, 0L), tapeCache.numberOfRecords());
        GenericRecord actualValue = (GenericRecord)unsent.iterator().next();
        assertEquals(key.getSourceId(), ((GenericRecord)unsent.getKey()).get("sourceId"));
        assertEquals(value.getUptime(), actualValue.get("uptime"));
        tapeCache.remove(1);
        assertNull(tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());

        tapeCache.addMeasurement(key, value);
        tapeCache.addMeasurement(key, value);

        Thread.sleep(100);

        unsent = tapeCache.unsentRecords(100);
        assertNotNull(unsent);
        assertEquals(2, unsent.size());
        assertEquals(new Pair<>(2L, 0L), tapeCache.numberOfRecords());
    }

    @Test
    public void testBinaryObject() throws IOException {
        AvroTopic<ObservationKey, ActiveAudioRecording> topic = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), ActiveAudioRecording.getClassSchema(),
                ObservationKey.class, ActiveAudioRecording.class);
        AvroTopic<Object, Object> outputTopic = new AvroTopic<>("test",
                ObservationKey.getClassSchema(), ActiveAudioRecording.getClassSchema(),
                Object.class, Object.class);
        TapeCache<ObservationKey, ActiveAudioRecording> localTapeCache = new TapeCache<>(
                folder.newFile(), topic, outputTopic, executorFactory, specificData, genericData);

        localTapeCache.setMaximumSize(45000000);
        localTapeCache.setTimeWindow(100);

        Random random = new Random();
        byte[] data = new byte[176482];
        random.nextBytes(data);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        ActiveAudioRecording localValue = new ActiveAudioRecording(buffer);

        localTapeCache.addMeasurement(key, localValue);
        localTapeCache.flush();
        RecordData<Object, Object> records = localTapeCache.unsentRecords(100);

        assertNotNull(records);
        assertEquals(1, records.size());
        GenericRecord firstRecord = (GenericRecord)records.iterator().next();
        assertEquals(key.getSourceId(), ((GenericRecord)records.getKey()).get("sourceId"));
        assertEquals(localValue.getData(), firstRecord.get("data"));
    }

    @Test
    public void flush() throws Exception {
        assertNull(tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());
        assertNull(tapeCache.getRecords(100));

        tapeCache.addMeasurement(key, value);

        assertNull(tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());

        tapeCache.flush();

        RecordData<Object, Object> unsent = tapeCache.unsentRecords(100);
        assertNotNull(unsent);
        assertEquals(1, unsent.size());
        assertEquals(new Pair<>(1L, 0L), tapeCache.numberOfRecords());
    }
}
