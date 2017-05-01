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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * SingleThreadExecutorFactory that returns the same single-threaded executor in each getter
 * invocation.
 */
public class SharedSingleThreadExecutorFactory implements SingleThreadExecutorFactory {
    private final ScheduledExecutorService service;

    public SharedSingleThreadExecutorFactory(ThreadFactory threadFactory) {
        service = Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
        return service;
    }

    @Override
    public ExecutorService getExecutorService() {
        return service;
    }

    @Override
    public boolean join(long timeout) throws InterruptedException {
        return service.awaitTermination(timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        service.shutdown();
    }
}
