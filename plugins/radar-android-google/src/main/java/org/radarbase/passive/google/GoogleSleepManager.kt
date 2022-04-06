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

package org.radarbase.passive.google

import android.app.usage.UsageEvents
import android.app.usage.UsageEvents.Event.*
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.Build
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.PhoneInteractionState
import org.radarcns.passive.phone.PhoneUsageEvent
import org.radarcns.passive.phone.PhoneUserInteraction
import org.radarcns.passive.phone.UsageEventType
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit

class GoogleSleepManager(context: GoogleSleepService) : AbstractSourceManager<GoogleSleepService, BaseSourceState>(context) {

    private val googleSleepProcessor: OfflineProcessor


    init {
        name = service.getString(R.string.googleSleepServiceDisplayName)
        googleSleepProcessor = OfflineProcessor(context) {
            process = listOf(this@GoogleSleepManager::processSleepUpdates)
            requestCode = SLEEP_UPDATE_REQUEST_CODE
            requestName = ACTION_UPDATE_SLEEP
            wake = false
        }
    }

    override fun start(acceptableIds: Set<String>) {
        status = SourceStatusListener.Status.READY
        register()

        googleSleepProcessor.start()

        status = SourceStatusListener.Status.CONNECTED
    }

    private fun processSleepUpdates() {

    }

    override fun onClose() {
        googleSleepProcessor.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GoogleSleepManager::class.java)

        private const val ACTION_UPDATE_SLEEP = "org.radarbase.passive.google.GoogleSleepManager.ACTION_UPDATE_SLEEP"
        private const val SLEEP_UPDATE_REQUEST_CODE = 123456
    }
}
