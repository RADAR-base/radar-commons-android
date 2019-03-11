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
import android.app.Application
import android.app.Service
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.annotation.CallSuper
import com.crashlytics.android.Crashlytics
import io.fabric.sdk.android.Fabric
import org.radarbase.android.device.DeviceService
import org.slf4j.impl.HandroidLoggerAdapter

/** Provides the name and some metadata of the main activity  */
abstract class RadarApplication : Application() {
    private lateinit var notificationHandler: org.radarbase.android.util.NotificationHandler
    lateinit var configuration: org.radarbase.android.RadarConfiguration
        private set

    /** Large icon bitmap.  */
    abstract val largeIcon: Bitmap
    /** Small icon drawable resource ID.  */
    abstract val smallIcon: Int

    abstract val mainActivity: Class<out Activity>

    abstract val loginActivity: Class<out Activity>

    abstract val authService: Class<out Service>

    open val radarService: Class<out Service>
        get() = org.radarbase.android.RadarService::class.java

    open fun configureProvider(bundle: Bundle) {}
    open fun onDeviceServiceInvocation(service: DeviceService<*>, bundle: Bundle, isNew: Boolean) {}
    open fun onDeviceServiceDestroy(service: DeviceService<*>) {}

    @CallSuper
    override fun onCreate() {
        super.onCreate()
        setupLogging()
        configuration = createConfiguration()
        notificationHandler = org.radarbase.android.util.NotificationHandler(this)
    }

    protected open fun setupLogging() {
        // initialize crashlytics
        Fabric.with(this, Crashlytics())
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
        HandroidLoggerAdapter.APP_NAME = packageName
        HandroidLoggerAdapter.enableLoggingToCrashlytics()
    }

    /**
     * Create a RadarConfiguration object. At implementation, the Firebase version needs to be set
     * for this as well as the defaults.
     *
     * @return configured RadarConfiguration
     */
    protected abstract fun createConfiguration(): org.radarbase.android.RadarConfiguration

    companion object {
        /** Get a notification handler.  */
        fun getNotificationHandler(context: Context): org.radarbase.android.util.NotificationHandler {
            val applicationContext = context.applicationContext
            return if (applicationContext is org.radarbase.android.RadarApplication) {
                applicationContext.notificationHandler
            } else {
                org.radarbase.android.util.NotificationHandler(applicationContext)
            }
        }
    }
}
