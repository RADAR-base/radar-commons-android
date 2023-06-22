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
import android.os.Debug
import android.os.PowerManager
import android.os.Process.THREAD_PRIORITY_BACKGROUND
import android.os.SystemClock
import org.radarbase.util.CountedReference
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Process events based on a alarm. The events will be processed in a background Thread.
 * During processing in the provided Runnable,
 * check that [.isDone] remains `false`. Once it turns true, the Runnable should stop
 * processing. If wake is set to true, [android.Manifest.permission.WAKE_LOCK] should be
 * acquired in the Manifest.
 */
class OfflineProcessor(
    private val context: Context,
    private val config: ProcessorConfiguration,
) : Closeable {

    constructor(
        context: Context,
        config: ProcessorConfiguration.() -> Unit,
    ) : this(context, ProcessorConfiguration().apply(config))

    private val receiver: BroadcastReceiver
    private val pendingIntent: PendingIntent
    private val alarmManager: AlarmManager
    private val handler: SafeHandler

    private val requestName: String = requireNotNull(config.requestName) { "Cannot start processor without request name" }
    private val process: List<() -> Unit> = ArrayList(config.process)

    /** Whether the processing Runnable should stop execution.  */
    @get:Synchronized
    var isDone: Boolean = false
        private set

    private var didStart: Boolean = false

    val isStarted: Boolean
        get() = handler.compute { didStart }

    private val isRunning: Semaphore

    init {
        require(process.isNotEmpty()) { "Cannot start processor without processes" }

        this.isDone = false
        this.alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager

        handler = config.handlerReference.acquire()
        val intent = Intent(config.requestName)
        pendingIntent = PendingIntent.getBroadcast(
            context,
            requireNotNull(config.requestCode) { "Cannot start processor without request code" },
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT.toPendingIntentFlag(),
        )

        this.receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trigger()
            }
        }
        didStart = false
        isRunning = Semaphore(1)
    }

    /** Start processing.  */
    fun start(initializer: (() -> Unit)? = null) {
        handler.compute {
            check(config.intervalMillis > 0) { "Cannot start processing without an interval" }
        }
        handler.execute {
            didStart = true
            context.registerReceiver(this.receiver, IntentFilter(requestName))
            schedule()
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
            acquireWakeLock(context, requestName)
        } else null

        try {
            for (runnable in process) {
                handler.execute {
                    if (!isDone) {
                        try {
                            runnable()
                        } catch (ex: InterruptedException) {
                            logger.error("OfflineProcessor task was interrupted.", ex)
                        } catch (ex: Throwable) {
                            logger.error("OfflineProcessor task failed.", ex)
                        }
                    }
                    if (Thread.interrupted()) {
                        logger.debug("OfflineProcessor handler thread was interrupted but no blocking calls were invoked.")
                    }
                }
            }
        } catch (ex: RuntimeException) {
            logger.error("Handler thread is no longer running.", ex)
        } finally {
            handler.execute(true) {
                isRunning.release()
                wakeLock?.release()
            }
        }
    }

    /**
     * Change the processing interval to the given value.
     * @param duration time between processing.
     * @param timeUnit time unit to that duration is given with
     */
    fun interval(duration: Long, timeUnit: TimeUnit) {
        require(duration > 0L) { "Duration must be positive" }
        handler.execute(true) {
            if (config.interval(duration, timeUnit) && didStart) {
                schedule()
            }
        }
    }

    fun interval(duration: Duration) = interval(duration.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    private fun schedule() {
        val runImmediately = Debug.isDebuggerConnected()
        val firstAlarm: Long = if (runImmediately) {
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
        handler.execute {
            if (didStart) {
                alarmManager.cancel(pendingIntent)
                try {
                    context.unregisterReceiver(receiver)
                } catch (ex: IllegalStateException) {
                    logger.warn(
                        "Cannot unregister OfflineProcessor {}, it was most likely not completely started: {}",
                        config.requestName,
                        ex.message,
                    )
                }
            }
        }

        try {
            isRunning.acquire()
            config.handlerReference.release()
        } catch (e: InterruptedException) {
            logger.error("Interrupted while waiting for processing to finish.")
            Thread.currentThread().interrupt()
        }
    }

    data class ProcessorConfiguration(
        var requestCode: Int? = null,
        var requestName: String? = null,
        var process: List<() -> Unit> = emptyList(),
        @get:Synchronized
        var intervalMillis: Long = -1,
        var wake: Boolean = true,
        var handlerReference: CountedReference<SafeHandler> = DEFAULT_HANDLER_THREAD
    ) {
        @Synchronized
        fun interval(duration: Long, unit: TimeUnit): Boolean {
            require(duration > 0L) { "Duration must be positive" }
            val oldInterval = intervalMillis
            intervalMillis = unit.toMillis(duration)
            return oldInterval != intervalMillis
        }

        fun interval(duration: Duration) = interval(duration.inWholeMilliseconds, TimeUnit.MILLISECONDS)

        fun handler(value: SafeHandler) {
            handlerReference = value.toCountedReference()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OfflineProcessor::class.java)
        private val safeHandler = SafeHandler("OfflineProcessor", THREAD_PRIORITY_BACKGROUND)
        private val DEFAULT_HANDLER_THREAD = safeHandler.toCountedReference()

        private fun SafeHandler.toCountedReference() = CountedReference({
            apply { start() }
        }, {
            stop()
        })

        @SuppressLint("WakelockTimeout")
        private fun acquireWakeLock(
            context: Context,
            requestName: String?,
        ): PowerManager.WakeLock? {
            val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager? ?: return null
            val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, requestName)
            lock.acquire()
            return lock
        }
    }
}
