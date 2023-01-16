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

package org.radarbase.android.kafka

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.radarbase.android.util.DelayedRetry
import org.radarbase.android.util.SafeHandler
import org.radarbase.producer.AuthenticationException
import org.radarbase.producer.KafkaSender
import org.radarbase.producer.rest.ConnectionState
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Checks the connection of a sender. It does so using two mechanisms: a regular
 * heartbeat signal when the connection is assumed to be
 * present, and a exponential back-off mechanism if the connection is severed. If the connection is
 * assessed to be present through another mechanism, [didConnect] should be called,
 * conversely, if it is assessed to be severed, [didDisconnect] should be
 * called.
 */
internal class KafkaConnectionChecker(
    private val sender: KafkaSender,
    private val listener: ServerStatusListener,
    heartbeatSecondsInterval: Long,
) {
    private var future: SafeHandler.HandlerFuture? = null
    private val heartbeatInterval: Long = heartbeatSecondsInterval * 1000L
    private val retryDelay = DelayedRetry(INCREMENTAL_BACKOFF_MILLISECONDS, MAX_BACKOFF_MILLISECONDS)
    private var lastConnection: Long = -1L

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected
    private val _serverStatus = MutableStateFlow(ServerStatus.DISCONNECTED)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus

    suspend fun initialize() {
        if (sender.connectionState.first() == ConnectionState.State.CONNECTED) {
            _isConnected.value = false
            didConnect()
        } else {
            _isConnected.value = true
            didDisconnect(null)
        }
    }

    /**
     * Check whether the connection was closed and try to reconnect.
     */
    private suspend fun makeCheck() {
        try {
            if (!isConnected.value) {
                if (sender.resetConnection()) {
                    didConnect()
                    _serverStatus.value = ServerStatus.CONNECTED
                    logger.info("Sender reconnected")
                } else {
                    retry()
                }
            } else if (SystemClock.uptimeMillis() - lastConnection > 15_000L) {
                if (sender.connectionState.first() == ConnectionState.State.CONNECTED || sender.resetConnection()) {
                    didConnect()
                } else {
                    didDisconnect(null)
                }
            }
        } catch (ex: AuthenticationException) {
            didDisconnect(ex)
        }
    }

    /** Check the connection as soon as possible.  */
    suspend fun check() {
        makeCheck()
    }

    /** Retry the connection with an incremental backoff.  */
    private fun retry() {
        future = mHandler.delay(retryDelay.nextDelay(), ::makeCheck)
    }

    /** Signal that the sender successfully connected.  */
    fun didConnect() {
        mHandler.executeReentrant {
            lastConnection = SystemClock.uptimeMillis()
            if (isConnectedBacking.compareAndSet(false, true)) {
                future?.cancel()
                future = mHandler.repeat(heartbeatInterval, ::makeCheck)
            }
            retryDelay.reset()
        }
    }

    /**
     * Signal that the Kafka REST sender has disconnected.
     * @param ex exception the sender disconnected with, may be null
     */
    fun didDisconnect(ex: Exception?) {
        mHandler.executeReentrant {
            logger.warn("Sender is disconnected", ex)

            if (isConnectedBacking.compareAndSet(true, false)) {
                future?.cancel()
                future = mHandler.delay(INCREMENTAL_BACKOFF_MILLISECONDS, ::makeCheck)
                if (ex is AuthenticationException) {
                    logger.warn("Failed to authenticate to server: {}", ex.message)
                    listener.serverStatus = ServerStatus.UNAUTHORIZED
                } else {
                    listener.serverStatus = ServerStatus.DISCONNECTED
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(KafkaConnectionChecker::class.java)

        private const val INCREMENTAL_BACKOFF_MILLISECONDS = 60 * 1000L // 1 minute
        private const val MAX_BACKOFF_MILLISECONDS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
