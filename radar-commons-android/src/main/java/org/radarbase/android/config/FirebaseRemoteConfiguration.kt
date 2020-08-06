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

package org.radarbase.android.config

import android.content.Context
import android.os.Handler
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.radarbase.android.RadarConfiguration
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("unused")
class FirebaseRemoteConfiguration(context: Context, inDevelopmentMode: Boolean, defaultSettings: Int) : RemoteConfig {
    private val firebase = FirebaseRemoteConfig.getInstance().apply {
        setDefaultsAsync(defaultSettings)
        isInDevelopmentMode = inDevelopmentMode
        fetch()
    }

    override var status: RadarConfiguration.RemoteConfigStatus = RadarConfiguration.RemoteConfigStatus.INITIAL
        private set(value) {
            field = value
            onStatusUpdateListener(value)
        }

    private val onFailureListener: OnFailureListener
    private val hasChange: AtomicBoolean = AtomicBoolean(false)
    override var onStatusUpdateListener: (RadarConfiguration.RemoteConfigStatus) -> Unit = {}

    override var lastFetch: Long = 0L
        private set
    override var cache: Map<String, String> = mapOf()
        private set

    private val handler: Handler = Handler()
    private val onFetchCompleteHandler: OnSuccessListener<Void>
    private var isInDevelopmentMode: Boolean = false
    private var firebaseKeys: Set<String> = HashSet(firebase.getKeysByPrefix(""))

    init {
        this.onFailureListener = OnFailureListener { ex ->
            logger.info("Failed to fetch Firebase config", ex)
            status = RadarConfiguration.RemoteConfigStatus.ERROR
        }

        this.onFetchCompleteHandler = OnSuccessListener {
            // Once the config is successfully fetched it must be
            // activated before newly fetched values are returned.
            firebase.activate()
                    .addOnSuccessListener {
                        cache = firebase.getKeysByPrefix("")
                                .mapNotNull { key ->
                                    firebase.getValue(key).asString()
                                            .takeUnless { it.isEmpty() }
                                            ?.let { Pair(key, it) }
                                }
                                .toMap()

                        status = RadarConfiguration.RemoteConfigStatus.FETCHED
                    }
                    .addOnFailureListener(onFailureListener)
        }

        val googleApi = GoogleApiAvailabilityLight.getInstance()
        status = if (googleApi.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            RadarConfiguration.RemoteConfigStatus.READY
        } else {
            RadarConfiguration.RemoteConfigStatus.UNAVAILABLE
        }
    }

    /**
     * Fetch the configuration from the firebase server.
     * @param maxCacheAge seconds
     * @return fetch task or null status is [RadarConfiguration.RemoteConfigStatus.UNAVAILABLE].
     */
    override fun doFetch(maxCacheAge: Long) {
        if (status == RadarConfiguration.RemoteConfigStatus.UNAVAILABLE) {
            return
        }
        val task = firebase.fetch(maxCacheAge)
        synchronized(this) {
            status = RadarConfiguration.RemoteConfigStatus.FETCHING
            task.addOnSuccessListener(onFetchCompleteHandler)
            task.addOnFailureListener(onFailureListener)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FirebaseRemoteConfiguration::class.java)
        private const val FIREBASE_FETCH_TIMEOUT_MS_DEFAULT = 12 * 60 * 60 * 1000L
    }
}
