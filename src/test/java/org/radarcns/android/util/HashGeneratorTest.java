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

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

/**
 * Test that all hashes are unique for different values and the same for the same values.
 * Also test that all hashes are unique for different keys but with the same values.
 */
@RunWith(RobolectricTestRunner.class)
public class HashGeneratorTest {

    @Test
    public void createHash() throws Exception {
        SharedPreferences prefs1 = RuntimeEnvironment.application.getSharedPreferences("createHash1", Context.MODE_PRIVATE);
        HashGenerator hasher1 = new HashGenerator(prefs1);
        SharedPreferences prefs2 = RuntimeEnvironment.application.getSharedPreferences("createHash2", Context.MODE_PRIVATE);
        HashGenerator hasher2 = new HashGenerator(prefs2);
        Random random = new Random();

        byte[] previousB = null;
        int previousV = 0;
        for (int i = 0; i < 10; i++) {
            int v = random.nextInt();
            byte[] b1 = hasher1.createHash(v);
            byte[] b2 = hasher1.createHash(v);
            byte[] b3 = hasher2.createHash(v);
            assertNotNull(b1);
            assertTrue(Arrays.equals(b2, b1));
            assertFalse(Arrays.equals(b3, b1));
            if (i > 0 && previousV != v) {
                assertFalse(Arrays.equals(previousB, b1));
            }
            previousV = v;
            previousB = b1;
        }
    }

    @Test
    public void createHash1() throws Exception {
        SharedPreferences prefs1 = RuntimeEnvironment.application.getSharedPreferences("createHashString1", Context.MODE_PRIVATE);
        HashGenerator hasher1 = new HashGenerator(prefs1);
        SharedPreferences prefs2 = RuntimeEnvironment.application.getSharedPreferences("createHashString2", Context.MODE_PRIVATE);
        HashGenerator hasher2 = new HashGenerator(prefs2);

        byte[] previousB = null;
        String previousV = null;
        for (int i = 0; i < 10; i++) {
            String v = UUID.randomUUID().toString();
            byte[] b1 = hasher1.createHash(v);
            byte[] b2 = hasher1.createHash(v);
            byte[] b3 = hasher2.createHash(v);
            assertTrue(Arrays.equals(b2, b1));
            assertFalse(Arrays.equals(b3, b1));
            if (i > 0 && !v.equals(previousV)) {
                assertFalse(Arrays.equals(previousB, b1));
            }
            previousV = v;
            previousB = b1;
        }
    }

    @Test
    public void createHashByteBuffer() throws Exception {
        SharedPreferences prefs1 = RuntimeEnvironment.application.getSharedPreferences("createHashByteBuffer1", Context.MODE_PRIVATE);
        HashGenerator hasher1 = new HashGenerator(prefs1);
        SharedPreferences prefs2 = RuntimeEnvironment.application.getSharedPreferences("createHashByteBuffer2", Context.MODE_PRIVATE);
        HashGenerator hasher2 = new HashGenerator(prefs2);
        Random random = new Random();

        ByteBuffer previousB = null;
        int previousV = 0;
        for (int i = 0; i < 10; i++) {
            int v = random.nextInt();
            ByteBuffer b1 = hasher1.createHashByteBuffer(v);
            ByteBuffer b2 = hasher1.createHashByteBuffer(v);
            ByteBuffer b3 = hasher2.createHashByteBuffer(v);
            assertEquals(b2, b1);
            assertNotEquals(b3, b1);
            if (i > 0 && v != previousV) {
                assertNotEquals(previousB, b1);
            }
            previousV = v;
            previousB = b1;
        }
    }

    @Test
    public void createHashByteBuffer1() throws Exception {
        SharedPreferences prefs1 = RuntimeEnvironment.application.getSharedPreferences("createHashByteBufferString1", Context.MODE_PRIVATE);
        HashGenerator hasher1 = new HashGenerator(prefs1);
        SharedPreferences prefs2 = RuntimeEnvironment.application.getSharedPreferences("createHashByteBufferString2", Context.MODE_PRIVATE);
        HashGenerator hasher2 = new HashGenerator(prefs2);

        ByteBuffer previousB = null;
        String previousV = null;
        for (int i = 0; i < 10; i++) {
            String v = UUID.randomUUID().toString();
            ByteBuffer b1 = hasher1.createHashByteBuffer(v);
            ByteBuffer b2 = hasher1.createHashByteBuffer(v);
            ByteBuffer b3 = hasher2.createHashByteBuffer(v);
            assertEquals(b2, b1);
            assertNotEquals(b3, b1);
            if (i > 0 && !v.equals(previousV)) {
                assertNotEquals(previousB, b1);
            }
            previousV = v;
            previousB = b1;
        }
    }

}
