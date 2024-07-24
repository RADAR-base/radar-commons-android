# Polar plugin RADAR-pRMT

Application to be run on an Android 5.0 (or later) device with Bluetooth Low Energy (Bluetooth 4.0 or later), to interact with a Polar device.

The plugin application uses Bluetooth Low Energy requirement, making it require coarse location permissions. This plugin does not collect location information.

This plugin connects to a Polar device, of which the deviceId is hardcoded in PolarManager.

This plugin has currently been tested with the Polar H10, Polar Vantage V3 and Polar Verity Sense, of which the following topics are implemented:

| Polar device                   | Topic                       | Description                                                |
|--------------------------------|-----------------------------|------------------------------------------------------------| 
| Polar H10 | android_polar_battery_level | Battery level |
|  | android_polar_heart_rate    | Heart rate (bpm) with sample rate 1Hz|
|  | android_polar_ecg           | Electrocardiography (ECG) data in µV with sample rate 130Hz|
|  | android_polar_acceleration  | Accelerometer data with a sample rate of 25Hz, a resoltion of 16 and range of 2G. Axis specific acceleration data in mG.|
| Polar Vantage V3 | android_polar_battery_level | Battery level|
|  | android_polar_heart_rate    | Heart rate (bpm) in 1Hz frequency|
| Polar Verity Sense | android_polar_battery_level | Battery level|
|  | android_polar_heart_rate    | Heart rate (bpm) with sample rate 1Hz|
|  | android_polar_ppg           | PPG data with a sample rate of 55Hz, a resolution of 22 using 4 channels. |
|  | android_polar_ppi           | PP interval representing cardiac pulse-to-pulse interval extracted from PPG signal.|

****
## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation "org.radarbase:radar-android-polar:$radarCommonsAndroidVersion"
    implementation 'com.github.polarofficial:polar-ble-sdk:5.5.0'
    implementation 'io.reactivex.rxjava3:rxjava:3.1.6'
    implementation 'io.reactivex.rxjava3:rxandroid:3.0.2'
}
```

Add `org.radarbase.passive.polar.PolarProvider` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

Add the provider `.polar.PolarProvider` to the Firebase Remote Config `plugins` variable.

## Contributing

This plugin was build using the [POLAR BLE SDK][1].

[1]: https://github.com/polarofficial/polar-ble-sdk
