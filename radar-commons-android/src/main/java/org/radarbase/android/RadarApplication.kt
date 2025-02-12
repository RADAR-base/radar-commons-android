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

package org.radarbase.android

import android.app.Activity
import android.app.Service
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import org.radarbase.android.source.SourceService
import org.radarbase.android.util.NotificationHandler

/** Provides the name and some metadata of the main activity  */
interface RadarApplication {
    val notificationHandler: NotificationHandler

    val configuration: RadarConfiguration

    val mainActivity: Class<out Activity>

    val loginActivity: Class<out Activity>

    val authService: Class<out Service>

    val radarService: Class<out Service>
        get() = RadarService::class.java

    fun configureProvider(bundle: Bundle)
    fun onSourceServiceInvocation(service: SourceService<*>, bundle: Bundle, isNew: Boolean)
    fun onSourceServiceDestroy(service: SourceService<*>)

    companion object {
        val Context.radarApp: RadarApplication
            get() = applicationContext as RadarApplication

        val Context.radarConfig: RadarConfiguration
            get() = radarApp.configuration
    }
}
