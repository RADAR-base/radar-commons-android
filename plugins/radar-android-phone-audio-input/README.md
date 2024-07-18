# RADAR PHONE AUDIO INPUT



## Installation

Include this plugin in a RADAR app by adding the following configuration to `build.gradle`:
```gradle
dependencies {
    implementation "org.radarbase:radar-android-phone-audio-input:$radarCommonsAndroidVersion"
}
```
Add `org.radarbase.passive.ppg.PhonePpgProvider` to the `plugins` variable of the `RadarService` instance in your app.
