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

import org.radarcns.util.Serialization;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hash generator that uses the HmacSHA256 algorithm to hash data. This algorithm ensures that
 * it is very very hard to guess what data went in. The fixed key to the algorithm is stored in
 * the SharedPreferences. As long as the key remains there, a given input string will always
 * return the same output hash.
 *
 * <p>HashGenerator must be used from a single thread or synchronized externally.
 */
public class HashGenerator {
    private static final String HASH_KEY = "hash.key";

    private final SharedPreferences preferences;
    private final Mac sha256;
    private final byte[] hashBuffer = new byte[4];

    /**
     * Create a hash generator. This persists the hash.key property in the given preferences.
     * @param preferences to store key in
     */
    public HashGenerator(SharedPreferences preferences) {
        this.preferences = preferences;

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

    /**
     * Create a hash generator. This persists the hash in the SharedPreferences with the class name
     * of the passed context, in the hash.key property.
     * @param context service that the hash is needed for.
     */
    public HashGenerator(Context context) {
        this(context.getSharedPreferences(context.getClass().getName(), Context.MODE_PRIVATE));
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

    /** Create a unique hash for a given target. */
    public byte[] createHash(int target) {
        Serialization.intToBytes(target, hashBuffer, 0);
        return sha256.doFinal(hashBuffer);
    }

    /** Create a unique hash for a given target. */
    public byte[] createHash(String target) {
        return sha256.doFinal(target.getBytes());
    }

    /**
     * Create a unique hash for a given target. Internally this calls
     * {@link #createHash(int)}.
     */
    public ByteBuffer createHashByteBuffer(int target) {
        return ByteBuffer.wrap(createHash(target));
    }

    /**
     * Create a unique hash for a given target. Internally this calls
     * {@link #createHash(String)}.
     */
    public ByteBuffer createHashByteBuffer(String target) {
        return ByteBuffer.wrap(createHash(target));
    }
}
