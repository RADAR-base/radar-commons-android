# Google places plugin of RADAR-pRMT

Plugin to monitor the user location data. Location data is tracked via Google Places API.

This plugin requires the Android any of `ACCESS_COARSE_LOCATION` or `ACCESS_FINE_LOCATION` and for android devices running on Android 10 or higher it requires additional `ACCESS_BACKGROUND_LOCATION` permission. To ensure the accuracy of the data, please do not disregard the `ACCESS_FINE_LOCATION` permission. Failure to grant this permission may result in erratic behavior and the cessation of plugin functionality.

## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    implementation "org.radarbase:radar-android-google-places:$radarCommonsAndroidVersion"
}
```

Add `org.radarbase.passive.google.places.GooglePlacesProvider` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

To enable this plugin, add the provider `google_places` to `plugins` property of the configuration.

This plugin takes the following Firebase configuration parameters:


| Name                                         | Type          | Default            | Description                                                                                                                                                                                                                                                                                                    |
|----------------------------------------------|---------------|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `places_api_key`                             | string        | `<empty>`          | Places API key from the Google Cloud Console to work with the Google Places API                                                                                                                                                                                                                                |
| `places_interval_seconds`                    | int (seconds) | `600` = 10 minutes | Rate at which to send data for all places topics.                                                                                                                                                                                                                                                              |
| `places_should_fetch_places_id`              | boolean       | `false`            | Whether to send the Place-Id along with current place data.                                                                                                                                                                                                                                                    |
| `places_should_fetch_additional_places_info` | boolean       | `false`            | Whether to send Additional details with the user current place like city, state and country                                                                                                                                                                                                                    |
| `places_fetch_place_likelihoods_count`       | int           | `-1`               | If limited places data is needed from list of places data. Set `-1` to  to send the whole list of likelihoods.                                                                                                                                                                                                 |
| `places_fetch_place_likelihoods_bound`       | double        | `-1.0`             | Only select places that have a likelihood higher than the provided likelihood. Set to -1.0 to not limit results by likelihood.                                                                                                                                                                                 |
| `places_additional_fetch_network_delay`      | long          | `0L`               | Set the delay (in milliseconds) when `places_should_fetch_additional_places_info` is set to true. Since the `FindCurrentPlaceRequest` may retrieve multiple places simultaneously, and if a substantial number of places are retrieved, individual network calls will be made to fetch additional information. |



This plugin produces data for the following topics: (types starts with `org.radarcns.passive.google` prefix)

| Topic                        | Type               | Description                                                                                                                                   |
|------------------------------|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `android_google_places_info` | `GooglePlacesInfo` | Information of places where the user’s device is last known to be located along with an indication of the relative likelihood for each place. |
