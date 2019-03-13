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

package org.radarbase.android.util

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import com.crashlytics.android.Crashlytics
import org.radarbase.util.CountedReference
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Process events based on a alarm. The events will be processed in a background Thread.
 * During processing in the provided Runnable,
 * check that [.isDone] remains `false`. Once it turns true, the Runnable should stop
 * processing. If wake is set to true, [android.Manifest.permission.WAKE_LOCK] should be
 * acquired in the Manifest.
 */
class OfflineProcessor(private val context: Context,
                       private val config: ProcessorConfiguration) : Closeable {

    constructor(context: Context, config: ProcessorConfiguration.() -> Unit) : this(context, ProcessorConfiguration().apply(config))

    private val receiver: BroadcastReceiver
    private val pendingIntent: PendingIntent
    private val alarmManager: AlarmManager
    private val handler: Handler

    /** Whether the processing Runnable should stop execution.  */
    @get:Synchronized
    var isDone: Boolean = false
        private set

    private var isStarted: Boolean = false

    private val isRunning: Semaphore

    init {
        config.validate()
        this.isDone = false
        this.alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager

        handler = config.handler ?: Handler(config.handlerReference.acquire().looper)
        val intent = Intent(config.requestName)
        pendingIntent = PendingIntent.getBroadcast(context, config.requestCode!!, intent,
                PendingIntent.FLAG_UPDATE_CURRENT)

        this.receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trigger()
            }
        }
        isStarted = false
        isRunning = Semaphore(1)
    }

    /** Start processing.  */
    fun start(initializer: (() -> Unit)? = null) {
        context.registerReceiver(this.receiver, IntentFilter(config.requestName))
        handler.post {
            schedule()
            isStarted = true
            initializer?.let { it() }
        }
    }

    /** Start up a new thread to process.  */
    fun trigger() {
        if (isDone) {
            return
        }
        if (!isRunning.tryAcquire()) {
            return
        }
        val wakeLock = if (config.wake) {
            acquireWakeLock(context, config.requestName)
        } else null

        try {
            for (runnable in config.process) {
                handler.post {
                    if (isDone) {
                        return@post
                    }
                    try {
                        runnable()
                    } catch (ex: RuntimeException) {
                        Crashlytics.logException(ex)
                        logger.error("OfflineProcessor task failed.", ex)
                    }
                }
            }
            handler.post {
                isRunning.release()
                wakeLock?.release()
            }
        } catch (ex: RuntimeException) {
            logger.error("Handler thread is no longer running.", ex)
            isRunning.release()
            wakeLock?.release()
        }

    }

    /**
     * Change the processing interval to the given value.
     * @param duration time between processing.
     * @param timeUnit time unit to that duration is given with
     */
    fun interval(duration: Long, timeUnit: TimeUnit) {
        if (duration <= 0L) {
            throw IllegalArgumentException("Duration must be positive")
        }
        handler.post {
            if (config.interval(duration, timeUnit) && isStarted) {
                schedule()
            }
        }
    }

    private fun schedule() {
        val runImmediately = Debug.isDebuggerConnected()
        val firstAlarm: Long
        firstAlarm = if (runImmediately) {
            trigger()
            SystemClock.elapsedRealtime() + config.intervalMillis
        } else {
            SystemClock.elapsedRealtime() + config.intervalMillis / 4
        }
        val type = if (config.wake) AlarmManager.ELAPSED_REALTIME_WAKEUP else AlarmManager.ELAPSED_REALTIME
        alarmManager.setInexactRepeating(type, firstAlarm, config.intervalMillis, pendingIntent)
    }

    /**
     * Closes the processor.
     *
     * This will deregister any BroadcastReceiver, remove pending alarms and signal the running thread to stop. If
     * processing is currently taking place, it will block until that is actually done.
     * The processing Runnable should query [.isDone] very regularly to stop execution
     * if that is the case.
     */
    override fun close() {
        synchronized(this) {
            if (isDone) {
                logger.info("OfflineProcessor attempted to be closed twice.")
                return
            }
            isDone = true
        }
        alarmManager.cancel(pendingIntent)
        context.unregisterReceiver(receiver)

        try {
            isRunning.acquire()
            if (config.handler == null) {
                config.handlerReference.release()
            }
        } catch (e: InterruptedException) {
            logger.error("Interrupted while waiting for processing to finish.")
            Thread.currentThread().interrupt()
        }
    }

    data class ProcessorConfiguration(
            var requestCode: Int? = null,
            var requestName: String? = null,
            var process: List<() -> Unit> = emptyList(),
            var intervalMillis: Long = -1,
            var wake: Boolean = true,
            var handler: Handler? = null,
            var handlerReference: CountedReference<HandlerThread> = DEFAULT_HANDLER_THREAD) {

        fun validate() {
            if (requestCode == null || requestName == null) {
                throw IllegalArgumentException("Cannot start processor without request code or name")
            }
            if (intervalMillis < 0L) {
                throw IllegalArgumentException("Cannot start offline processor without an interval")
            }
            if (process.isEmpty()) {
                throw IllegalArgumentException("Cannot run without a process")
            }
        }

        fun interval(duration: Long, unit: TimeUnit): Boolean {
            if (duration <= 0L) {
                throw IllegalArgumentException("Duration must be positive")
            }
            val oldInterval = intervalMillis
            intervalMillis = unit.toMillis(duration)
            return oldInterval != intervalMillis
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OfflineProcessor::class.java)
        private val DEFAULT_HANDLER_THREAD = CountedReference({
            HandlerThread("OfflineProcessor", THREAD_PRIORITY_BACKGROUND).also {
                it.start()
            }
        }, { it.quitSafely() })

        @SuppressLint("WakelockTimeout")
        private fun acquireWakeLock(context: Context, requestName: String?): PowerManager.WakeLock? {
            return (context.getSystemService(POWER_SERVICE) as PowerManager?)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, requestName)
                    ?.also { it.acquire() }
        }
    }
}
