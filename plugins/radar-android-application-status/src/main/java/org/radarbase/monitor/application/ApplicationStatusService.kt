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

    override fun createSourceManager() = ApplicationStatusManager(this)

    override fun configureSourceManager(manager: SourceManager<ApplicationState>, config: SingleRadarConfiguration) {
        manager as ApplicationStatusManager
        manager.setApplicationStatusUpdateRate(config.getLong(UPDATE_RATE, UPDATE_RATE_DEFAULT), TimeUnit.SECONDS)
        manager.setTzUpdateRate(config.getLong(TZ_UPDATE_RATE, TZ_UPDATE_RATE_DEFAULT), TimeUnit.SECONDS)
        manager.setVerificationUpdateRate(config.getLong(VERIFICATION_UPDATE_RATE, VERIFICATION_UPDATE_RATE_DEFAULT), TimeUnit.SECONDS)
        manager.ntpServer = config.optString(NTP_SERVER_CONFIG)
        manager.isProcessingIp = config.getBoolean(SEND_IP, false)
        manager.metricsBatchSize = config.getLong(APPLICATION_METRICS_BATCH_SIZE, APPLICATION_METRICS_BATCH_SIZE_DEFAULT)
        manager.metricsRetentionSize = config.getLong(APPLICATION_METRICS_DATA_RETENTION_COUNT, APPLICATION_METRICS_DATA_RETENTION_COUNT_DEFAULT)
        manager.metricsRetentionTime = config.getLong(APPLICATION_METRICS_RETENTION_TIME, APPLICATION_METRICS_RETENTION_TIME_DEFAULT)
        manager.uiStatusUpdateRate = config.getLong(APPLICATION_PLUGIN_UI_COUNT_UPDATE_RATE, APPLICATION_PLUGIN_UI_COUNT_UPDATE_RATE_DEFAULT)
    }

    companion object {
        private const val UPDATE_RATE = "application_status_update_rate"
        private const val TZ_UPDATE_RATE = "application_time_zone_update_rate"
        private const val VERIFICATION_UPDATE_RATE = "application_time_zone_update_rate"
        private const val APPLICATION_METRICS_BATCH_SIZE = "application_metrics_buffer_size"
        private const val APPLICATION_METRICS_RETENTION_TIME = "application_metrics_retention_time_value"
        private const val APPLICATION_METRICS_DATA_RETENTION_COUNT = "application_metrics_data_retention_count"
        private const val APPLICATION_METRICS_BATCH_SIZE_DEFAULT = 5L
        private const val APPLICATION_METRICS_RETENTION_TIME_DEFAULT = 7 * 86400L // seconds == 1 day
        private const val APPLICATION_METRICS_DATA_RETENTION_COUNT_DEFAULT = 10000L // 10,000 counts per topic
        private const val APPLICATION_PLUGIN_UI_COUNT_UPDATE_RATE = "application_plugin_ui_count_update_rate"
        private const val APPLICATION_PLUGIN_UI_COUNT_UPDATE_RATE_DEFAULT = 60L // 1 minute
        private const val SEND_IP = "application_send_ip"
        internal const val UPDATE_RATE_DEFAULT = 300L // seconds == 5 minutes
        internal const val VERIFICATION_UPDATE_RATE_DEFAULT = 300L // seconds == 5 minutes
        internal const val TZ_UPDATE_RATE_DEFAULT = 86400L // seconds == 1 day
        private const val NTP_SERVER_CONFIG = "ntp_server"
    }
}
