package org.radarbase.android.util

import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.Keep
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue

class SafeHandler(val name: String, private val priority: Int) {
    private var handlerThread: HandlerThread? = null

    val isStarted: Boolean
        get() = handler != null

    @get:Synchronized
    var handler: Handler? = null
        private set

    @Synchronized
    fun start() {
        if (handler != null) {
            logger.warn("Tried to start SafeHandler multiple times.")
            return
        }

        handlerThread = HandlerThread(name, priority).also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    @Throws(InterruptedException::class, ExecutionException::class)
    fun await(runnable: Runnable) = compute { runnable.run() }

    @Throws(InterruptedException::class, ExecutionException::class)
    fun await(runnable: () -> Unit) = compute(runnable)

    @Throws(InterruptedException::class, ExecutionException::class)
    fun <T> compute(method: () -> T): T {
        if (Thread.currentThread() == handlerThread) {
            try {
                return method()
            } catch (ex: Exception) {
                throw ExecutionException(ex)
            }
        } else {
            val queue = SynchronousQueue<Any>()
            execute {
                try {
                    queue.put(method() ?: nullMarker)
                } catch (ex: Exception) {
                    queue.put(ExecutionException(ex))
                }
            }
            val result = queue.take()
            @Suppress("UNCHECKED_CAST")
            return when {
                result === nullMarker -> null
                result is ExecutionException -> throw result
                else -> result
            } as T
        }
    }

    private fun <T> doRun(callable: () -> T): T? {
        return try {
            callable()
        } catch (ex: Exception) {
            logger.error("Failed to run posted runnable", ex)
            null
        }
    }

    fun execute(runnable: Runnable) = execute(false, runnable::run)

    fun execute(runnable: () -> Unit) = execute(false, runnable)

    fun executeReentrant(runnable: () -> Unit) {
        if (Thread.currentThread() == handlerThread) {
            runnable()
        } else {
            execute(runnable)
        }
    }

    fun executeReentrant(runnable: Runnable) = executeReentrant(runnable::run)

    fun execute(defaultToCurrentThread: Boolean, runnable: Runnable) = execute(defaultToCurrentThread, runnable::run)

    fun execute(defaultToCurrentThread: Boolean, runnable: () -> Unit) {
        val didRun = synchronized(this) {
            handler?.post { doRun(runnable) }
        } ?: false

        if (!didRun && defaultToCurrentThread) {
            doRun(runnable)
        }
    }

    fun delay(delay: Long, runnable: Runnable): HandlerFuture? = delay(delay, runnable::run)

    @Synchronized
    fun delay(delay: Long, runnable: () -> Unit): HandlerFuture? {
        return handler?.let {
            val r = Runnable {
                doRun(runnable)
            }
            it.postDelayed(r, delay)
            HandlerFutureRef(r)
        }
    }

    fun repeatWhile(delay: Long, runnable: RepeatableRunnable): HandlerFuture? = repeatWhile(delay, runnable::runAndRepeat)

    fun repeatWhile(delay: Long, runnable: () -> Boolean): HandlerFuture? {
        return delay(delay) {
            if (runnable()) repeatWhile(delay, runnable)
        }
    }

    fun repeat(delay: Long, runnable: () -> Unit): HandlerFuture? {
        return delay(delay) {
            runnable()
            repeat(delay, runnable)
        }
    }

    fun stop(finalization: Runnable) = stop(finalization::run)

    @Synchronized
    fun stop(finalization: (() -> Unit)? = null) {
        handlerThread?.let { thread ->
            val oldHandler = handler
            handler = null
            finalization?.let {
                oldHandler?.post { doRun(it) } ?: doRun(it)
            }
            thread.quitSafely()
            handlerThread = null
        } ?: logger.warn("Tried to stop SafeHandler multiple times.")
    }

    interface RepeatableRunnable {
        fun runAndRepeat(): Boolean
    }

    @Keep
    interface HandlerFuture {
        @Throws(InterruptedException::class, ExecutionException::class)
        fun awaitNow()
        fun runNow()
        fun cancel()
    }

    private inner class HandlerFutureRef(val runnable: Runnable): HandlerFuture {
        override fun awaitNow() {
            synchronized(this@SafeHandler) {
                handler?.apply {
                    removeCallbacks(runnable)
                }
                await(runnable)
            }
        }
        override fun runNow() {
            synchronized(this@SafeHandler) {
                handler?.apply {
                    removeCallbacks(runnable)
                }
                executeReentrant(runnable)
            }
        }
        override fun cancel() {
            synchronized(this@SafeHandler) {
                handler?.removeCallbacks(runnable)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SafeHandler::class.java)
        private val nullMarker = object : Any() {}
    }
}
