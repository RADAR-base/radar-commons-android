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

package org.radarbase.passive.phone

import android.content.Context
import android.hardware.Sensor
import android.util.SparseIntArray
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * A service that manages the phone sensor manager and a TableDataHandler to send store the data of
 * the phone sensors and send it to a Kafka REST proxy.
 */
class PhoneSensorService : SourceService<PhoneState>() {
//    private lateinit var context: Context

    override val defaultState: PhoneState
        get() = PhoneState()

//    override fun createSourceManager() = PhoneSensorManager(this, context)
    override fun createSourceManager() = PhoneSensorManager(this)


    override fun configureSourceManager(manager: SourceManager<PhoneState>, config: SingleRadarConfiguration) {
        manager as PhoneSensorManager
    }
}

