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

import net.aksingh.owmjapis.DailyForecast.Forecast.Companion.optFloatOrNaN
import net.aksingh.owmjapis.DailyForecast.Forecast.Companion.optStringOrNull
import org.json.JSONObject
import java.io.Serializable

/**
 *
 *
 * Parses hourly forecast data and provides methods to get/access the same information.
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
class HourlyForecast internal constructor(jsonObj: JSONObject?) : AbstractForecast(jsonObj) {
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
        val dateTimeText: String? = jsonObj.optStringOrNull(JSON_DT_TEXT)

        /**
         * @return Clouds instance if available, otherwise `null`.
         */
        val cloudsInstance: Clouds? = jsonObj.optObjectOrNull(JSON_CLOUDS) { Clouds(it) }

        /**
         * @return Main instance if available, otherwise `null`.
         */
        val mainInstance: Main? = jsonObj.optObjectOrNull(JSON_MAIN) { Main(it) }

        /**
         * @return Sys instance if available, otherwise `null`.
         */
        val sysInstance: Sys? = jsonObj.optObjectOrNull(JSON_SYS) { Sys(it) }

        /**
         * @return Wind instance if available, otherwise `null`.
         */
        val windInstance: Wind? = jsonObj.optObjectOrNull(JSON_WIND) { Wind(it) }

        fun hasDateTimeText(): Boolean = dateTimeText != null

        /**
         * @return `true` if Clouds instance is available, otherwise `false`.
         */
        fun hasCloudsInstance(): Boolean = cloudsInstance != null

        /**
         * @return `true` if Main instance is available, otherwise `false`.
         */
        fun hasMainInstance(): Boolean = mainInstance != null

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
        class Main(jsonObj: JSONObject?) : AbstractWeather.Main(jsonObj) {
            val seaLevel: Float = jsonObj.optFloatOrNaN(JSON_MAIN_SEA_LEVEL)
            val groundLevel: Float = jsonObj.optFloatOrNaN(JSON_MAIN_GRND_LEVEL)
            val tempKF: Float = jsonObj.optFloatOrNaN(JSON_MAIN_TMP_KF)

            fun hasSeaLevel(): Boolean = !seaLevel.isNaN()

            fun hasGroundLevel(): Boolean = !groundLevel.isNaN()

            fun hasTempKF(): Boolean = !tempKF.isNaN()

            companion object {
                private const val JSON_MAIN_SEA_LEVEL = "sea_level"
                private const val JSON_MAIN_GRND_LEVEL = "grnd_level"
                private const val JSON_MAIN_TMP_KF = "temp_kf"
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
            val pod: String? = jsonObj.optStringOrNull(JSON_SYS_POD)

            fun hasPod(): Boolean = pod != null

            companion object {
                private const val JSON_SYS_POD = "pod"
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
        class Wind(jsonObj: JSONObject?) : AbstractWeather.Wind(jsonObj)

        companion object {
            private const val JSON_SYS = "sys"
            private const val JSON_DT_TEXT = "dt_txt"
        }
    }
}
