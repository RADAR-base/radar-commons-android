# Faros RADAR-pRMT plugin

Plugin for the eMotion Faros 180 and 360 devices. The source code for device interaction is
closed source and maintained by The Hyve. For more information, please contact The Hyve.

## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
repositories {
    maven { url  'https://repo.thehyve.nl/content/repositories/releases' }
}

dependencies {
    runtimeOnly "org.radarbase:radar-android-faros:$radarCommonsAndroidVersion"
}
```

## Configuration

This plugin takes the following Firebase configuration parameters:

| Name | Type | Default | Description |
| ---- | ---- | ------- | ----------- |
| `bittium_faros_acceleration_rate` | int (Hz) | `25` | How frequently acceleration values are collected. Use `0` to disable acceleration data. |
| `bittium_faros_acceleration_resolution` | float (g) | `0.001` | Resolution of acceleration values. A higher resolution will result in lower range. |
| `bittium_faros_ecg_rate` | int (Hz) | `125` | How frequently ECG data is collected. Use `0` to disable ECG data. |
| `bittium_faros_ecg_resolution` | float (µV) | `1.0` | Resolution of ECG values. A higher resolution will result in lower range. |
| `bittium_faros_ecg_channels` | int | `1` | Number of ECG channels to activate. Faros 360 supports 3 channels, other devices support 1. |
| `bittium_faros_ecg_filter_frequency` | float (Hz) | `0.05` | High pass filter frequency for the ECG channel. |
| `bittium_faros_temperature_enable` | boolean | `true` | Whether to send temperature readings. Only the Faros 360 supports temperature readings. |
| `bittium_faros_inter_beat_interval_enable` | boolean | `true` | Whether to send inter-beat-interval readings. |
| `bittium_faros_battery_interval` | int (s) | `60` = 1 minute | How often to send the battery level. Use `0` to disable precise battery level collection and opt for approximate battery level instead. |

This plugin produces data for several Kafka topics. All value types are prefixed with the `org.radarcns.passive.bittium` namespace.

| Topic | Type | Description |
| ----- | ---- | ----------- |
| `android_bittium_faros_acceleration` | `BittiumFarosAcceleration` | Acceleration values. |
| `android_bittium_faros_ecg` | `BittiumFarosEcg` | ECG signal. |
| `android_bittium_faros_inter_beat_interval` | `BittiumFarosInterBeatInterval` | Inter-beat-interval derived from the ECG signal. |
| `android_bittium_faros_temperature` | `BittiumFarosTemperature` | Temperature. |
| `android_bittium_faros_battery_level` | `BittiumFarosBatteryLevel` | Battery level. If `battery_level_interval` is set to `0`, this is an approximation. |
