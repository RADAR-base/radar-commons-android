# RADAR Android PPG

Plugin to measure PPG using the camera of a phone. This essentially takes preview snapshots of the camera when the left index finger is pressed against the camera. It then measures the amount of red, green and blue components. Later analysis can determine how this translates to blood volume pulse.

## Installation

Include this plugin in a RADAR app by adding the following configuration to `build.gradle`:
```gradle
dependencies {
    implementation "org.radarbase:radar-android-ppg:$radarCommonsAndroidVersion"
}
```
Add `org.radarbase.passive.ppg.PhonePpgProvider` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

To enable this plugin, add the provider `phone_ppg` to `plugins` property of the configuration.

Other configuration properties are the following:

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| `phone_ppg_measurement_seconds` | int (s) | 60 | Number of seconds that a single measurement is supposed to take. |
| `phone_ppg_measurement_width` | int (px) | 200 | Preferred camera image width to analyze. Increasing this will make analysis slower. |
| `phone_ppg_measurement_height` | int (px) | 200 | Preferred camera image height to analyze. Increasing this will make analysis slower. |

This produces data to the following Kafka topics:

| Topic | Type |
| ----- | ---- |
| `android_phone_ppg` | `org.radarcns.passive.ppg.PhonePpg` |
