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

package org.radarbase.android.util;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test that all hashes are unique for different values and the same for the same values.
 * Also test that all hashes are unique for different keys but with the same values.
 */
@RunWith(AndroidJUnit4.class)
public class HashGeneratorTest {
    private HashGenerator hasher1;
    private HashGenerator hasher1a;
    private HashGenerator hasher2;

    @Before
    public void setUp() {
        int v1 = ThreadLocalRandom.current().nextInt();
        int v2 = ThreadLocalRandom.current().nextInt();
        hasher1 = new HashGenerator(ApplicationProvider.getApplicationContext(), "createHash1" + v1);
        hasher1a = new HashGenerator(ApplicationProvider.getApplicationContext(), "createHash1" + v1);
        hasher2 = new HashGenerator(ApplicationProvider.getApplicationContext(), "createHash2" + v2);
    }

    @Test
    public void createHash() {
        Random random = new Random();

        byte[] previousB = null;
        int previousV = 0;
        for (int i = 0; i < 10; i++) {
            int v = random.nextInt();
            byte[] b1 = hasher1.createHash(v);
            byte[] b2 = hasher1a.createHash(v);
            byte[] b3 = hasher2.createHash(v);
            Assert.assertNotNull(b1);
            Assert.assertArrayEquals(b2, b1);
            Assert.assertFalse(Arrays.equals(b3, b1));
            if (i > 0 && previousV != v) {
                Assert.assertFalse(Arrays.equals(previousB, b1));
            }
            previousV = v;
            previousB = b1;
        }
    }

    @Test
    public void createHash1() {
        byte[] previousB = null;
        String previousV = null;
        for (int i = 0; i < 10; i++) {
            String v = UUID.randomUUID().toString();
            byte[] b1 = hasher1.createHash(v);
            byte[] b2 = hasher1a.createHash(v);
            byte[] b3 = hasher2.createHash(v);
            Assert.assertArrayEquals(b2, b1);
            Assert.assertFalse(Arrays.equals(b3, b1));
            if (i > 0 && !v.equals(previousV)) {
                Assert.assertFalse(Arrays.equals(previousB, b1));
            }
            previousV = v;
            previousB = b1;
        }
    }

    @Test
    public void createHashByteBuffer() {
        Random random = new Random();

        ByteBuffer previousB = null;
        int previousV = 0;
        for (int i = 0; i < 10; i++) {
            int v = random.nextInt();
            ByteBuffer b1 = hasher1.createHashByteBuffer(v);
            ByteBuffer b2 = hasher1a.createHashByteBuffer(v);
            ByteBuffer b3 = hasher2.createHashByteBuffer(v);
            Assert.assertEquals(b2, b1);
            Assert.assertNotEquals(b3, b1);
            if (i > 0 && v != previousV) {
                Assert.assertNotEquals(previousB, b1);
            }
            previousV = v;
            previousB = b1;
        }
    }

    @Test
    public void createHashByteBuffer1() {
        ByteBuffer previousB = null;
        String previousV = null;
        for (int i = 0; i < 10; i++) {
            String v = UUID.randomUUID().toString();
            ByteBuffer b1 = hasher1.createHashByteBuffer(v);
            ByteBuffer b2 = hasher1a.createHashByteBuffer(v);
            ByteBuffer b3 = hasher2.createHashByteBuffer(v);
            Assert.assertEquals(b2, b1);
            Assert.assertNotEquals(b3, b1);
            if (i > 0 && !v.equals(previousV)) {
                Assert.assertNotEquals(previousB, b1);
            }
            previousV = v;
            previousB = b1;
        }
    }

}
