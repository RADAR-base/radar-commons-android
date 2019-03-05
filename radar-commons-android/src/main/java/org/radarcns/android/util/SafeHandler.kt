package org.radarcns.android.util

import android.os.Handler
import android.os.HandlerThread
import org.slf4j.LoggerFactory

class SafeHandler(val name: String, val priority: Int) {
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

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

    fun post(runnable: Runnable) {
        post(runnable, false)
    }

    fun post(runnable: () -> Unit) {
        post(Runnable { runnable() }, false)
    }

    fun postReentrant(runnable: () -> Unit) {
        postReentrant(Runnable { runnable() })
    }

    fun postReentrant(runnable: Runnable) {
        if (Thread.currentThread() == handlerThread) {
            runnable.run()
        } else {
            post(runnable)
        }
    }

    fun post(runnable: Runnable, defaultToCurrentThread: Boolean) {
        val didRun = synchronized(this) {
            handler?.post(runnable)
        } ?: false

        if (!didRun && defaultToCurrentThread) {
            runnable.run()
        }
    }

    @Synchronized
    fun postDelayed(runnable: Runnable, delay: Long): HandlerFuture? {
        return handler?.let {
            it.postDelayed(runnable, delay)
            HandlerFutureRef(runnable)
        }
    }

    @Synchronized
    fun postDelayed(runnable: RepeatableRunnable, delay: Long): HandlerFuture? {
        return postDelayed(Runnable {
            if (runnable.runAndRepeat()) postDelayed(runnable, delay)
        }, delay)
    }

    @Synchronized
    fun postDelayed(runnable: () -> Boolean, delay: Long): HandlerFuture? {
        return postDelayed(Runnable {
            if (runnable()) postDelayed(runnable, delay)
        }, delay)
    }

    fun stop(finalization: () -> Unit) {
        stop(Runnable { finalization() })
    }

    @Synchronized
    fun stop(finalization: Runnable) {
        handlerThread?.also { thread ->
            val oldHandler = handler
            handler = null
            if (oldHandler == null) {
                finalization.run()
            } else {
                oldHandler.post(finalization)
            }
            thread.quitSafely()
            handlerThread = null
        } ?: logger.warn("Tried to stop SafeHandler multiple times.")
    }

    interface RepeatableRunnable {
        fun runAndRepeat(): Boolean
    }

    interface HandlerFuture {
        fun postNow()
        fun cancel()
    }

    private inner class HandlerFutureRef(val runnable: Runnable): HandlerFuture {
        override fun postNow() {
            synchronized(this@SafeHandler) {
                handler?.apply {
                    removeCallbacks(runnable)
                }
                postReentrant(runnable)
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
    }
}
