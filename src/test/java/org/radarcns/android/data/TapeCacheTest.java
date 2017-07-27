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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.radarcns.android.util.AndroidThreadFactory;
import org.radarcns.android.util.SharedSingleThreadExecutorFactory;
import org.radarcns.application.ApplicationUptime;
import org.radarcns.data.Record;
import org.radarcns.key.MeasurementKey;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.ActiveAudioRecording;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TapeCacheTest {
    private SharedSingleThreadExecutorFactory executorFactory;
    private TapeCache<MeasurementKey, ApplicationUptime> tapeCache;
    private MeasurementKey key;
    private ApplicationUptime value;

    @Before
    public void setUp() throws IOException {
        AvroTopic<MeasurementKey, ApplicationUptime> topic = new AvroTopic<>("test",
                MeasurementKey.getClassSchema(), ApplicationUptime.getClassSchema(),
                MeasurementKey.class, ApplicationUptime.class);
        executorFactory = new SharedSingleThreadExecutorFactory(
                new AndroidThreadFactory("test", THREAD_PRIORITY_BACKGROUND));
        tapeCache = new TapeCache<>(
                RuntimeEnvironment.application.getApplicationContext(),
                topic, executorFactory);
        tapeCache.setMaximumSize(4096);
        tapeCache.setTimeWindow(100);

        key = new MeasurementKey("a", "b");
        double time = System.currentTimeMillis() / 1000d;
        value = new ApplicationUptime(time, time, System.nanoTime() / 1_000_000_000d);
    }

    @After
    public void tearDown() throws IOException {
        tapeCache.close();
        executorFactory.close();
    }

    @Test
    public void addMeasurement() throws Exception {
        assertEquals(Collections.emptyList(), tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());
        assertEquals(Collections.emptyList(), tapeCache.getRecords(100));

        tapeCache.addMeasurement(key, value);

        assertEquals(Collections.emptyList(), tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());

        Thread.sleep(100);

        List<Record<MeasurementKey, ApplicationUptime>> unsent = tapeCache.unsentRecords(100);
        assertEquals(1, unsent.size());
        assertEquals(new Pair<>(1L, 0L), tapeCache.numberOfRecords());
        unsent = tapeCache.unsentRecords(100);
        assertEquals(1, unsent.size());
        assertEquals(new Pair<>(1L, 0L), tapeCache.numberOfRecords());
        Record<MeasurementKey, ApplicationUptime> record = unsent.get(0);
        assertEquals(key, record.key);
        assertEquals(value, record.value);
        tapeCache.markSent(record.offset);
        assertEquals(Collections.emptyList(), tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());

        tapeCache.addMeasurement(key, value);
        tapeCache.addMeasurement(key, value);

        Thread.sleep(100);

        unsent = tapeCache.unsentRecords(100);
        assertEquals(2, unsent.size());
        assertEquals(new Pair<>(2L, 0L), tapeCache.numberOfRecords());
    }


    @Test
    public void testBinaryObject() throws IOException {
        AvroTopic<MeasurementKey, ActiveAudioRecording> topic = new AvroTopic<>("test",
                MeasurementKey.getClassSchema(), ActiveAudioRecording.getClassSchema(),
                MeasurementKey.class, ActiveAudioRecording.class);
        TapeCache<MeasurementKey, ActiveAudioRecording> localTapeCache = new TapeCache<>(
                RuntimeEnvironment.application.getApplicationContext(),
                topic, executorFactory);

        localTapeCache.setMaximumSize(4096);
        localTapeCache.setTimeWindow(100);

        Random random = new Random();
        byte[] data = new byte[176482];
        random.nextBytes(data);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        ActiveAudioRecording localValue = new ActiveAudioRecording(buffer);

        localTapeCache.addMeasurement(key, localValue);
        localTapeCache.flush();
        List<Record<MeasurementKey, ActiveAudioRecording>> records = localTapeCache.unsentRecords(100);

        assertEquals(Collections.singletonList(new Record<>(0, key, localValue)), records);
    }

    @Test
    public void flush() throws Exception {
        assertEquals(Collections.emptyList(), tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());
        assertEquals(Collections.emptyList(), tapeCache.getRecords(100));

        tapeCache.addMeasurement(key, value);

        assertEquals(Collections.emptyList(), tapeCache.unsentRecords(100));
        assertEquals(new Pair<>(0L, 0L), tapeCache.numberOfRecords());

        tapeCache.flush();

        assertEquals(1, tapeCache.unsentRecords(100).size());
        assertEquals(new Pair<>(1L, 0L), tapeCache.numberOfRecords());
    }
}
