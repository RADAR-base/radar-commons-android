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
        return handler?.let {
            val r = Runnable {
                if (runnable.runAndRepeat()) {
                    postDelayed(runnable, delay)
                }
            }
            it.postDelayed(r, delay)
            HandlerFutureRef(r)
        }
    }

    @Synchronized
    fun stop(finalization: Runnable) {
        if (handlerThread == null) {
            logger.warn("Tried to stop SafeHandler multiple times.")
            return
        }
        post(finalization, true)
        handlerThread!!.quitSafely()
        handlerThread = null
        handler = null
    }

    interface RepeatableRunnable {
        fun runAndRepeat(): Boolean
    }

    interface HandlerFuture {
        fun cancel()
    }

    private inner class HandlerFutureRef(val runnable: Runnable): HandlerFuture {
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
