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

package org.radarbase.passive.google.places

import android.os.Process
import com.google.android.libraries.places.api.Places
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import org.radarbase.android.util.ChangeRunner
import org.radarbase.android.util.SafeHandler
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class GooglePlacesService: SourceService<GooglePlacesState>() {
    private val apiKey: ChangeRunner<String> = ChangeRunner(PLACES_API_KEY_DEFAULT)
    private lateinit var placeHandler: SafeHandler

    override fun onCreate() {
        super.onCreate()
        placeHandler = SafeHandler.getInstance("Google-Places-Handler", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
        }
    }

    override fun configureSourceManager(manager: SourceManager<GooglePlacesState>, config: SingleRadarConfiguration) {
        manager as GooglePlacesManager

        apiKey.applyIfChanged(config.getString(PLACES_API_KEY, PLACES_API_KEY_DEFAULT)) {
           placeHandler.execute {
                   try {
                       Places.initialize(this.applicationContext, it)
                       manager.updateApiKey(it)
                   } catch (ex: IllegalArgumentException) {
                       logger.error("API Key can't be empty")
                   }
               }
           }

        manager.placesFetchInterval(config.getLong(PLACES_FETCH_INTERVAL, PLACES_FETCH_INTERVAL_DEFAULT), TimeUnit.SECONDS)
        manager.shouldFetchPlaceId(config.getBoolean(FETCH_PLACES_ID_KEY, false))
        manager.shouldFetchAdditionalInfo(config.getBoolean(FETCH_PLACES_ADDITIONAL_INFO, false))
        manager.limitByPlacesCount(config.getInt(FETCH_PLACE_COUNT_BOUND, FETCH_PLACE_COUNT_NUMBER_DEFAULT))
        manager.limitByPlacesLikelihood(config.getFloat(FETCH_PLACE_LIKELIHOOD_BOUND, FETCH_PLACE_LIKELIHOOD_BOUND_DEFAULT.toFloat()).toDouble())

    }

    override val defaultState: GooglePlacesState
        get() = GooglePlacesState()

    override fun createSourceManager(): GooglePlacesManager = GooglePlacesManager(this, apiKey.lastResult, placeHandler)

    override val isBluetoothConnectionRequired: Boolean = false

    override fun onDestroy() {
        super.onDestroy()
        placeHandler.stop()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GooglePlacesService::class.java)

        const val PLACES_API_KEY = "places_api_key"
        const val PLACES_FETCH_INTERVAL = "places_interval_seconds"
        const val FETCH_PLACES_ID_KEY = "should_fetch_places_id"
        const val FETCH_PLACES_ADDITIONAL_INFO = "should_fetch_additional_places_info"
        const val FETCH_PLACE_LIKELIHOOD_BOUND = "fetch_place_likelihoods_bound"
        const val FETCH_PLACE_COUNT_BOUND = "fetch_place_likelihoods_count"

        const val PLACES_FETCH_INTERVAL_DEFAULT = 600L
        const val PLACES_API_KEY_DEFAULT = ""
        const val FETCH_PLACE_LIKELIHOOD_BOUND_DEFAULT = -1.0
        const val FETCH_PLACE_COUNT_NUMBER_DEFAULT = -1
    }
}