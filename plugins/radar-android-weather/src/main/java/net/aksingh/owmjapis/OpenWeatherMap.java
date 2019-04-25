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

package net.aksingh.owmjapis;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * <p>
 *     <b>The starting point for all API operations.</b>
 *     If you're new to this API, read the docs for this class first.
 * </p>
 * <p>
 * Lets you access data from OpenWeatherMap.org using its Weather APIs.
 * Henceforth, it's shortened as OWM.org to ease commenting.
 * </p>
 * <p>
 * <b>Sample code:</b><br>
 * <code>OpenWeatherMap.org owm = new OpenWeatherMap("your-api-key");</code><br>
 * <code>OpenWeatherMap.org owm = new OpenWeatherMap(your-units, "your-api-key");</code><br>
 * <code>OpenWeatherMap.org owm = new OpenWeatherMap(your-units, your-language, "your-api-key");</code>
 * </p>
 *
 * @author Ashutosh Kumar Singh &lt;me@aksingh.net&gt;
 * @version 2015-01-17
 * @see <a href="http://openweathermap.org/">OpenWeatherMap.org</a>
 * @see <a href="http://openweathermap.org/api">OpenWeatherMap.org API</a>
 * @since 2.5.0.1
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class OpenWeatherMap {
    private static final Logger logger = LoggerFactory.getLogger(OpenWeatherMap.class);

    /*
    URLs and parameters for OWM.org
     */
    public static final String URL_API = "http://api.openweathermap.org/data/2.5/";
    private static final String URL_CURRENT = "weather?";
    private static final String URL_HOURLY_FORECAST = "forecast?";
    private static final String URL_DAILY_FORECAST = "forecast/daily?";

    private static final String PARAM_COUNT = "cnt=";
    private static final String PARAM_CITY_NAME = "q=";
    private static final String PARAM_CITY_ID = "id=";
    private static final String PARAM_LATITUDE = "lat=";
    private static final String PARAM_LONGITUDE = "lon=";
    private static final String PARAM_MODE = "mode=";
    private static final String PARAM_UNITS = "units=";
    private static final String PARAM_APPID = "appId=";
    private static final String PARAM_LANG = "lang=";

    /**
     * Languages that can be set for getting data from OWM.org
     *
     * @since 2.5.0.3
     */
    public static final String LANGUAGE_ENGLISH = "en";
    public static final String LANGUAGE_RUSSIAN = "ru";
    public static final String LANGUAGE_ITALIAN = "it";
    public static final String LANGUAGE_SPANISH = "es";
    public static final String LANGUAGE_UKRAINIAN = "uk";
    public static final String LANGUAGE_GERMAN = "de";
    public static final String LANGUAGE_PORTUGUESE = "pt";
    public static final String LANGUAGE_ROMANIAN = "ro";
    public static final String LANGUAGE_POLISH = "pl";
    public static final String LANGUAGE_FINNISH = "fi";
    public static final String LANGUAGE_DUTCH = "nl";
    public static final String LANGUAGE_FRENCH = "FR";
    public static final String LANGUAGE_BULGARIAN = "bg";
    public static final String LANGUAGE_SWEDISH = "sv";
    public static final String LANGUAGE_CHINESE_TRADITIONAL = "zh_tw";
    public static final String LANGUAGE_CHINESE_SIMPLIFIED = "zh";
    public static final String LANGUAGE_TURKISH = "tr";
    public static final String LANGUAGE_CROATIAN = "hr";
    public static final String LANGUAGE_CATALAN = "ca";

    /**
     * Units that can be set for getting data from OWM.org
     *
     * @since 2.5.0.3
     */
    public static final String UNITS_METRIC = "metric";
    public static final String UNITS_IMPERIAL = "imperial";

    /*
    Instance Variables
     */
    private final OWMAddress owmAddress;
    private final OWMResponse owmResponse;

    /**
     * Constructor
     *
     * @param apiKey API key from OWM.org
     * @see <a href="http://openweathermap.org/appid">OWM.org API Key</a>
     */
    public OpenWeatherMap(String apiKey, OkHttpClient client) {
        this(UNITS_IMPERIAL, "en", apiKey, client);
    }

    /**
     * Constructor
     *
     * @param units  Any constant from Units
     * @param apiKey API key from OWM.org
     * @see <a href="http://openweathermap.org/appid">OWM.org API Key</a>
     */
    public OpenWeatherMap(String units, String apiKey, OkHttpClient client) {
        this(units, "en", apiKey, client);
    }

    /**
     * Constructor
     *
     * @param units  Any constant from Units
     * @param lang   Any constant from Language
     * @param apiKey API key from OWM.org
     * @see <a href="http://openweathermap.org/current#multi">OWM.org's Multilingual support</a>
     * @see <a href="http://openweathermap.org/appid">OWM.org's API Key</a>
     */
    public OpenWeatherMap(String units, String lang, String apiKey, OkHttpClient client) {
        this.owmAddress = new OWMAddress(units, lang, apiKey);
        this.owmResponse = new OWMResponse(client, owmAddress);
    }

    /*
    Getters
     */
    public OWMAddress getOwmAddressInstance() {
        return owmAddress;
    }

    public String getApiKey() {
        return owmAddress.getAppId();
    }

    public String getUnits() {
        return owmAddress.getUnits();
    }

    public String getMode() {
        return owmAddress.getMode();
    }

    public String getLang() {
        return owmAddress.getLang();
    }

    /*
    Setters
     */

    /**
     * Set units for getting data from OWM.org
     *
     * @param units Any constant from Units
     */
    public void setUnits(String units) {
        owmAddress.setUnits(units);
    }

    /**
     * Set API key for getting data from OWM.org
     *
     * @param appId API key from OWM.org
     * @see <a href="http://openweathermap.org/appid">OWM.org's API Key</a>
     */
    public void setApiKey(String appId) {
        owmAddress.setAppId(appId);
    }

    /**
     * Set language for getting data from OWM.org
     *
     * @param lang Any constant from Language
     * @see <a href="http://openweathermap.org/current#multi">OWM.org's Multilingual support</a>
     */
    public void setLang(String lang) {
        owmAddress.setLang(lang);
    }

    public CurrentWeather currentWeatherByCityName(String cityName)
            throws IOException, JSONException {
        String response = owmResponse.currentWeatherByCityName(cityName);
        return this.currentWeatherFromRawResponse(response);
    }

    public CurrentWeather currentWeatherByCityName(String cityName, String countryCode)
            throws IOException, JSONException {
        String response = owmResponse.currentWeatherByCityName(cityName, countryCode);
        return this.currentWeatherFromRawResponse(response);
    }

    public CurrentWeather currentWeatherByCityCode(long cityCode)
            throws JSONException {
        String response = owmResponse.currentWeatherByCityCode(cityCode);
        return this.currentWeatherFromRawResponse(response);
    }

    public CurrentWeather currentWeatherByCoordinates(float latitude, float longitude)
            throws JSONException {
        String response = owmResponse.currentWeatherByCoordinates(latitude, longitude);
        return this.currentWeatherFromRawResponse(response);
    }

    public CurrentWeather currentWeatherFromRawResponse(String response)
            throws JSONException {
        JSONObject jsonObj = (response != null) ? new JSONObject(response) : null;
        return new CurrentWeather(jsonObj);
    }

    public HourlyForecast hourlyForecastByCityName(String cityName)
            throws IOException, JSONException {
        String response = owmResponse.hourlyForecastByCityName(cityName);
        return this.hourlyForecastFromRawResponse(response);
    }

    public HourlyForecast hourlyForecastByCityName(String cityName, String countryCode)
            throws IOException, JSONException {
        String response = owmResponse.hourlyForecastByCityName(cityName, countryCode);
        return this.hourlyForecastFromRawResponse(response);
    }

    public HourlyForecast hourlyForecastByCityCode(long cityCode)
            throws JSONException {
        String response = owmResponse.hourlyForecastByCityCode(cityCode);
        return this.hourlyForecastFromRawResponse(response);
    }

    public HourlyForecast hourlyForecastByCoordinates(float latitude, float longitude)
            throws JSONException {
        String response = owmResponse.hourlyForecastByCoordinates(latitude, longitude);
        return this.hourlyForecastFromRawResponse(response);
    }

    public HourlyForecast hourlyForecastFromRawResponse(String response)
            throws JSONException {
        JSONObject jsonObj = (response != null) ? new JSONObject(response) : null;
        return new HourlyForecast(jsonObj);
    }

    public DailyForecast dailyForecastByCityName(String cityName, byte count)
            throws IOException, JSONException {
        String response = owmResponse.dailyForecastByCityName(cityName, count);
        return this.dailyForecastFromRawResponse(response);
    }

    public DailyForecast dailyForecastByCityName(String cityName, String countryCode, byte count)
            throws IOException, JSONException {
        String response = owmResponse.dailyForecastByCityName(cityName, countryCode, count);
        return this.dailyForecastFromRawResponse(response);
    }

    public DailyForecast dailyForecastByCityCode(long cityCode, byte count)
            throws JSONException {
        String response = owmResponse.dailyForecastByCityCode(cityCode, count);
        return this.dailyForecastFromRawResponse(response);
    }

    public DailyForecast dailyForecastByCoordinates(float latitude, float longitude, byte count)
            throws JSONException {
        String response = owmResponse.dailyForecastByCoordinates(latitude, longitude, count);
        return this.dailyForecastFromRawResponse(response);
    }

    public DailyForecast dailyForecastFromRawResponse(String response)
            throws JSONException {
        JSONObject jsonObj = (response != null) ? new JSONObject(response) : null;
        return new DailyForecast(jsonObj);
    }

    /**
     * Generates addresses for accessing the information from OWM.org
     *
     * @since 2.5.0.3
     */
    @SuppressWarnings({"unused", "WeakerAccess"})
    public static class OWMAddress {
        private static final String MODE = "json";
        private static final String ENCODING = "UTF-8";

        private String mode;
        private String units;
        private String appId;
        private String lang;

        /*
        Constructors
         */
        private OWMAddress(String appId) {
            this(UNITS_IMPERIAL, "en", appId);
        }

        private OWMAddress(String units, String appId) {
            this(units, "en", appId);
        }

        private OWMAddress(String units, String lang, String appId) {
            this.mode = MODE;
            this.units = units;
            this.lang = lang;
            this.appId = appId;
        }

        /*
        Getters
         */
        private String getAppId() {
            return this.appId;
        }

        private String getUnits() {
            return this.units;
        }

        private String getMode() {
            return this.mode;
        }

        private String getLang() {
            return this.lang;
        }

        /*
        Setters
         */
        private void setUnits(String units) {
            this.units = units;
        }

        private void setAppId(String appId) {
            this.appId = appId;
        }

        private void setLang(String lang) {
            this.lang = lang;
        }

        /*
        Addresses for current weather
         */
        public String currentWeatherByCityName(String cityName) throws UnsupportedEncodingException {
            return URL_API + URL_CURRENT +
                    PARAM_CITY_NAME + URLEncoder.encode(cityName, ENCODING) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }

        public String currentWeatherByCityName(String cityName, String countryCode) throws UnsupportedEncodingException {
            return currentWeatherByCityName(cityName + "," + countryCode);
        }

        public String currentWeatherByCityCode(long cityCode) {
            return URL_API + URL_CURRENT +
                    PARAM_CITY_ID + Long.toString(cityCode) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }

        public String currentWeatherByCoordinates(float latitude, float longitude) {
            return URL_API + URL_CURRENT +
                    PARAM_LATITUDE + Float.toString(latitude) + "&" +
                    PARAM_LONGITUDE + Float.toString(longitude) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_APPID + this.appId;
        }

        /*
        Addresses for hourly forecasts
         */
        public String hourlyForecastByCityName(String cityName) throws UnsupportedEncodingException {
            return URL_API + URL_HOURLY_FORECAST +
                    PARAM_CITY_NAME + URLEncoder.encode(cityName, ENCODING) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }

        public String hourlyForecastByCityName(String cityName, String countryCode) throws UnsupportedEncodingException {
            return hourlyForecastByCityName(cityName + "," + countryCode);
        }

        public String hourlyForecastByCityCode(long cityCode) {
            return URL_API + URL_HOURLY_FORECAST +
                    PARAM_CITY_ID + Long.toString(cityCode) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }

        public String hourlyForecastByCoordinates(float latitude, float longitude) {
            return URL_API + URL_HOURLY_FORECAST +
                    PARAM_LATITUDE + Float.toString(latitude) + "&" +
                    PARAM_LONGITUDE + Float.toString(longitude) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }

        /*
        Addresses for daily forecasts
         */
        public String dailyForecastByCityName(String cityName, byte count) throws UnsupportedEncodingException {
            return URL_API + URL_DAILY_FORECAST +
                    PARAM_CITY_NAME + URLEncoder.encode(cityName, ENCODING) + "&" +
                    PARAM_COUNT + Byte.toString(count) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }

        public String dailyForecastByCityName(String cityName, String countryCode, byte count) throws UnsupportedEncodingException {
            return dailyForecastByCityName(cityName + "," + countryCode, count);
        }

        public String dailyForecastByCityCode(long cityCode, byte count) {
            return URL_API + URL_DAILY_FORECAST +
                    PARAM_CITY_ID + Long.toString(cityCode) + "&" +
                    PARAM_COUNT + Byte.toString(count) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }

        public String dailyForecastByCoordinates(float latitude, float longitude, byte count) {
            return URL_API + URL_DAILY_FORECAST +
                    PARAM_LATITUDE + Float.toString(latitude) + "&" +
                    PARAM_LONGITUDE + Float.toString(longitude) + "&" +
                    PARAM_COUNT + Byte.toString(count) + "&" +
                    PARAM_MODE + this.mode + "&" +
                    PARAM_UNITS + this.units + "&" +
                    PARAM_LANG + this.lang + "&" +
                    PARAM_APPID + this.appId;
        }
    }

    /**
     * Requests OWM.org for data and provides back the incoming response.
     *
     * @since 2.5.0.3
     */
    @SuppressWarnings("WeakerAccess")
    private static class OWMResponse {
        private final OWMAddress owmAddress;
        private final OkHttpClient client;

        public OWMResponse(OkHttpClient client, OWMAddress owmAddress) {
            this.owmAddress = owmAddress;
            this.client = client;
        }

        /*
        Responses for current weather
         */
        public String currentWeatherByCityName(String cityName) throws UnsupportedEncodingException {
            String address = owmAddress.currentWeatherByCityName(cityName);
            return httpGET(address);
        }

        public String currentWeatherByCityName(String cityName, String countryCode) throws UnsupportedEncodingException {
            String address = owmAddress.currentWeatherByCityName(cityName, countryCode);
            return httpGET(address);
        }

        public String currentWeatherByCityCode(long cityCode) {
            String address = owmAddress.currentWeatherByCityCode(cityCode);
            return httpGET(address);
        }

        public String currentWeatherByCoordinates(float latitude, float longitude) {
            String address = owmAddress.currentWeatherByCoordinates(latitude, longitude);
            return httpGET(address);
        }

        /*
        Responses for hourly forecasts
         */
        public String hourlyForecastByCityName(String cityName) throws UnsupportedEncodingException {
            String address = owmAddress.hourlyForecastByCityName(cityName);
            return httpGET(address);
        }

        public String hourlyForecastByCityName(String cityName, String countryCode) throws UnsupportedEncodingException {
            String address = owmAddress.hourlyForecastByCityName(cityName, countryCode);
            return httpGET(address);
        }

        public String hourlyForecastByCityCode(long cityCode) {
            String address = owmAddress.hourlyForecastByCityCode(cityCode);
            return httpGET(address);
        }

        public String hourlyForecastByCoordinates(float latitude, float longitude) {
            String address = owmAddress.hourlyForecastByCoordinates(latitude, longitude);
            return httpGET(address);
        }

        /*
        Responses for daily forecasts
         */
        public String dailyForecastByCityName(String cityName, byte count) throws UnsupportedEncodingException {
            String address = owmAddress.dailyForecastByCityName(cityName, count);
            return httpGET(address);
        }

        public String dailyForecastByCityName(String cityName, String countryCode, byte count) throws UnsupportedEncodingException {
            String address = owmAddress.dailyForecastByCityName(cityName, countryCode, count);
            return httpGET(address);
        }

        public String dailyForecastByCityCode(long cityCode, byte count) {
            String address = owmAddress.dailyForecastByCityCode(cityCode, count);
            return httpGET(address);
        }

        public String dailyForecastByCoordinates(float latitude, float longitude, byte count) {
            String address = owmAddress.dailyForecastByCoordinates(latitude, longitude, count);
            return httpGET(address);
        }

        /**
         * Implements HTTP's GET method
         *
         * @param requestAddress Address to be loaded
         * @return Response if successful, else <code>null</code>
         * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec9.html">HTTP - (9.3) GET</a>
         */
        private String httpGET(String requestAddress) {
            Request request = new Request.Builder()
                    .get()
                    .url(requestAddress)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .header("Accept-Encoding", "gzip, deflate")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseString = body != null ? body.string() : null;
                if (!response.isSuccessful() || responseString == null) {
                    logger.error("Failed to request body (HTTP code {}): {}", response.code(), responseString);
                    return null;
                }
                return responseString;
            } catch (IOException e) {
                logger.error("Failed to call OpenWeatherMap API", e);
                return null;
            }
        }
    }
}
