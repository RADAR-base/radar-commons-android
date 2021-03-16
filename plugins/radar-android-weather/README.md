# Weather API

Application to be run on an Android 5.0 (or later) device.

This module requests current weather data from an external API and sends this to the backend. By default, the data is requested every three hours. In addition to basic weather metrics (temperature, pressure, humidity, precipitation), the module also sends the time of day of sunrise and sunset.

All data is based on the last known location of the phone. This location could be outdated. In order to prevent this, use this module in combination with the `PhoneLocationProvider` of the `org.radarcns:radar-android-phone` package.

The following weather API is implemented:
 - [OpenWeatherMap](https://openweathermap.org/current)

## Installation

Include this plugin in a RADAR app by adding the following configuration to `build.gradle`:

```gradle
repositories {
    mavenCentral()
}

dependencies {
    compile 'org.radarcns:radar-android-weatherapi:<version>'
}
```
Add `org.radarbase.passive.weather.WeatherApiManager` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration
To enable this plugin add `weather_api` to the `plugins` property of the configuration in Firebase or `remote_config_defaults.xml`)

The following parameters are available:

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| `weather_api_key` | string | | The API key for the given API source. See below for a description of how a key can be retrieved. |
| `weather_api_source` | string | "openweathermap" | The name of the API where the weather data will be requested from. The only supported API for now is openweathermap.  |
| `weather_query_interval_seconds` | int (s) | 10,800 (=3 hours) | Interval between successive requests to the weather API. |

The api key for access to the OpenWeatherMap API can be retrieved by [signing up for free](http://openweathermap.org/price#weather). Note that the free plan is subject to a maximum number of calls per minute and has a limited data update frequency.

Data is sent to the `android_local_weather` topic using the `org.radarcns.passive.weather.LocalWeather` schema.

## Contributing

To add a new weather source, implement the `WeatherApi` interface and add its instantiation in the `WeatherApiManager` constructor.

## License

Code under the `src/main/java/net` directory is subject to the following license.

> Copyright (c) 2013-2015 Ashutosh Kumar Singh <me@aksingh.net>
> 
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
> 
> The above copyright notice and this permission notice shall be included in
> all copies or substantial portions of the Software.
> 
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
> THE SOFTWARE.
