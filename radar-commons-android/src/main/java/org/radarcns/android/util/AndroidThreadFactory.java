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

import android.support.annotation.NonNull;

import java.util.concurrent.ThreadFactory;

public class AndroidThreadFactory implements ThreadFactory {
    private final String name;
    private final int priority;

    /**
     * Create threads in Android with the correct priority.
     * @param name thread name
     * @param priority one of android.os.Process.THEAD_PRIORITY_*
     */
    public AndroidThreadFactory(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    @Override
    public Thread newThread(@NonNull final Runnable r) {
        return new Thread(() -> {
            android.os.Process.setThreadPriority(priority);
            r.run();
        }, name);
    }
}
