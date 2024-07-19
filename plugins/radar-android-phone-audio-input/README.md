# RADAR PHONE AUDIO INPUT

Plugin for recording uncompressed high-quality audio, utilizing low-level classes to directly interact with hardware, with capabilities for playback and audio input device selection.


## Installation

Include this plugin in a RADAR app by adding the following configuration to `build.gradle`:
```gradle
dependencies {
    implementation "org.radarbase:radar-android-phone-audio-input:$radarCommonsAndroidVersion"
}
```
Add the provider `org.radarbase.passive.phone.audio.input` to the `plugins` variable of the `RadarService` instance in your app.

## Configuration

To enable this plugin, add the provider `phone_audio_input` to `plugins` property of the configuration.

This plugin takes the following Firebase configuration parameters:


| Name                                     | Type | Default                              | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|------------------------------------------|------|--------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `phone-audio-input-audio-source`         | int  | `1` = MediaRecorder.AudioSource.MIC  | Specifies the source of audio input for recording. The default value is 1, which corresponds to MediaRecorder.AudioSource.MIC. This means the application will use the device's microphone for audio input.                                                                                                                                                                                                                                                |
| `phone-audio-input-recorder-buffer-size` | int  | `-1`                                 | Defines the size of the buffer used by the audio recorder. The buffer is a temporary storage area for audio data before it is processed or saved. Setting this to -1 lets the system choose an optimal buffer size based on the current audio configuration. Adjusting this can impact audio latency and quality.                                                                                                                                          |
| `phone-audio-input-current-audio-format` | int  | `2` = AudioFormat.ENCODING_PCM_16BIT | Determines the encoding format of the recorded audio. The default value 2 corresponds to AudioFormat.ENCODING_PCM_16BIT, which means the audio will be recorded in 16-bit Pulse Code Modulation (PCM). PCM is a common uncompressed audio format that provides high-quality sound. Other formats like 8-bit PCM can also be used, but they may reduce audio quality. Note that 8-bit encoding might not work on all devices, so 16-bit should be preferred |
| `phone-audio-input-current-channel`      | int  | `0x10` = 16                          | Specifies the number of audio channels to be used during recording. The default value 0x10 represents mono (1 channel), meaning audio will be recorded from a single channel. Using mono recording is typical for voice recording to save space and simplify processing. Stereo (2 channels) can also be used for higher quality audio that captures a sense of direction.                                                                                 |
| `phone-audio-input-current-sample-rate`  | int  | `16000`                              | Defines the number of audio samples captured per second. The default value is 16000 Hz (16 kHz), which is a common sample rate for voice recording, balancing audio quality and file size. Higher sample rates, such as 44100 Hz (CD quality), provide better audio fidelity but result in larger file sizes. Lower sample rates may be used for lower quality or reduced file size needs.                                                                 |



This plugin produces data for the following topics: (types starts with `org.radarcns.passive.google` prefix)

| Topic                       | Type              | Description                                                                                                                                                                                                                              |
|-----------------------------|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `android_phone_audio_input` | `PhoneAudioInput` | This topic captures high-quality, uncompressed audio data recorded by the user's device. The PhoneAudioInput type includes detailed information about the audio recording, such as timestamps, file metadata, and device specifications. |
