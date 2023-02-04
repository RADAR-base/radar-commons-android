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

import org.radarbase.android.kafka.ServerStatusListener
import org.radarbase.android.source.BaseSourceState
import java.util.concurrent.ConcurrentHashMap

class ApplicationState : BaseSourceState() {
    @set:Synchronized
    var serverStatus: ServerStatusListener.Status? = null
        @Synchronized get() = field ?: ServerStatusListener.Status.DISCONNECTED

    @get:Synchronized
    var recordsSent = 0L
        private set

    @get:Synchronized
    var recordsSentPerTopic = 0L
    private set

    @get:Synchronized
    var topicName:String? = ""
    private set

    @get:Synchronized
    var pluginName:String? = ""
    private set

    @get:Synchronized
    var pluginStatus:String? = ""
    private set

    @get:Synchronized
    var error:String? = ""
        private set

    val cachedRecords: MutableMap<String, Long> = ConcurrentHashMap()

    @Synchronized
    fun addRecordsSent(nRecords: Long) {
        recordsSent += nRecords
    }

    @Synchronized
    fun recordSentPerTopic(nRecordsTopic:Long){
        recordsSentPerTopic= nRecordsTopic
    }

    @Synchronized
    fun topicInfo(name:String?){
        topicName = name
    }

    @Synchronized
    fun getPluginName(name:String?){
        pluginName = name
    }

    @Synchronized
    fun getPluginStatus(status:String?){
        pluginStatus = status
    }

    @Synchronized
    fun getError(error:String?){
        pluginStatus = error
    }

}
