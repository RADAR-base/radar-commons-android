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

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.content.Context.ALARM_SERVICE;
import static android.content.Context.POWER_SERVICE;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

/**
 * Process events based on a alarm. The events will be processed in a background Thread.
 * During processing in the provided Runnable,
 * check that {@link #isDone()} remains {@code false}. Once it turns true, the Runnable should stop
 * processing. If wake is set to true, {@link android.Manifest.permission#WAKE_LOCK} should be
 * acquired in the Manifest.
 */
@SuppressWarnings({"unused"})
public class OfflineProcessor implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(OfflineProcessor.class);

    private final Context context;
    private final BroadcastReceiver receiver;
    private final String requestName;
    private final PendingIntent pendingIntent;
    private final AlarmManager alarmManager;
    private final boolean keepAwake;
    private final Runnable runnable;

    private boolean doStop;
    private Thread processorThread;
    private long interval;
    private final AtomicBoolean isStarted;

    /**
     * Creates a processor that will register a BroadcastReceiver and alarm with the given context.
     * @param context context to register a BroadcastReceiver with
     * @param runnable code to run in offline mode
     * @param requestCode a code unique to the application, used to identify the current processor
     * @param requestName a name unique to the application, used to identify the current processor
     * @param wake wake the device for processing.
     * @param interval interval to run the processor in seconds.
     */
    public OfflineProcessor(Context context, Runnable runnable, int requestCode, final String
            requestName, long interval, final boolean wake) {
        this.context = context;
        this.doStop = false;
        this.requestName = requestName;
        this.alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        this.keepAwake = wake;
        this.runnable = runnable;

        Intent intent = new Intent(requestName);
        pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        this.receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                processBroadcast();
            }
        };
        this.interval = interval;
        isStarted = new AtomicBoolean(false);
    }

    /** Start processing. */
    public void start() {
        context.registerReceiver(this.receiver, new IntentFilter(requestName));
        schedule();
        isStarted.set(true);
    }

    /** Start up a new thread to process. */
    private synchronized void processBroadcast() {
        if (doStop) {
            return;
        }
        final PowerManager.WakeLock wakeLock;
        if (keepAwake) {
            wakeLock = acquireWakeLock(context, requestName);
        } else {
            wakeLock = null;
        }
        processorThread = new Thread(requestName) {
            @Override
            public void run() {
                try {
                    Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND);
                    runnable.run();
                } finally {
                    if (wakeLock != null) {
                        wakeLock.release();
                    }
                }
            }
        };
        processorThread.start();
    }

    @SuppressLint("WakelockTimeout")
    private static PowerManager.WakeLock acquireWakeLock(Context context, String requestName) {
        PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return null;
        }
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, requestName);
        wakeLock.acquire();
        return wakeLock;
    }

    /**
     * Change the processing interval to the given value.
     * @param interval time between processing in seconds.
     */
    public final void setInterval(long interval) {
        if (this.interval == interval) {
            return;
        }
        this.interval = interval;
        if (isStarted.get()) {
            schedule();
        }
    }

    private void schedule() {
        boolean runImmediately = Debug.isDebuggerConnected();
        long firstAlarm;
        if (runImmediately) {
            processBroadcast();
            firstAlarm = SystemClock.elapsedRealtime() + interval * 1000;
        } else {
            firstAlarm = SystemClock.elapsedRealtime();
        }
        int type = keepAwake ? AlarmManager.ELAPSED_REALTIME_WAKEUP : AlarmManager.ELAPSED_REALTIME;
        alarmManager.setInexactRepeating(type, firstAlarm, interval * 1000, pendingIntent);
    }

    /** Whether the processing Runnable should stop execution. */
    @SuppressWarnings("WeakerAccess")
    public synchronized boolean isDone() {
        return doStop;
    }

    /**
     * Closes the processor.
     *
     * This will deregister any BroadcastReceiver, remove pending alarms and signal the running thread to stop. If
     * processing is currently taking place, it will block until that is actually done. The processing Runnable should
     * query {@link #isDone()} very regularly to stop execution if that is the case.
     */
    @Override
    public void close() {
        alarmManager.cancel(pendingIntent);
        context.unregisterReceiver(receiver);

        Thread localThread;
        synchronized (this) {
            doStop = true;
            localThread = processorThread;
        }
        if (localThread != null) {
            try {
                localThread.join();
            } catch (InterruptedException e) {
                logger.warn("Waiting for processing thread interrupted");
            }
        }
    }
}
