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
import android.util.Base64;

import org.radarcns.android.device.DeviceService;
import org.radarcns.util.Serialization;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class HashGenerator {
    private final SharedPreferences preferences;

    private static final String HASH_KEY = "hash.key";
    private final Mac sha256;
    private final byte[] hashBuffer = new byte[4];

    public HashGenerator(DeviceService deviceService) {
        Context context = deviceService.getApplicationContext();
        preferences = context.getSharedPreferences(DeviceService.class.getName(), Context.MODE_PRIVATE);

        try {
            this.sha256 = Mac.getInstance("HmacSHA256");
            sha256.init(new SecretKeySpec(loadHashKey(), "HmacSHA256"));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot retrieve hashing algorithm", ex);
        } catch (InvalidKeyException ex) {
            throw new IllegalStateException("Encoding is invalid", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load hashing key", ex);
        }
    }

    private byte[] loadHashKey() throws IOException {
        String b64Salt = preferences.getString(HASH_KEY, null);
        if (b64Salt == null) {
            byte[] byteSalt = new byte[16];
            new SecureRandom().nextBytes(byteSalt);

            b64Salt = Base64.encodeToString(byteSalt, Base64.NO_WRAP);
            preferences.edit().putString(HASH_KEY, b64Salt).apply();
            return byteSalt;
        } else {
            return Base64.decode(b64Salt, Base64.NO_WRAP);
        }
    }

    public byte[] createHash(int target) {
        Serialization.intToBytes(target, hashBuffer, 0);
        return sha256.doFinal(hashBuffer);
    }

    public byte[] createHash(String target) {
        return sha256.doFinal(target.getBytes());
    }

    public ByteBuffer createHashByteBuffer(int target) {
        return ByteBuffer.wrap(createHash(target));
    }

    public ByteBuffer createHashByteBuffer(String target) {
        return ByteBuffer.wrap(createHash(target));
    }

    /**
     * Extracts last 9 characters and hashes the result with a salt.
     * For phone numbers this means that the area code is removed
     * E.g.: +31232014111 becomes 232014111 and 0612345678 becomes 612345678 (before hashing)
     * If target is a name instead of a number (e.g. when sms), then hash this name
     * @param target String
     * @return String
     */
    public byte[] createHashFromPhoneNumber(String target) {
        // Test if target is numerical
        try {
            Long targetLong = Long.parseLong(target);

            // Anonymous calls have target -1 or -2, do not hash them
            if (targetLong < 0) {
                return null;
            }
        } catch (NumberFormatException ex) {
            // If non-numerical, then hash the target directly
            return createHash(target);
        }

        int length = target.length();
        if (length > 9) {
            target = target.substring(length - 9, length);
            // remove all non-numeric characters
            target = target.replaceAll("[^0-9]", "");
            // for example, a
            if (target.isEmpty()) {
                return null;
            }
        }

        return createHash(Integer.valueOf(target));
    }
}
