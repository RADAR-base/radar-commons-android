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

/**
 * Reference that will close itself once its released.
 */
public class CountedReference<T> {
    private T value;
    private int count;

    public CountedReference(T referent) {
        value = referent;
        count = 0;
    }

    public synchronized T acquire() {
        count++;
        return value;
    }

    public synchronized T release() {
        if (count <= 0) {
            throw new IllegalStateException("Cannot release object that was not acquired");
        }
        count--;
        return value;
    }

    public synchronized boolean isNotHeld() {
        return count == 0;
    }
}
