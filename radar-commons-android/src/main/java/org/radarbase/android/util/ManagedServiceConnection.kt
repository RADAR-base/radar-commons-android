package org.radarbase.android.util

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Context.BIND_AUTO_CREATE
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.radarbase.android.util.ManagedServiceConnection.BoundService.Companion.unbind
import org.slf4j.LoggerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.log

open class ManagedServiceConnection<T: IBinder>(
    val context: Context,
    private val cls: Class<out Service>,
    private val binderCls: Class<T>,
) {
    private val unbound = Unbound<T>()
    private val _state: MutableStateFlow<BindState<T>> = MutableStateFlow(unbound)
    val state: StateFlow<BindState<T>>
        get() = _state

    var bindFlags = BIND_AUTO_CREATE
    val bindMutex: Mutex = Mutex()

    suspend fun bind(): BoundService<T> = bindMutex.withLock {
        coroutineScope {
            val service = suspendCoroutine { continuation ->
                val connection: ServiceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        continuation.resume(
                            BoundService(
                                name,
                                checkNotNull(binderCls.cast(service)),
                                this
                            )
                        )
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        _state.value = unbound
                    }
                }

                val boundResult: Boolean = context.bindService(Intent(context, cls), connection, bindFlags)
                if (!boundResult) {
                    throw IllegalStateException("Cannot bind ${context.javaClass.simpleName} to service $cls. Bind Service returned: $boundResult ")
                } else {
                    logger.trace("In ManagedServiceConnection: Bound service {} to {}", context.javaClass.simpleName, cls)
                }
            }
            _state.value = service
            service
        }
    }

    open suspend fun applyBinder(callback: suspend T.() -> Unit) {
        val currentValue = state.value
        if (currentValue is BoundService<T>) {
            currentValue.binder.callback()
        }
    }

    fun unbind(): Boolean {
        val currentState = state.value
        return if (currentState is BoundService<T>) {
            logger.trace("In ManagedServiceConnection: Unbinding service {} from {}", context.javaClass.simpleName, currentState.componentName.className)
            context.unbind(currentState)
            _state.value = unbound
            true
        } else {
            logger.warn("Connection was never bound")
            false
        }
    }

    class Unbound<T> : BindState<T>()
    class BoundService<T>(
        val componentName: ComponentName,
        val binder: T,
        private val conn: ServiceConnection,
    ) : BindState<T>() {
        companion object {
            fun Context.unbind(service: BoundService<*>) {
                try {
                    unbindService(service.conn)
                } catch (ex: Exception) {
                    logger.error("Failed to unbind {} from {}", service.componentName.className, this::class.simpleName)
                    throw ex
                }
            }
        }
    }

    override fun toString(): String = "ManagedServiceConnection<${cls.simpleName}>"

    companion object {
        private val logger = LoggerFactory.getLogger(ManagedServiceConnection::class.java)

        inline fun <reified T: IBinder> Context.serviceConnection(
            serviceClass: Class<out Service>,
        ): ManagedServiceConnection<T> = ManagedServiceConnection(
            this,
            serviceClass,
            T::class.java,
        )
    }
}
