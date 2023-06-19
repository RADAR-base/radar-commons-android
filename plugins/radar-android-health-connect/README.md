# Google Health Connect integration for pRMT

A plugin for the RADAR pRMT app. The plugin can be used on an Android 9.0 (or later) device.

## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    implementation "org.radarbase:radar-android-health-connect:$radarCommonsAndroidVersion"
}
```

Add `org.radarbase.passive.healthconnect.HealthConnectProvider` to the `plugins` variable of the `RadarService` instance in your app, depending on what plugins you want to be available at runtime. See the descriptions of the plugins below.

## Configuration

Add `health_connect` to your `plugins` configuration variable to enable this plugin.

### Sensors

#### TODO

The following Firebase parameters are available:

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| **PhoneSensorProvider** |||
| `phone_sensor_default_interval` | int (ms) | 200 | Default interval between phone sensor polls. Set to `0` to disable sensors by default. |
| `phone_sensor_gyroscope_interval` | int (ms) | 200 | Interval between phone gyroscope sensor polls. Set to `0` to disable. |
| `phone_sensor_magneticfield_interval` | int (ms) | 200 | Interval between phone magnetic field sensor polls. Set to `0` to disable.  |
| `phone_sensor_steps_interval` | int (ms) | 200 | Interval between phone step counter polls. Set to `0` to disable. |
| `phone_sensor_acceleration_interval` | int (ms) | 200 | Interval between phone acceleration sensor polls. Set to `0` to disable. |
| `phone_sensor_light_interval` | int (ms) | - | Set to `0` to disable. Note that the light sensor registers every change of illuminance and can't be set to record in a specific interval |
| `phone_sensor_battery_interval_seconds` | int (s) | 600 (= 10 minutes) | Interval between phone battery level polls. |
| **PhoneLocationProvider** |||
| `phone_location_gps_interval` | int (s) | 3600 (= 1 hour) | Interval for gathering location using the GPS sensor. Set this parameter and the next to `0` to disable GPS data gathering. | 
| `phone_location_gps_interval_reduced` | int (s) | 18000 (= 5 hours) | Interval for gathering location using the GPS sensor when the battery level is low. |
| `phone_location_network_interval` | int (s) | 600 (= 10 minutes) | Interval for gathering location using network triangulation. Set this parameter and the next to `0` to disable network location gathering. |
| `phone_location_network_interval_reduced` | int (s) | 3000 (= 50 minutes) | Interval for gathering location using network triangulation when the battery level is low. |
| `phone_location_battery_level_reduced` | float (0-1) | 0.3 (= 30%) | Battery level threshold, below which to use the reduced interval configuration. |
| `phone_location_battery_level_minimum` | float (0-1) | 0.15 (= 15%) | Battery level threshold, below which to stop gathering location data altogether. |
| `phone_location_relative` | `boolean` | `true` | Whether to use relative data. If set to false, no location offsets are used and the absolute location is available. |
| **PhoneContactListProvider** |||
| `phone_contacts_list_interval_seconds` | int (s) | 86400 (= 1 day) | Interval for scanning contact list for changes. |
| **PhoneBluetoothProvider** |||
| `bluetooth_devices_scan_interval_seconds` | int (s) | 3600 (= 1 hour) | Interval for scanning Bluetooth devices. |

This produces data to the following Kafka topics (all types are prefixed with the `org.radarcns.passive.phone` package).

| Topic | Type |
| ----- | ---- |
| **PhoneSensorProvider** ||
| `android_phone_gyroscope` | `PhoneGyroscope` |
| `android_phone_magnetic_field` | `PhoneMagneticField` |
| `android_phone_step_count` | `PhoneStepCount` |
| `android_phone_acceleration` | `PhoneAcceleration` |
| `android_phone_light` | `PhoneLight` |
| `android_phone_battery_level` | `PhoneBatteryLevel` |
| **PhoneLocationProvider** ||
| `android_phone_relative_location` | `PhoneRelativeLocation` |
| **PhoneContactListProvider** ||
| `android_phone_contacts` | `PhoneContactList` |
| **PhoneBluetoothProvider** ||
| `android_phone_bluetooth_devices` | `PhoneBluetoothDevices` |
