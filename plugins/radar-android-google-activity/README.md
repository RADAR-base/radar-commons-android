# Google Activity plugin of RADAR-pRMT

Plugin to monitor the user activity transition data. The user activity data is tracked via Google Activity Recognition API. The API  automatically detects activities by periodically reading short bursts of sensor data and processing them using machine learning models. This API provides the activity and transition of it.

This plugin requires the Android `ACTIVITY_RECOGNITION` permission for devices with API level 29 or higher, and the `com.google.android.gms.permission.ACTIVITY_RECOGNITION` permission for devices with API level 28 or lower.

## Installation

To add the plugin to your app, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    implementation "org.radarbase:radar-android-google-activity:$radarCommonsAndroidVersion"
}
```

Add `org.radarbase.passive.google.activity.GoogleActivityProvider` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

To enable this plugin, add the provider `google_activity` to `plugins` property of the configuration.

This plugin produces data for the following topics: (types starts with `org.radarcns.passive.google` namespace)

| Topic                                      | Type                            | Description                                                                           |
|--------------------------------------------|---------------------------------|---------------------------------------------------------------------------------------|
 | `android_google_activity_transition_event` | `GoogleActivityTransitionEvent` | Represents an activity transition event, for example start to walk, stop running etc. |