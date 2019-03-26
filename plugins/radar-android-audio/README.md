# Audio RADAR-pRMT

Audio plugin for the RADAR passive remote monitoring app. It uses openSMILE to process audio from
the phone's microphone, extracts features from it, and sends those features. 

## Installation

To add the plugin code to your app, add the following snippet to your app's `build.gradle` file.

```gradle
dependencies {
    runtimeOnly "org.radarbase:radar-android-audio:$radarCommonsAndroidVersion"
}
```

## Configuration

To enable this plugin, add the provider `.passive.audio.OpenSmileAudioProvider` to `device_services_to_connect` property of the configuration.

| Parameter | Type | Default | Description |
| --------- | ---- | ------- | ----------- |
| `audio_duration` | int (seconds) | 15 | Length in seconds of the audio recording when it started.  |
| `audio_record_rate` | int (seconds) | 3600 | Default interval between two consecutive audio recordings.  |
| `audio_config_file` | string (filepath) | "ComParE_2016.conf" |  Path to openSMILE configuration file. |

Config files are addressed relative to the path `src/main/assets/org/radarbase/passive/audio/opensmile`.

_Please note_:
The *actual* audio recording duration is stated here:

```
src/main/assets/org/radarbase/passive/audio/opensmile/shared/FrameModeFunctionals.conf.inc
```

However, the `audio_duration` parameter from the RadarConfiguration parameters only tells after
which time period a termination signal should be sent to the openSMILE feature extraction thread.
So `audio_duration` and `frameSize` and `frameStep` in FrameModeFunctionals.conf.inc must all be set
together correctly such that all values correspond to each other.

This plugin produces data for the following topics: (types starts with `org.radarcns.passive.opensmile` namespace)

| Topic | Type | Description |
| ----- | ---- | ----------- |
| `android_processed_audio` | `OpenSmile2PhoneAudio` | Raw base-64 encoded data, according to the provided config file. |

## License

The radar-android-audio plugin includes a modified copy of OpenSMILE, and it is subject to the `src/main/jni/opensmile-2.3.0/COPYING` file. In addition, Please cite openSMILE in your publications by citing the following paper:
                                                                                                                                                                            
> Florian Eyben, Martin Wöllmer, Björn Schuller: "openSMILE - The Munich Versatile and Fast Open-Source Audio Feature Extractor", Proc. ACM Multimedia (MM), ACM, Florence, Italy, ISBN 978-1-60558-933-6, pp. 1459-1462, 25.-29.10.2010.

Please note that this plugin may only be used for personal or academic purposes, or for commercial research not directly associated with product development and with the primary purpose of publishing and sharing results with the academic world. For the exact terms, please refer to the `src/main/jni/opensmile-2.3.0/COPYING` file.
