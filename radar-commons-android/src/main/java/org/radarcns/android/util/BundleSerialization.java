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
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class BundleSerialization {
    private static final Logger logger = LoggerFactory.getLogger(BundleSerialization.class);

    private BundleSerialization() {
        // utility class
    }

    public static String bundleToString(Bundle bundle) {
        StringBuilder sb = new StringBuilder(bundle.size() * 40);
        sb.append('{');
        boolean first = true;
        for (String key : bundle.keySet()) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append(key).append(": ").append(bundle.get(key));
        }
        sb.append('}');
        return sb.toString();
    }

    public static Bundle getPersistentExtras(Intent intent, Context context) {
        SharedPreferences prefs = context.getSharedPreferences(context.getClass().getName(), Context.MODE_PRIVATE);
        Bundle bundle;
        if (intent == null || intent.getExtras() == null) {
            bundle = restoreFromPreferences(prefs);
        } else {
            bundle = intent.getExtras();
            saveToPreferences(prefs, bundle);
        }
        bundle.setClassLoader(BundleSerialization.class.getClassLoader());
        return bundle;
    }

    public static void saveToPreferences(SharedPreferences prefs, Bundle in) {
        Parcel parcel = Parcel.obtain();
        String serialized = null;
        try {
            in.writeToParcel(parcel, 0);

            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                bos.write(parcel.marshall());
                serialized = Base64.encodeToString(bos.toByteArray(), 0);
            }
        } catch (IOException e) {
            logger.error("Failed to serialize bundle", e);
        } finally {
            parcel.recycle();
        }
        if (serialized != null) {
            prefs.edit()
                .putString("parcel", serialized)
                .apply();
        }
    }

    public static Bundle restoreFromPreferences(SharedPreferences prefs) {
        Bundle bundle = null;
        String serialized = prefs.getString("parcel", null);

        if (serialized != null) {
            Parcel parcel = Parcel.obtain();
            try {
                byte[] data = Base64.decode(serialized, 0);
                parcel.unmarshall(data, 0, data.length);
                parcel.setDataPosition(0);
                bundle = parcel.readBundle(prefs.getClass().getClassLoader());
            } finally {
                parcel.recycle();
            }
        }
        return bundle;
    }
}
