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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * SingleThreadExecutorFactory that creates a new single-threaded executor in getter invocation.
 */
public class LocalSingleThreadExecutorFactory implements SingleThreadExecutorFactory {
    private final List<ExecutorService> services;
    private final ThreadFactory threadFactory;

    public LocalSingleThreadExecutorFactory(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
        this.services = new ArrayList<>();
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
        ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(threadFactory);
        services.add(service);
        return service;
    }

    @Override
    public ExecutorService getExecutorService() {
        ExecutorService service = Executors.newSingleThreadExecutor(threadFactory);
        services.add(service);
        return service;
    }

    @Override
    public boolean join(long timeout) throws InterruptedException {
        long start = System.currentTimeMillis();
        boolean didTerminate = true;
        for (ExecutorService service : services) {
            long alreadyWaited = System.currentTimeMillis() - start;
            if (alreadyWaited > timeout) {
                return false;
            }
            didTerminate = service.awaitTermination(timeout - alreadyWaited, TimeUnit.MILLISECONDS);
        }
        return didTerminate;
    }

    @Override
    public void close() {
        for (ExecutorService service : services) {
            service.shutdown();
        }
    }
}
