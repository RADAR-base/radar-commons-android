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
 * Parses current weather data and provides methods to get/access the same information.
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
 * @version 2014/12/26
 * @see [OWM's Current Weather API](http://openweathermap.org/current)
 *
 * @since 2.5.0.1
 */
class CurrentWeather(jsonObj: JSONObject?) : AbstractWeather(jsonObj) {
    /**
     * @return Base station if available, otherwise `null`.
     */
    /*
         Instance variables
          */
    val baseStation: String?

    /**
     * @return City code if available, otherwise `Long.MIN_VALUE`.
     */
    val cityCode: Long

    /**
     * @return City name if available, otherwise `null`.
     */
    val cityName: String?

    /**
     * @return Clouds instance if available, otherwise `null`.
     */
    val cloudsInstance: Clouds?

    /**
     * @return Coord instance if available, otherwise `null`.
     */
    val coordInstance: Coord?

    /**
     * @return Main instance if available, otherwise `null`.
     */
    val mainInstance: Main?

    /**
     * @return Rain instance if available, otherwise `null`.
     */
    val rainInstance: Rain?

    /**
     * @return Snow instance if available, otherwise `null`.
     */
    val snowInstance: Snow?

    /**
     * @return Sys instance if available, otherwise `null`.
     */
    val sysInstance: Sys?

    /**
     * @return Wind instance if available, otherwise `null`.
     */
    val windInstance: Wind?


    init {
        baseStation = jsonObj.optStringOrNull(JSON_BASE)
        cityCode = jsonObj?.optLong(JSON_CITY_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        cityName = jsonObj.optStringOrNull(JSON_CITY_NAME)
        cloudsInstance = jsonObj.optObjectOrNull(JSON_CLOUDS) { Clouds(it) }
        coordInstance = jsonObj.optObjectOrNull(JSON_COORD) { Coord(it) }
        mainInstance = jsonObj.optObjectOrNull(JSON_MAIN) { Main(it) }
        rainInstance = jsonObj.optObjectOrNull(JSON_RAIN) { Rain(it) }
        snowInstance = jsonObj.optObjectOrNull(JSON_SNOW) { Snow(it) }
        sysInstance = jsonObj.optObjectOrNull(JSON_SYS) { Sys(it) }
        windInstance = jsonObj.optObjectOrNull(JSON_WIND) { Wind(it) }
    }

    /**
     * @return `true` if base station is available, otherwise `false`.
     */
    fun hasBaseStation(): Boolean = baseStation != null

    /**
     * @return `true` if city code is available, otherwise `false`.
     */
    fun hasCityCode(): Boolean = cityCode != Long.MIN_VALUE

    /**
     * @return `true` if city name is available, otherwise `false`.
     */
    fun hasCityName(): Boolean = cityName != null

    /**
     * @return `true` if Clouds instance is available, otherwise `false`.
     */
    fun hasCloudsInstance(): Boolean = cloudsInstance != null

    /**
     * @return `true` if Coord instance is available, otherwise `false`.
     */
    fun hasCoordInstance(): Boolean = coordInstance != null

    /**
     * @return `true` if Main instance is available, otherwise `false`.
     */
    fun hasMainInstance(): Boolean = mainInstance != null

    /**
     * @return `true` if Rain instance is available, otherwise `false`.
     */
    fun hasRainInstance(): Boolean = rainInstance != null

    /**
     * @return `true` if Snow instance is available, otherwise `false`.
     */
    fun hasSnowInstance(): Boolean = snowInstance != null

    /**
     * @return `true` if Sys instance is available, otherwise `false`.
     */
    fun hasSysInstance(): Boolean = sysInstance != null

    /**
     * @return `true` if Wind instance is available, otherwise `false`.
     */
    fun hasWindInstance(): Boolean = windInstance != null

    /**
     *
     *
     * Parses clouds data and provides methods to get/access the same information.
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
     * @version 2014/12/26
     * @since 2.5.0.1
     */
    class Clouds(jsonObj: JSONObject?) : AbstractWeather.Clouds(jsonObj)

    /**
     *
     *
     * Parses coordination data and provides methods to get/access the same information.
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
     * @version 2014/12/26
     * @since 2.5.0.1
     */
    class Coord(jsonObj: JSONObject?) : AbstractWeather.Coord(jsonObj)

    /**
     *
     *
     * Parses main data and provides methods to get/access the same information.
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
     * @version 2014/12/26
     * @since 2.5.0.1
     */
    class Main(jsonObj: JSONObject?) : AbstractWeather.Main(jsonObj)

    /**
     *
     *
     * Parses rain data and provides methods to get/access the same information.
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
     * @version 2014/12/26
     * @since 2.5.0.1
     */
    class Rain internal constructor(jsonObj: JSONObject?) : Serializable {
        val rain1h: Float = jsonObj.optFloatOrNaN(JSON_RAIN_1HOUR)
        val rain3h: Float = jsonObj.optFloatOrNaN(JSON_RAIN_3HOUR)

        fun hasRain1h(): Boolean = !rain1h.isNaN()

        fun hasRain3h(): Boolean = !rain3h.isNaN()

        companion object {
            private const val JSON_RAIN_1HOUR = "1h"
            private const val JSON_RAIN_3HOUR = "3h"
        }
    }

    /**
     *
     *
     * Parses snow data and provides methods to get/access the same information.
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
     * @version 2015/01/28
     * @since 2.5.0.4
     */
    class Snow(jsonObj: JSONObject?) : Serializable {
        val snow1h: Float = jsonObj.optFloatOrNaN(JSON_SNOW_1HOUR)
        val snow3h: Float = jsonObj.optFloatOrNaN(JSON_SNOW_3HOUR)

        fun hasSnow1h(): Boolean = !snow1h.isNaN()

        fun hasSnow3h(): Boolean = !snow3h.isNaN()

        companion object {
            private const val JSON_SNOW_1HOUR = "1h"
            private const val JSON_SNOW_3HOUR = "3h"
        }
    }

    /**
     *
     *
     * Parses sys data and provides methods to get/access the same information.
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
     * @version 2014/12/26
     * @since 2.5.0.1
     */
    class Sys(jsonObj: JSONObject?) : Serializable {
        val type: Int = jsonObj?.optInt(JSON_SYS_TYPE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val id: Int = jsonObj?.optInt(JSON_SYS_ID, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val message: Double = jsonObj?.optDouble(JSON_SYS_MESSAGE, Double.NaN) ?: Double.NaN
        val countryCode: String? = jsonObj.optStringOrNull(JSON_SYS_COUNTRY_CODE)
        val sunriseTime: Date? = jsonObj.optDateOrNull(JSON_SYS_SUNRISE)
        val sunsetTime: Date? = jsonObj.optDateOrNull(JSON_SYS_SUNSET)

        fun hasType(): Boolean = type != Int.MIN_VALUE

        fun hasId(): Boolean = id != Int.MIN_VALUE

        fun hasMessage(): Boolean = !message.isNaN()

        fun hasCountryCode(): Boolean = countryCode != null

        fun hasSunriseTime(): Boolean = sunriseTime != null

        fun hasSunsetTime(): Boolean = sunsetTime != null

        companion object {
            private const val JSON_SYS_TYPE = "type"
            private const val JSON_SYS_ID = "id"
            private const val JSON_SYS_MESSAGE = "message"
            private const val JSON_SYS_COUNTRY_CODE = "country"
            private const val JSON_SYS_SUNRISE = "sunrise"
            private const val JSON_SYS_SUNSET = "sunset"
        }
    }

    /**
     *
     *
     * Parses wind data and provides methods to get/access the same information.
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
     * @version 2014/12/26
     * @since 2.5.0.1
     */
    class Wind(jsonObj: JSONObject?) : AbstractWeather.Wind(jsonObj) {
        val windGust: Float = jsonObj.optFloatOrNaN(JSON_WIND_GUST)

        fun hasWindGust(): Boolean = !windGust.isNaN()

        companion object {
            private const val JSON_WIND_GUST = "gust"
        }
    }

    /**
     * Copyright The Hyve
     *
     * @author Maxim Moinat
     */
    override fun toString(): String {
        val w = mainInstance
        val rain = rainInstance
        val snow = snowInstance
        val weather = getWeatherInstance(0)
        val clouds = cloudsInstance
        val sys = sysInstance
        return String.format(
            "Temperature: %s (%s-%s), Pressure: %s, Humidity: %s, " +
                    "Rain/Snow3h: %s/%s, Weather: '%s %s' (%s), Cloudiness: %s, " +
                    "Sunrise/set: '%s' - '%s'",
            w?.temperature ?: "",
            w?.minTemperature ?: "",
            w?.maxTemperature ?: "",
            w?.pressure ?: "",
            w?.humidity ?: "",
            if (rain != null && rain.hasRain3h()) rain.rain3h else "-",
            if (snow != null && snow.hasSnow3h()) snow.snow3h else "-",
            if (hasWeatherInstance) weather.weatherName else "",
            if (hasWeatherInstance) weather.weatherDescription else "",
            if (hasWeatherInstance) weather.weatherCode else "",
            clouds?.percentageOfClouds ?: "",
            sys?.sunriseTime ?: "",
            sys?.sunsetTime ?: "",
        )
    }

    companion object {
        private const val JSON_RAIN = "rain"
        private const val JSON_SNOW = "snow"
        private const val JSON_SYS = "sys"
        private const val JSON_BASE = "base"
        private const val JSON_CITY_ID = "id"
        private const val JSON_CITY_NAME = "name"
    }

}
