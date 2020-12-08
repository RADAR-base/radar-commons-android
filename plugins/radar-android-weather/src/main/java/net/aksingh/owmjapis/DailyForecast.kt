/*
 * Copyright (c) 2013-2015 Ashutosh Kumar Singh <me@aksingh.net>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.aksingh.owmjapis

import org.json.JSONObject
import java.io.Serializable
import java.util.*

/**
 *
 *
 * Parses daily forecast data and provides methods to get/access the same information.
 * This class provides `has` and `get` methods to access the information.
 *
 *
 *
 * `has` methods can be used to check if the data exists, i.e., if the data was available
 * (successfully downloaded) and was parsed correctly.
 * `get` methods can be used to access the data, if the data exists, otherwise `get`
 * methods will give value as per following basis:
 * Boolean: `false`
 * Integral: Minimum value (MIN_VALUE)
 * Floating point: Not a number (NaN)
 * Others: `null`
 *
 *
 * @author Ashutosh Kumar Singh
 * @version 2014/12/27
 * @see [OWM's Weather Forecast API](http://openweathermap.org/forecast)
 *
 * @since 2.5.0.3
 */
class DailyForecast(jsonObj: JSONObject?) : AbstractForecast(jsonObj) {
    private val forecastList: List<Forecast> =
        jsonObj.optObjectList(JSON_FORECAST_LIST) { Forecast(it) }

    /**
     * @param index Index of Forecast instance in the list.
     * @return Forecast instance if available, otherwise `null`.
     */
    fun getForecastInstance(index: Int): Forecast {
        return forecastList[index]
    }

    /**
     *
     *
     * Parses forecast data (one element in the forecastList) and provides methods to get/access the same information.
     * This class provides `has` and `get` methods to access the information.
     *
     *
     *
     * `has` methods can be used to check if the data exists, i.e., if the data was available
     * (successfully downloaded) and was parsed correctly.
     * `get` methods can be used to access the data, if the data exists, otherwise `get`
     * methods will give value as per following basis:
     * Boolean: `false`
     * Integral: Minimum value (MIN_VALUE)
     * Floating point: Not a number (NaN)
     * Others: `null`
     *
     */
    class Forecast internal constructor(jsonObj: JSONObject?) : AbstractForecast.Forecast(jsonObj) {
        /*
        Instance Variables
         */
        val pressure: Float = jsonObj.optFloatOrNaN(JSON_FORECAST_PRESSURE)
        val humidity: Float = jsonObj.optFloatOrNaN(JSON_FORECAST_HUMIDITY)
        val windSpeed: Float = jsonObj.optFloatOrNaN(JSON_FORECAST_WIND_SPEED)
        val windDegree: Float = jsonObj.optFloatOrNaN(JSON_FORECAST_WIND_DEGREE)
        val percentageOfClouds: Float = jsonObj.optFloatOrNaN(JSON_FORECAST_CLOUDS)
        val rain: Float = jsonObj.optFloatOrNaN(JSON_FORECAST_RAIN)
        val snow: Float = jsonObj.optFloatOrNaN(JSON_FORECAST_SNOW)
        val temperatureInstance: Temperature? =
            jsonObj.optObjectOrNull(JSON_TEMP) { Temperature(it) }

        val hasHumidity: Boolean
            get() = !humidity.isNaN()

        val hasPressure: Boolean
            get() = !pressure.isNaN()

        val hasWindSpeed: Boolean
            get() = !windSpeed.isNaN()

        val hasWindDegree: Boolean
            get() = !windDegree.isNaN()

        val hasPercentageOfClouds: Boolean
            get() = !percentageOfClouds.isNaN()

        val hasRain: Boolean
            get() = !rain.isNaN()

        val hasSnow: Boolean
            get() = !snow.isNaN()

        /**
         *
         *
         * Parses temperature data and provides methods to get/access the same information.
         * This class provides `has` and `get` methods to access the information.
         *
         *
         *
         * `has` methods can be used to check if the data exists, i.e., if the data was available
         * (successfully downloaded) and was parsed correctly.
         * `get` methods can be used to access the data, if the data exists, otherwise `get`
         * methods will give value as per following basis:
         * Boolean: `false`
         * Integral: Minimum value (MIN_VALUE)
         * Floating point: Not a number (NaN)
         * Others: `null`
         *
         */
        class Temperature(jsonObj: JSONObject) : Serializable {
            val dayTemperature: Float = jsonObj.optFloatOrNaN(JSON_TEMP_DAY)
            val minimumTemperature: Float = jsonObj.optFloatOrNaN(JSON_TEMP_DAY)
            val maximumTemperature: Float = jsonObj.optFloatOrNaN(JSON_TEMP_MAX)
            val nightTemperature: Float = jsonObj.optFloatOrNaN(JSON_TEMP_NIGHT)
            val eveningTemperature: Float = jsonObj.optFloatOrNaN(JSON_TEMP_EVENING)
            val morningTemperature: Float = jsonObj.optFloatOrNaN(JSON_TEMP_MORNING)

            val hasDayTemperature: Boolean
                get() = !dayTemperature.isNaN()

            val hasMinimumTemperature: Boolean
                get() = !minimumTemperature.isNaN()

            val hasMaximumTemperature: Boolean
                get() = !maximumTemperature.isNaN()

            val hasNightTemperature: Boolean
                get() = !nightTemperature.isNaN()

            val hasEveningTemperature: Boolean
                get() = !eveningTemperature.isNaN()

            val hasMorningTemperature: Boolean
                get() = !morningTemperature.isNaN()

            companion object {
                private const val JSON_TEMP_DAY = "day"
                private const val JSON_TEMP_MIN = "min"
                private const val JSON_TEMP_MAX = "max"
                private const val JSON_TEMP_NIGHT = "night"
                private const val JSON_TEMP_EVENING = "eve"
                private const val JSON_TEMP_MORNING = "morn"
            }
        }

        companion object {
            fun JSONObject?.optFloatOrNaN(field: String): Float = if (this != null) {
                optDouble(field, Double.NaN).toFloat()
            } else {
                Float.NaN
            }

            fun JSONObject?.optDateOrNull(field: String): Date? = this?.let { obj ->
                val secs = obj.optLong(field, Long.MIN_VALUE)
                if (secs != Long.MIN_VALUE) Date(1000L * secs) else null
            }

            fun JSONObject?.optStringOrNull(field: String): String? = this?.let { obj ->
                obj.getString(field).takeIf { it.isNotEmpty() }
            }

            const val JSON_TEMP = "temp"
            private const val JSON_FORECAST_PRESSURE = "pressure"
            private const val JSON_FORECAST_HUMIDITY = "humidity"
            private const val JSON_FORECAST_WIND_SPEED = "speed"
            private const val JSON_FORECAST_WIND_DEGREE = "deg"
            private const val JSON_FORECAST_CLOUDS = "clouds"
            private const val JSON_FORECAST_RAIN = "rain"
            private const val JSON_FORECAST_SNOW = "snow"
        }
    }
}
