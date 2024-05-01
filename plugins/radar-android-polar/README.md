# Polar plugin RADAR-pRMT

Application to be run on an Android 5.0 (or later) device with Bluetooth Low Energy (Bluetooth 4.0 or later), to interact with a Polar device.

The plugin application uses Bluetooth Low Energy requirement, making it require coarse location permissions. This plugin does not collect location information.

This plugin has currently been tested using Polar's H10 heart rate sensor, but should also be compatible with the Polar H9 Heart rate sensor, Polar Verity Sense Optical heart rate sensor, OH1 Optical heart rate sensor, Ignite 3 watch and Vantage V3 watch, as listed on the [POLAR BLE SDK] GitHub [1].

The following H10 features have been implemented:
- BatteryLevel
- Heart Rate (as bpm) with sample rate of 1Hz. 
- Electrocardiography (ECG) data in µV with sample rate 130Hz.
- Accelerometer data with a sample rate of 25Hz and range of 2G. Axis specific acceleration data in mG.
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
