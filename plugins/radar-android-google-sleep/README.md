# Google sleep plugin of RADAR-pRMT

Plugin to monitor the user sleep data. Sleep data is tracked in form of GoogleSleepSegment and GoogleSleepClassify events.

This plugin requires the Android `ACTIVITY_RECOGNITION` permission for devices with API level 29 or higher, and the `com.google.android.gms.permission.ACTIVITY_RECOGNITION` permission for devices with API level 28 or lower.

## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    implementation "org.radarbase:radar-android-google-sleep:$radarCommonsAndroidVersion"
}
```

Add `org.radarbase.passive.google.sleep.GoogleSleepProvider` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

To enable this plugin, add the provider `google_sleep` to `plugins` property of the configuration.

This plugin produces data for the following topics: (types starts with `org.radarcns.passive.google` namespace)

| Topic | Type | Description                                                  |
| ----- | ---- |--------------------------------------------------------------|
| `android_google_sleep_segment_event` | `GoogleSleepSegmentEvent` | Represents the result of sleep data after the user is awake.                                             |
| `android_google_sleep_classify_event` | `GoogleSleepClassifyEvent` | Sleep classification event including the sleep confidence, device motion and ambient light level.                                                            |
