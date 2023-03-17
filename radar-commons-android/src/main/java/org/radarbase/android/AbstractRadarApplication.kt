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
import android.os.Bundle
import androidx.annotation.CallSuper
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.config.CombinedRadarConfig
import org.radarbase.android.config.LocalConfiguration
import org.radarbase.android.config.RemoteConfig
import org.radarbase.android.source.SourceService
import org.radarbase.android.util.NotificationHandler
import org.slf4j.impl.HandroidLoggerAdapter
import java.util.concurrent.ConcurrentSkipListSet

/** Provides the name and some metadata of the main activity  */
abstract class AbstractRadarApplication : Application(), RadarApplication {
    private lateinit var lifecycleListener: ActivityLifecycleCallbacks
    private lateinit var innerNotificationHandler: NotificationHandler

    override val notificationHandler: NotificationHandler
        get() = innerNotificationHandler.apply { onCreate() }

    override lateinit var configuration: RadarConfiguration

    override val activeActivities: MutableSet<Class<out Activity>> = ConcurrentSkipListSet()

    override fun configureProvider(bundle: Bundle) {}
    override fun onSourceServiceInvocation(service: SourceService<*>, bundle: Bundle, isNew: Boolean) {}
    override fun onSourceServiceDestroy(service: SourceService<*>) {}

    @CallSuper
    override fun onCreate() {
        super.onCreate()
        setupLogging()
        lifecycleListener = object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                activeActivities += activity.javaClass
            }

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                activeActivities -= activity.javaClass
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        registerActivityLifecycleCallbacks(lifecycleListener)
        configuration = createConfiguration()
        innerNotificationHandler = NotificationHandler(this)
    }

    protected open fun setupLogging() {
        // initialize crashlytics
        HandroidLoggerAdapter.DEBUG = BuildConfig.DEBUG
        HandroidLoggerAdapter.APP_NAME = packageName
        HandroidLoggerAdapter.enableLoggingToFirebaseCrashlytics()
    }

    /**
     * Create a RadarConfiguration object. At implementation, the Firebase version needs to be set
     * for this as well as the defaults.
     *
     * @return configured RadarConfiguration
     */
    protected open fun createConfiguration(): RadarConfiguration {
        return CombinedRadarConfig(
            LocalConfiguration(this),
            createRemoteConfiguration(),
            ::createDefaultConfiguration
        )
    }

    /**
     * Create remote configuration objects. For example,
     * [org.radarbase.android.config.FirebaseRemoteConfiguration].
     *
     * @return configured RadarConfiguration
     */
    protected open fun createRemoteConfiguration(): List<RemoteConfig> = listOf()

    /**
     * Create default configuration for the app.
     */
    protected open fun createDefaultConfiguration(): Map<String, String> = mapOf()
}
