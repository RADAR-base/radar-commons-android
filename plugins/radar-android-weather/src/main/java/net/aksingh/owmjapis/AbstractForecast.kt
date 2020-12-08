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

import net.aksingh.owmjapis.DailyForecast.Forecast.Companion.optStringOrNull
import org.json.JSONObject
import java.io.Serializable

/**
 *
 *
 * Provides default behaviours and implementations for:
 * 1. [net.aksingh.owmjapis.HourlyForecast]
 * 2. [net.aksingh.owmjapis.DailyForecast]
 * It defines common methods like `has`, `get` and some others.
 *
 *
 * @author Ashutosh Kumar Singh
 * @version 2014/12/27
 * @since 2.5.0.3
 */
abstract class AbstractForecast(jsonObj: JSONObject?) : AbstractResponse(jsonObj) {
    /**
     * @return Message if available, otherwise `Double.NaN`.
     */
    val message: Double = jsonObj?.optDouble(JSON_MESSAGE, Double.NaN) ?: Double.NaN

    /**
     * @return City's instance if available, otherwise `null`.
     */
    val cityInstance: City? = jsonObj.optObjectOrNull(JSON_CITY) { City(it) }

    /**
     * @return Count of forecasts if available, otherwise `0`.
     */
    val forecastCount: Int = jsonObj?.optInt(JSON_FORECAST_COUNT, 0) ?: 0

    /**
     * @return `true` if message is available, otherwise `false`.
     */
    fun hasMessage(): Boolean = !message.isNaN()

    /**
     * @return `true` if count of forecasts is available, otherwise `false`.
     */
    fun hasForecastCount(): Boolean = forecastCount != 0

    /**
     * @return `true` if message is available, otherwise `false`.
     */
    fun hasCityInstance(): Boolean = cityInstance != null

    /**
     *
     *
     * Provides default behaviours for City
     *
     *
     * @author Ashutosh Kumar Singh
     */
    class City(jsonObj: JSONObject?) : Serializable {
        val cityCode: Long
        val cityName: String?
        val countryCode: String?
        val cityPopulation: Long

        /**
         * @return Coord instance if available, otherwise `null`.
         */
        val coordInstance: Coord?

        init {
            cityCode = jsonObj?.optLong(JSON_CITY_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
            cityName = jsonObj.optStringOrNull(JSON_CITY_NAME)
            countryCode = jsonObj.optStringOrNull(JSON_CITY_COUNTRY_CODE)
            cityPopulation =
                jsonObj?.optLong(JSON_CITY_POPULATION, Long.MIN_VALUE) ?: Long.MIN_VALUE
            coordInstance = jsonObj.optObjectOrNull(JSON_CITY_COORD) { Coord(it) }
        }

        fun hasCityCode(): Boolean = cityCode != Long.MIN_VALUE

        fun hasCityName(): Boolean = cityName != null

        fun hasCountryCode(): Boolean = countryCode != null

        fun hasCityPopulation(): Boolean = cityPopulation != Long.MIN_VALUE

        /**
         * @return `true` if Coord instance is available, otherwise `false`.
         */
        fun hasCoordInstance(): Boolean = coordInstance != null

        class Coord(jsonObj: JSONObject?) : AbstractWeather.Coord(jsonObj)

        companion object {
            private const val JSON_CITY_ID = "id"
            private const val JSON_CITY_NAME = "name"
            private const val JSON_CITY_COUNTRY_CODE = "country"
            private const val JSON_CITY_POPULATION = "population"
            private const val JSON_CITY_COORD = "coord"
        }
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
     *
     * @author Ashutosh Kumar Singh
     * @version 2014/12/27
     * @since 2.5.0.3
     */
    abstract class Forecast(jsonObj: JSONObject?) : AbstractWeather(jsonObj)

    companion object {
        const val JSON_FORECAST_LIST = "list"
        const val JSON_MESSAGE = "message"
        const val JSON_CITY = "city"
        const val JSON_FORECAST_COUNT = "cnt"
    }
}
