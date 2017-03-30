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

package org.radarcns.android.util;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.radarcns.android.BuildConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(constants = BuildConfig.class)
public class PersistentStorageTest {
    private PersistentStorage storage;

    @Before
    public void setUp() throws Exception {
        storage = new PersistentStorage(PersistentStorageTest.class);
        storage.clear();
        assertEquals(0, storage.size());
    }

    @Test
    public void get() throws Exception {
        assertEquals(null, storage.get("something"));
        storage.put("something", "other");
        assertEquals("other", storage.get("something"));

        PersistentStorage altStorage = new PersistentStorage(PersistentStorageTest.class);
        assertEquals("other", altStorage.get("something"));
    }

    @Test
    public void loadOrStore() throws IOException {
        Properties props1 = new Properties();
        props1.put("something", "other");
        storage.loadOrStore(props1);
        assertEquals("other", props1.getProperty("something"));
        assertEquals("other", storage.get("something"));

        Properties props2 = new Properties();
        props2.setProperty("something2", "other2");
        storage.loadOrStore(props2);
        assertEquals("other", props2.getProperty("something"));
        assertEquals("other", storage.get("something"));
        assertEquals("other2", props2.getProperty("something2"));
        assertEquals("other2", storage.get("something2"));
        assertEquals(null, props1.getProperty("something2"));

        Properties props3 = new Properties();
        props3.put("something2", "other3");
        storage.loadOrStore(props3);
        assertEquals("other", props2.getProperty("something"));
        assertEquals("other", props3.getProperty("something"));
        assertEquals("other", storage.get("something"));
        assertEquals("other2", props2.getProperty("something2"));
        assertEquals("other2", props3.getProperty("something2"));
        assertEquals("other2", storage.get("something2"));
        assertEquals(null, props1.getProperty("something2"));
    }

    @Test
    public void getOrSet() throws IOException {
        assertEquals("other", storage.getOrSet("something", "other"));
        assertEquals("other", storage.getOrSet("something", "other2"));
        assertEquals("o", storage.getOrSet("s", "o"));
    }

    @Test
    public void size() throws IOException {
        assertEquals(0, storage.size());
        assertEquals("other", storage.getOrSet("something", "other"));
        assertEquals(1, storage.size());
        storage.put("something2", "other");
        assertEquals(2, storage.size());
        storage.put("something2", "other");
        assertEquals(2, storage.size());

        assertEquals(2, new PersistentStorage(PersistentStorageTest.class).size());
    }

    @Test
    public void remove() throws IOException {
        assertEquals(0, storage.size());
        assertEquals("other", storage.getOrSet("something", "other"));
        assertEquals(1, storage.size());
        storage.put("something2", "other");
        assertEquals(2, storage.size());
        storage.put("something2", "other");
        assertEquals(2, storage.size());
        storage.remove("something2");
        assertEquals(1, storage.size());
        assertEquals(null, storage.get("something2"));
        storage.remove("nonexistant");
        assertEquals(1, storage.size());
    }
}