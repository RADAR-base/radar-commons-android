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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import com.crashlytics.android.Crashlytics;

import org.radarcns.util.CountedReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
@SuppressWarnings({"unused", "WeakerAccess"})
public class OfflineProcessor implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(OfflineProcessor.class);
    private static final CountedReference<HandlerThread> DEFAULT_HANDLER_THREAD = new CountedReference<>(
            () -> {
                HandlerThread handlerThread = new HandlerThread("OfflineProcessor", THREAD_PRIORITY_BACKGROUND);
                handlerThread.start();
                return handlerThread;
            }, handlerThread -> {
                if (handlerThread != null) {
                    handlerThread.quitSafely();
                }
            });

    private final Context context;
    private final BroadcastReceiver receiver;
    private final String requestName;
    private final PendingIntent pendingIntent;
    private final AlarmManager alarmManager;
    private final boolean keepAwake;
    private final Runnable runnable;
    private final Handler handler;
    private final boolean doReleaseHandler;

    private boolean doStop;
    private long intervalMillis;
    private volatile boolean isStarted;
    private volatile boolean isRunning;

    /**
     * Creates a processor that will register a BroadcastReceiver and alarm with the given context.
     * @param context context to register a BroadcastReceiver with
     * @param runnable code to run in offline mode
     * @param requestCode a code unique to the application, used to identify the current processor
     * @param requestName a name unique to the application, used to identify the current processor
     * @param wake wake the device for processing.
     * @param interval interval to run the processor in seconds.
     * @deprecated use {@link Builder} instead.
     */
    @Deprecated
    public OfflineProcessor(Context context, Runnable runnable, int requestCode, final String
            requestName, long interval, final boolean wake) {
        this(new Builder(context, Objects.requireNonNull(runnable))
                .requestIdentifier(requestCode, requestName)
                .interval(interval, TimeUnit.SECONDS)
                .wake(wake));
    }

    /**
     * Creates a processor that will register a BroadcastReceiver and alarm with the given context.
     * @param context context to register a BroadcastReceiver with
     * @param runnable code to run in offline mode
     * @param requestCode a code unique to the application, used to identify the current processor
     * @param requestName a name unique to the application, used to identify the current processor
     * @param interval interval to run the processor.
     * @param intervalUnit time unit to measure interval with.
     * @param wake wake the device for processing.
     * @deprecated use {@link Builder} instead.
     */
    @Deprecated
    public OfflineProcessor(Context context, Runnable runnable, int requestCode, final String
            requestName, long interval, TimeUnit intervalUnit, final boolean wake) {
        this(new Builder(context, Objects.requireNonNull(runnable))
                .requestIdentifier(requestCode, requestName)
                .interval(interval, intervalUnit)
                .wake(wake));
    }

    private OfflineProcessor(Builder builder) {
        this.context = builder.context;
        this.requestName = builder.requestName;
        this.keepAwake = builder.wake;
        this.runnable = builder.runnable;
        this.intervalMillis = builder.intervalMillis;
        this.doStop = false;
        this.alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
        if (builder.handler == null) {
            this.handler = new Handler(DEFAULT_HANDLER_THREAD.acquire().getLooper());
            doReleaseHandler = true;
        } else {
            this.handler = builder.handler;
            doReleaseHandler = false;
        }
        Intent intent = new Intent(requestName);
        pendingIntent = PendingIntent.getBroadcast(context, builder.requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        this.receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                trigger();
            }
        };
        isStarted = false;
    }

    /** Start processing. */
    public void start() {
        context.registerReceiver(this.receiver, new IntentFilter(requestName));
        schedule();
        isStarted = true;
    }

    /** Start up a new thread to process. */
    public synchronized void trigger() {
        if (doStop || isRunning) {
            return;
        }
        isRunning = true;
        final PowerManager.WakeLock wakeLock;
        if (keepAwake) {
            wakeLock = acquireWakeLock(context, requestName);
        } else {
            wakeLock = null;
        }
        try {
            handler.post(() -> {
                try {
                    runnable.run();
                } catch (RuntimeException ex) {
                    Crashlytics.logException(ex);
                    logger.error("OfflineProcessor task failed.", ex);
                } finally {
                    isRunning = false;
                    if (wakeLock != null) {
                        wakeLock.release();
                    }
                }
            });
        } catch (RuntimeException ex) {
            logger.error("Handler thread is no longer running.", ex);
            isRunning = false;
            if (wakeLock != null) {
                wakeLock.release();
            }
        }
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
     * @deprecated use {@link #setInterval(long, TimeUnit)} instead.
     */
    @Deprecated
    public final void setInterval(long interval) {
        setInterval(interval, TimeUnit.SECONDS);
    }

    /**
     * Change the processing interval to the given value.
     * @param interval time between processing.
     * @param timeUnit time unit to that interval is given with
     */
    public final void setInterval(long interval, TimeUnit timeUnit) {
        long newIntervalMillis = timeUnit.toMillis(interval);
        if (this.intervalMillis == newIntervalMillis) {
            return;
        }
        this.intervalMillis = newIntervalMillis;
        if (isStarted) {
            schedule();
        }
    }

    private void schedule() {
        boolean runImmediately = Debug.isDebuggerConnected();
        long firstAlarm;
        if (runImmediately) {
            trigger();
            firstAlarm = SystemClock.elapsedRealtime() + intervalMillis;
        } else {
            firstAlarm = SystemClock.elapsedRealtime() + intervalMillis / 4;
        }
        int type = keepAwake ? AlarmManager.ELAPSED_REALTIME_WAKEUP : AlarmManager.ELAPSED_REALTIME;
        alarmManager.setInexactRepeating(type, firstAlarm, intervalMillis, pendingIntent);
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

        synchronized (this) {
            doStop = true;
        }
        if (doReleaseHandler) {
            DEFAULT_HANDLER_THREAD.release();
        }
    }

    public static class Builder {
        private final Context context;
        private final Runnable runnable;
        private long intervalMillis = -1;
        private boolean wake = true;
        private int requestCode;
        private String requestName;
        private Handler handler;

        public Builder(@NonNull Context context, @NonNull Runnable runnable) {
            this.context = Objects.requireNonNull(context);
            this.runnable = Objects.requireNonNull(runnable);
        }

        public Builder wake(boolean doWake) {
            wake = doWake;
            return this;
        }

        public Builder requestIdentifier(int code, String name) {
            requestCode = code;
            requestName = name;
            return this;
        }

        public Builder interval(long duration, TimeUnit unit) {
            intervalMillis = unit.toMillis(duration);
            return this;
        }

        public Builder handler(Handler handler) {
            this.handler = handler;
            return this;
        }

        public OfflineProcessor build() {
            if (intervalMillis == -1) {
                throw new IllegalStateException("Cannot start offline processor without an interval");
            }
            return new OfflineProcessor(this);
        }
    }
}
