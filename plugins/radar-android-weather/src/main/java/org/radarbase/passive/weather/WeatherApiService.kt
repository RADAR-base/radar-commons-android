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

package org.radarbase.passive.weather

import okhttp3.OkHttpClient
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceManager
import org.radarbase.android.device.DeviceService
import org.radarbase.config.ServerConfig
import org.radarbase.producer.rest.RestClient
import org.radarbase.passive.weather.WeatherApiManager.Companion.SOURCE_OPENWEATHERMAP
import java.util.concurrent.TimeUnit

class WeatherApiService : DeviceService<BaseDeviceState>() {

    private lateinit var client: OkHttpClient

    override val defaultState: BaseDeviceState
        get() = BaseDeviceState()

    override fun onCreate() {
        super.onCreate()
        client = RestClient.global()
                .server(ServerConfig())
                .build()
                .httpClient
    }

    override fun createDeviceManager(): WeatherApiManager {
        return WeatherApiManager(this, client)
    }

    override fun configureDeviceManager(manager: DeviceManager<BaseDeviceState>, configuration: RadarConfiguration) {
        val weatherManager = manager as WeatherApiManager
        weatherManager.setQueryInterval(configuration.getLong(WEATHER_QUERY_INTERVAL, WEATHER_QUERY_INTERVAL_DEFAULT), TimeUnit.SECONDS)
        weatherManager.setSource(
                configuration.getString(WEATHER_API_KEY, WEATHER_API_KEY_DEFAULT),
                configuration.getString(WEATHER_API_SOURCE, WEATHER_API_SOURCE_DEFAULT))
    }

    companion object {
        private const val WEATHER_QUERY_INTERVAL = "weather_query_interval_seconds"
        private const val WEATHER_API_SOURCE = "weather_api_source"
        private const val WEATHER_API_KEY = "weather_api_key"

        internal val WEATHER_QUERY_INTERVAL_DEFAULT = TimeUnit.HOURS.toSeconds(3)
        internal const val WEATHER_API_SOURCE_DEFAULT = SOURCE_OPENWEATHERMAP
        internal const val WEATHER_API_KEY_DEFAULT = ""
    }
}
