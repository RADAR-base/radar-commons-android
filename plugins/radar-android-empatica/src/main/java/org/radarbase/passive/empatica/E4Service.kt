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
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.SafeHandler
import org.slf4j.LoggerFactory

/**
 * A service that manages a E4DeviceManager and a TableDataHandler to send store the data of an
 * Empatica E4 and send it to a Kafka REST proxy.
 */
class E4Service : SourceService<E4State>() {
    private lateinit var mHandler: SafeHandler
    private var empaManager: EmpaDeviceManager? = null
    private var apiKey: String? = null
    private var hasInvalidApiKey: Boolean = false
    private val delegate = E4Delegate(this)


    override fun onCreate() {
        super.onCreate()
        mHandler = SafeHandler.getInstance("E4-device-handler", Process.THREAD_PRIORITY_MORE_FAVORABLE)
        mHandler.start()
    }

    override fun createSourceManager(): E4Manager {
        val localE4Manager = empaManager ?: EmpaDeviceManager(this, delegate, delegate, delegate)
        return E4Manager(this, localE4Manager, mHandler)
    }

    override fun configureSourceManager(manager: SourceManager<E4State>, config: SingleRadarConfiguration) {
        manager as E4Manager
        manager.notifyDisconnect(config.getBoolean(NOTIFY_DISCONNECT, NOTIFY_DISCONNECT_DEFAULT))
        config.optString(EMPATICA_API_KEY)?.let { newApiKey ->
            when {
                apiKey == null -> {
                    apiKey = newApiKey
                    manager.updateApiKey(apiKey)
                }
                apiKey != newApiKey -> {
                    logger.error("Cannot change E4 API key. Please restart the app.")
                    hasInvalidApiKey = true
                    manager.startDisconnect()
                }
                else -> manager.updateApiKey(apiKey)
            }
        }
    }

    override fun sourceStatusUpdated(manager: SourceManager<*>, status: SourceStatusListener.Status) {
        if (status == SourceStatusListener.Status.DISCONNECTED && hasInvalidApiKey) {
            mHandler.execute {
                try {
                    empaManager?.let {
                        empaManager = null
                        it.cleanUp()
                    }
                    hasInvalidApiKey = false
                    apiKey = null
                } catch (ex: RuntimeException) {
                    logger.error("Failed to clean up Empatica manager", ex)
                }
                super.sourceStatusUpdated(manager, status)
            }
        } else {
            super.sourceStatusUpdated(manager, status)
        }
    }

    override val defaultState = E4State()

    override fun onDestroy() {
        super.onDestroy()

        mHandler.stop {
            try {
                empaManager?.cleanUp()
            } catch (ex: RuntimeException) {
                logger.error("Failed to clean up Empatica manager", ex)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(E4Service::class.java)

        private const val EMPATICA_API_KEY = "empatica_api_key"

        private const val NOTIFY_DISCONNECT = "empatica_notify_disconnect"
        private const val NOTIFY_DISCONNECT_DEFAULT = false
    }
}
