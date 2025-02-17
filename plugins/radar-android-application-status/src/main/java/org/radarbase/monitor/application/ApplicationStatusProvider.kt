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

import android.content.Intent
import org.radarbase.android.BuildConfig
import org.radarbase.android.RadarService
import org.radarbase.android.source.SourceProvider
import org.radarbase.monitor.application.ui.ApplicationMetricsActivity

open class ApplicationStatusProvider(radarService: RadarService) : SourceProvider<ApplicationState>(radarService) {
    override val description: String?
        get() = radarService.getString(R.string.application_status_description)

    override val serviceClass: Class<ApplicationStatusService> = ApplicationStatusService::class.java

    override val pluginNames = listOf(
            "application_status",
            "application",
            "application_monitor",
            ".application.ApplicationServiceProvider",
            "org.radarbase.monitor.application.ApplicationStatusProvider",
            "org.radarcns.application.ApplicationStatusProvider")

    override val isDisplayable: Boolean = false

    override val permissionsNeeded: List<String> = emptyList()

    override val displayName: String
        get() = radarService.getString(R.string.applicationServiceDisplayName)

    override val sourceProducer: String = "RADAR"

    override val sourceModel: String = "pRMT"

    override val version: String = BuildConfig.VERSION_NAME

    override val actions: List<Action>
        get() = listOf(Action(radarService.getString(R.string.start_app_metrics_activity)){
            startActivity(Intent(this, ApplicationMetricsActivity::class.java))
        })
}
