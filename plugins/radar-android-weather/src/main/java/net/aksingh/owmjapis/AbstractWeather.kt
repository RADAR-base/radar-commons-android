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

import net.aksingh.owmjapis.DailyForecast.Forecast.Companion.optDateOrNull
import net.aksingh.owmjapis.DailyForecast.Forecast.Companion.optFloatOrNaN
import net.aksingh.owmjapis.DailyForecast.Forecast.Companion.optStringOrNull
import org.json.JSONObject
import java.io.Serializable
import java.util.*

/**
 *
 *
 * Provides default behaviours and implementations for:
 * 1. [net.aksingh.owmjapis.CurrentWeather]
 * It defines common methods like `has`, `get` and some others.
 *
 *
 * @author Ashutosh Kumar Singh
 * @version 2014/12/21
 * @since 2.5.0.1
 */
@Suppress("unused")
abstract class AbstractWeather(jsonObj: JSONObject?) : AbstractResponse(jsonObj) {
    /**
     * @return Date and time if available, otherwise `null`.
     */
    /*
         Instance variables
          */
    val dateTime: Date? = jsonObj.optDateOrNull(JSON_DATE_TIME)

    private val weatherList: List<Weather> = jsonObj.optObjectList(JSON_WEATHER) { Weather(it) }

    /**
     * @return `true` if date/time is available, otherwise `false`.
     */
    val hasDateTime: Boolean
        get() = dateTime != null

    /**
     * @return `true` if Weather instance(s) is available, otherwise `false`.
     */
    val hasWeatherInstance: Boolean
        get() = weatherList.isNotEmpty()

    /**
     * @param index Index of Weather instance in the list.
     * @return Weather instance if available, otherwise `null`.
     */
    fun getWeatherInstance(index: Int): Weather = weatherList[index]

    /**
     *
     *
     * Provides default behaviours for Cloud
     *
     *
     * @author Ashutosh Kumar Singh
     * @version 2013/12/23
     * @since 2.5.0.1
     */
    abstract class Clouds(jsonObj: JSONObject?) : Serializable {
        /**
         * @return Percentage of all clouds if available, otherwise `Float.NaN`.
         */
        val percentageOfClouds: Float = jsonObj.optFloatOrNaN(JSON_CLOUDS_ALL)

        /**
         * Tells if percentage of clouds is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        fun hasPercentageOfClouds(): Boolean = !percentageOfClouds.isNaN()

        companion object {
            private const val JSON_CLOUDS_ALL = "all"
        }
    }

    /**
     *
     *
     * Provides default behaviours for Coord, i.e., coordinates.
     *
     *
     * @author Ashutosh Kumar Singh
     * @version 2013/12/23
     * @since 2.5.0.1
     */
    abstract class Coord(jsonObj: JSONObject?) : Serializable {
        /**
         * @return Latitude of the city if available, otherwise `Float.NaN`.
         */
        val latitude: Float = jsonObj.optFloatOrNaN(JSON_COORD_LATITUDE)

        /**
         * @return Longitude of the city if available, otherwise `Float.NaN`.
         */
        val longitude: Float = jsonObj.optFloatOrNaN(JSON_COORD_LONGITUDE)

        /**
         * Tells if the latitude of the city is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        val hasLatitude: Boolean
            get() = !latitude.isNaN()

        /**
         * Tells if the longitude of the city is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        val hasLongitude: Boolean
            get() = !longitude.isNaN()

        companion object {
            private const val JSON_COORD_LATITUDE = "lat"
            private const val JSON_COORD_LONGITUDE = "lon"
        }
    }

    /**
     *
     *
     * Provides default behaviours for Main, i.e., main weather elements like temperature, humidity, etc.
     *
     *
     * @author Ashutosh Kumar Singh
     * @version 2013/12/23
     * @since 2.5.0.1
     */
    abstract class Main(jsonObj: JSONObject?) : Serializable {
        /**
         * @return Temperature of the city if available, otherwise `Float.NaN`.
         */
        val temperature: Float = jsonObj.optFloatOrNaN(JSON_MAIN_TEMP)

        /**
         * @return Minimum temperature of the city if available, otherwise `Float.NaN`.
         */
        val minTemperature: Float = jsonObj.optFloatOrNaN(JSON_MAIN_TEMP_MIN)

        /**
         * @return Maximum temperature of the city if available, otherwise `Float.NaN`.
         */
        val maxTemperature: Float = jsonObj.optFloatOrNaN(JSON_MAIN_TEMP_MAX)

        /**
         * @return Pressure of the city if available, otherwise `Float.NaN`.
         */
        val pressure: Float = jsonObj.optFloatOrNaN(JSON_MAIN_PRESSURE)

        /**
         * @return Humidity of the city if available, otherwise `Float.NaN`.
         */
        val humidity: Float = jsonObj.optFloatOrNaN(JSON_MAIN_HUMIDITY)

        /**
         * Tells if the temperature of the city is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        val hasTemperature: Boolean
            get() = !temperature.isNaN()

        /**
         * Tells if the minimum temperature of the city is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        val hasMinTemperature: Boolean
            get() = !minTemperature.isNaN()

        /**
         * Tells if the maximum temperature of the city is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        val hasMaxTemperature: Boolean
            get() = !maxTemperature.isNaN()

        /**
         * Tells if pressure of the city is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        val hasPressure: Boolean
            get() = !pressure.isNaN()

        /**
         * Tells if humidity of the city is available or not.
         *
         * @return `true` if data available, otherwise `false`
         */
        val hasHumidity: Boolean
            get() = !humidity.isNaN()

        companion object {
            private const val JSON_MAIN_TEMP = "temp"
            private const val JSON_MAIN_TEMP_MIN = "temp_min"
            private const val JSON_MAIN_TEMP_MAX = "temp_max"
            private const val JSON_MAIN_PRESSURE = "pressure"
            private const val JSON_MAIN_HUMIDITY = "humidity"
        }
    }

    /**
     *
     *
     * Parses weather data and provides methods to get/access the same information.
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
     * @since 2.5.0.3
     */
    class Weather(jsonObj: JSONObject) : Serializable {
        /**
         * @return Code for weather of the city if available, otherwise `Integer.MIN_VALUE`.
         */
        val weatherCode: Int = jsonObj.optInt(JSON_WEATHER_ID, Int.MIN_VALUE)

        /**
         * @return Name for weather of the city if available, otherwise `null`.
         */
        val weatherName: String? = jsonObj.optStringOrNull(JSON_WEATHER_MAIN)

        /**
         * @return Description for weather of the city if available, otherwise `null`.
         */
        val weatherDescription: String? = jsonObj.optStringOrNull(JSON_WEATHER_DESCRIPTION)

        /**
         * @return Name of icon for weather of the city if available, otherwise `null`.
         */
        val weatherIconName: String? = jsonObj.optStringOrNull(JSON_WEATHER_ICON)

        /**
         * Tells if weather's code is available or not.
         *
         * @return `true` if data available, otherwise `false`.
         */
        val hasWeatherCode: Boolean
            get() = weatherCode != Int.MIN_VALUE

        /**
         * Tells if weather's name is available or not.
         *
         * @return `true` if data available, otherwise `false`.
         */
        val hasWeatherName: Boolean
            get() = weatherName != null

        /**
         * Tells if weather's description is available or not.
         *
         * @return `true` if data available, otherwise `false`.
         */
        val hasWeatherDescription: Boolean
            get() = weatherDescription != null

        /**
         * Tells if name of weather's icon is available or not.
         *
         * @return `true` if data available, otherwise `false`.
         */
        val hasWeatherIconName: Boolean
            get() = weatherIconName != null

        companion object {
            private const val JSON_WEATHER_ID = "id"
            private const val JSON_WEATHER_MAIN = "main"
            private const val JSON_WEATHER_DESCRIPTION = "description"
            private const val JSON_WEATHER_ICON = "icon"
        }
    }

    /**
     *
     *
     * Provides default behaviours for Wind.
     *
     *
     * @author Ashutosh Kumar Singh
     * @version 2013/12/23
     * @since 2.5.0.1
     */
    abstract class Wind(jsonObj: JSONObject?) : Serializable {
        /**
         * @return Speed of wind in the city if available, otherwise `Float.NaN`.
         */
        val windSpeed: Float = jsonObj.optFloatOrNaN(JSON_WIND_SPEED)

        /**
         * @return Degree of wind in the city if available, otherwise `Float.NaN`.
         */
        val windDegree: Float = jsonObj.optFloatOrNaN(JSON_WIND_DEGREE)

        /**
         * Tells if speed of wind in the city is available or not.
         *
         * @return `true` if data available, otherwise `false`.
         */
        val hasWindSpeed: Boolean
            get() = !windSpeed.isNaN()

        /**
         * Tells if degree (degree gives direction) of wind in the city is available or not.
         *
         * @return `true` if data available, otherwise `false`.
         */
        val hasWindDegree: Boolean
            get() = hasWindSpeed && !windDegree.isNaN()

        companion object {
            private const val JSON_WIND_SPEED = "speed"
            private const val JSON_WIND_DEGREE = "deg"
        }
    }

    companion object {
        const val JSON_CLOUDS = "clouds"
        const val JSON_COORD = "coord"
        const val JSON_MAIN = "main"
        const val JSON_WIND = "wind"
        private const val JSON_WEATHER = "weather"
        private const val JSON_DATE_TIME = "dt"
    }
}
