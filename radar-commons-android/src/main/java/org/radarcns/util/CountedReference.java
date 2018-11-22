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

import android.support.annotation.Nullable;

/**
 * Reference that will close itself once its released.
 */
public class CountedReference<T> {
    private final ValueCreator<T> creator;
    private final ValueDestroyer<T> destroyer;
    private T value;
    private int count;

    public CountedReference(ValueCreator<T> creator, ValueDestroyer<T> destroyer) {
        this.creator = creator;
        this.destroyer = destroyer;
        value = null;
        count = 0;
    }

    public synchronized T acquire() {
        if (count == 0) {
            value = creator.doCreate();
        }
        count++;
        return value;
    }

    public synchronized void release() {
        if (count <= 0) {
            throw new IllegalStateException("Cannot release object that was not acquired");
        }
        count--;
        if (count == 0) {
            destroyer.doDestroy(value);
            value = null;
        }
    }

    public interface ValueCreator<T> {
        @Nullable T doCreate();
    }

    public interface ValueDestroyer<T> {
        void doDestroy(@Nullable T value);
    }
}
