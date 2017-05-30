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

import java.io.Closeable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public interface SingleThreadExecutorFactory extends Closeable {
    /**
     * Create or get a cached single threaded scheduled executor. This executor may only be shutdown
     * through {@link #close()} of this factory.
     * @return scheduled executor service
     */
    ScheduledExecutorService getScheduledExecutorService();
    /**
     * Create or get a cached single threaded executor. This executor may only be shutdown
     * through {@link #close()} of this factory.
     * @return executor service
     */
    ExecutorService getExecutorService();

    /**
     * Await the termination of all created executors. Do not call before calling {@link #close()}.
     * @param timeout number of milliseconds to wait.
     * @return true if all executors terminated before the timeout, false otherwise.
     * @throws InterruptedException if the join operation is interrupted.
     */
    boolean join(long timeout) throws InterruptedException;

    /**
     * Shutdown all created executors. Call this instead of closing the individually returned
     * executors. This does not wait for the termination of the executors, so existing tasks will
     * be run first. To wait for all existing tasks, call {@link #join(long)}.
     */
    @Override
    void close();
}
