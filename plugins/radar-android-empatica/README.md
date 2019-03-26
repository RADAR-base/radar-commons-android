# Empatica plugin for RADAR-pRMT

Application to be run on an Android 5.0 (or later) device with Bluetooth Low Energy (Bluetooth 4.0 or later), to interact with the Empatica.

The plugin application only runs on an ARM architecture and because of its Bluetooth Low Energy requirement, it also requires coarse location permissions. This plugin does not collect location information.

## Installation

First, request an Empatica Connect developer account from [Empatica's Developer Area][1]. Download the Empatica Android SDK there. Copy downloaded AAR `empalink-2.2.aar` from the Empatica Android SDK package to the `libs` directory of your application. Then the project can be edited with Android Studio. Add the following to your `build.gradle`:
        
 ```gradle
 repositories {
     flatDir { dirs 'libs' }
 }
 
 dependencies {
     runtimeOnly "org.radarbase:radar-android-empatica:$radarCommonsAndroidVersion"
 }
 ```

### Configuration

Add the provider `org.radarbase.passive.empatica.E4ServiceProvider` to the Firebase Remote Config `device_services_to_connect` variable.

Request an Empatica API key for your Empatica Connect account. Set your Empatica API key in the `empatica_api_key` Firebase parameter. The plugin can now be used with devices linked to your account.

## Contributing

To build this repository, download the Empatica Android SDK from the [Empatica Developer Area][1]. Copy the `empalink-2.2.aar` from the Empatica Android SDK package to the `libs` directory. Remove the `gradle.skip` file in the project directory to enable building it.

[1]: https://www.empatica.com/connect/developer.php
