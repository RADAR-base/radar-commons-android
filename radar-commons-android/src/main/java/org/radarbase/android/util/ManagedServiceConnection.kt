package org.radarbase.android.util

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.radarbase.android.RadarApplication
import org.slf4j.LoggerFactory

open class ManagedServiceConnection<T: IBinder>(val context: Context, private val cls: Class<out Service>) {
    var isBound: Boolean = false
    var binder: T? = null
    val onBoundListeners: MutableList<(T) -> Unit> = mutableListOf()
    val onUnboundListeners: MutableList<(T) -> Unit> = mutableListOf()
    var bindFlags: Int = BIND_AUTO_CREATE

    private val app = context.applicationContext as org.radarbase.android.RadarApplication
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            service?.let { b ->
                @Suppress("UNCHECKED_CAST")
                (b as? T)
                        ?.also { binder = it }
                        ?.also { bound -> onBoundListeners.forEach { it(bound) } }
                        ?: throw java.lang.IllegalStateException("Cannot cast binder to type T")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    fun bind(): Boolean {
        return try {
            context.bindService(Intent(context, cls), connection, bindFlags)
        } catch (ex: IllegalStateException) {
            false
        }.also { bound -> isBound = bound }
    }

    open fun applyBinder(callback: (T) -> Unit) {
        binder?.also(callback)
    }

    fun unbind(): Boolean {
        if (isBound) {
            binder?.also { bound -> onUnboundListeners.forEach { it(bound) } }
            binder = null
            isBound = false
            try {
                context.unbindService(connection)
                return true
            } catch (ex: IllegalStateException) {
                logger.warn("Cannot unbind connection that was not bound.")
            }
        } else {
            logger.warn("Connection was never bound")
        }
        return false
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ManagedServiceConnection::class.java)
    }
}
