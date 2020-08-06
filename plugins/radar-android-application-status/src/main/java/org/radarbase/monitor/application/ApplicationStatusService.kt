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

import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import java.util.concurrent.TimeUnit

class ApplicationStatusService : SourceService<ApplicationState>() {

    override val defaultState: ApplicationState
        get() = ApplicationState()

    override fun createSourceManager(): ApplicationStatusManager {
        return ApplicationStatusManager(this)
    }

    override fun configureSourceManager(manager: SourceManager<ApplicationState>, config: SingleRadarConfiguration) {
        (manager as ApplicationStatusManager).apply {
            setApplicationStatusUpdateRate(config.getLong(UPDATE_RATE, UPDATE_RATE_DEFAULT), TimeUnit.SECONDS)
            setTzUpdateRate(config.getLong(TZ_UPDATE_RATE, TZ_UPDATE_RATE_DEFAULT), TimeUnit.SECONDS)
            ntpServer = config.optString(NTP_SERVER_CONFIG)
            isProcessingIp = config.getBoolean(SEND_IP, false)
        }
    }

    companion object {
        private const val UPDATE_RATE = "application_status_update_rate"
        private const val TZ_UPDATE_RATE = "application_time_zone_update_rate"
        private const val SEND_IP = "application_send_ip"
        internal const val UPDATE_RATE_DEFAULT = 300L // seconds == 5 minutes
        internal const val TZ_UPDATE_RATE_DEFAULT = 86400L // seconds == 1 day
        private const val NTP_SERVER_CONFIG = "ntp_server"
    }
}
