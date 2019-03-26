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

package org.radarbase.passive.empatica

import android.os.Process
import com.empatica.empalink.EmpaDeviceManager
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.device.DeviceManager
import org.radarbase.android.device.DeviceService
import org.radarbase.android.util.SafeHandler
import org.slf4j.LoggerFactory

/**
 * A service that manages a E4DeviceManager and a TableDataHandler to send store the data of an
 * Empatica E4 and send it to a Kafka REST proxy.
 */
class E4Service : DeviceService<E4DeviceStatus>() {
    private lateinit var mHandler: SafeHandler
    private lateinit var empaManager: EmpaDeviceManager

    override fun onCreate() {
        super.onCreate()
        mHandler = SafeHandler("E4-device-handler", Process.THREAD_PRIORITY_MORE_FAVORABLE)

        val delegate = E4Delegate(this)
        empaManager = EmpaDeviceManager(this, delegate, delegate, delegate)
    }

    override fun createDeviceManager() = E4DeviceManager(this, empaManager, mHandler)

    override fun configureDeviceManager(manager: DeviceManager<E4DeviceStatus>, configuration: RadarConfiguration) {
        (manager as E4DeviceManager).apiKey = configuration.getString(EMPATICA_API_KEY)
    }

    override val defaultState = E4DeviceStatus()

    override fun onDestroy() {
        super.onDestroy()

        try {
            empaManager.cleanUp()
        } catch (ex: RuntimeException) {
            logger.error("Failed to clean up Empatica manager", ex)
        }
        mHandler.stop()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(E4Service::class.java)

        private const val EMPATICA_API_KEY = "empatica_api_key"
    }
}
