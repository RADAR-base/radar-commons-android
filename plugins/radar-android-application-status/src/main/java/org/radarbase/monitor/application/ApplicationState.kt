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

package org.radarbase.monitor.application

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.radarbase.android.kafka.ServerStatus
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.storage.entity.NetworkStatusLog
import org.radarbase.android.storage.entity.SourceStatusLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ApplicationState : BaseSourceState() {

    private val sourceStatusMutex: Mutex = Mutex()
    private val networkStatusMutex: Mutex = Mutex()

    val sourceStatusBufferCount: AtomicInteger = AtomicInteger(0)
    val networkStatusBufferCount: AtomicInteger = AtomicInteger(0)

    @set:Synchronized
    var serverStatus: ServerStatus? = null
        @Synchronized get() = field ?: ServerStatus.DISCONNECTED

    @get:Synchronized
    var recordsSent = 0L
        private set

    val cachedRecords: MutableMap<String, Long> = ConcurrentHashMap()
    val recordsSentPerTopic: MutableMap<String, Long> = ConcurrentHashMap()
    private val sourceStatusBuffer: MutableList<SourceStatusLog> = mutableListOf()
    private val networkStatusBuffer: MutableList<NetworkStatusLog> = mutableListOf()

    suspend fun addSourceStatus(status: SourceStatusLog) {
        sourceStatusMutex.withLock {
            sourceStatusBuffer.add(status)
        }
    }

    suspend fun clearSourceStatuses() {
        sourceStatusMutex.withLock {
            sourceStatusBuffer.clear()
        }
    }

    suspend fun clearNetworkStatuses() {
        networkStatusMutex.withLock {
            networkStatusBuffer.clear()
        }
    }

    suspend fun getSourceStatuses(): List<SourceStatusLog> = sourceStatusMutex.withLock {
        sourceStatusBuffer
    }
    
    suspend fun addNetworkStatus(status: NetworkStatusLog) = networkStatusMutex.withLock {
        networkStatusBuffer.add(status)
    }
    
    suspend fun getNetworkStatus(): List<NetworkStatusLog> = networkStatusMutex.withLock {
        networkStatusBuffer
    }

    @Synchronized
    fun addRecordsSent(nRecords: Long) {
        recordsSent += nRecords
    }
}
