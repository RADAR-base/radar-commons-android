/*
 * Copyright 2017 Kings College London and The Hyve
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

import android.os.Bundle;

public class BundleSerialization {
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
}
