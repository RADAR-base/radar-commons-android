# Phone app usage plugin of RADAR-pRMT

Plugin to monitor app usage on an Android device. Application usage events are only gathered for Android 5.1 and later.

This package requires the Android `PACKAGE_USAGE_STATS` permission. For possible future permission restrictions by Google Play, only include this plugin if usage statistics are actually required.

## Installation

To add the plugin code to your application, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    implementation "org.radarbase:radar-android-phone-usage:$radarCommonsAndroidVersion"
}
```
Add `org.radarbase.passive.phone.usage.PhoneUsageProvider` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

To enable this plugin, add the provider `phone_usage` to `plugins` property of the configuration.

The following Firebase parameters are available:

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| `phone_usage_interval_seconds` | int (s) | 3600 (= 1 hour) | Interval for gathering Android usage stats. |

This produces data to the following Kafka topics (all types are prefixed with the `org.radarcns.passive.phone` package).

| Topic | Type |
| ----- | ---- |
| `android_phone_user_interaction` | `PhoneUserInteraction` |
| `android_phone_usage_event` | `PhoneUsageEvent` |
