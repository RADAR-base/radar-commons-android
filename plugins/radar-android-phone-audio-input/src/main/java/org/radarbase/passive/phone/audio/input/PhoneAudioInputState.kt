package org.radarbase.passive.phone.audio.input

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.MediaRecorder
import org.radarbase.android.source.BaseSourceState
import org.radarbase.passive.phone.audio.input.PhoneAudioInputService.Companion.PHONE_AUDIO_INPUT_CURRENT_SAMPLE_RATE_DEFAULT
import org.radarbase.passive.phone.audio.input.PhoneAudioInputService.Companion.PHONE_AUDIO_INPUT_RECORDER_BUFFER_SIZE_DEFAULT
import java.util.concurrent.atomic.AtomicInteger

class PhoneAudioInputState: BaseSourceState() {

    var audioSource: AtomicInteger = AtomicInteger(MediaRecorder.AudioSource.MIC)
    var sampleRate: AtomicInteger = AtomicInteger(PHONE_AUDIO_INPUT_CURRENT_SAMPLE_RATE_DEFAULT)
    var channel: AtomicInteger = AtomicInteger(AudioFormat.CHANNEL_IN_MONO)
    var audioFormat: AtomicInteger = AtomicInteger(AudioFormat.ENCODING_PCM_16BIT)
    var bufferSize: AtomicInteger = AtomicInteger(PHONE_AUDIO_INPUT_RECORDER_BUFFER_SIZE_DEFAULT)


    interface AudioRecordManager {
        fun startRecording()
        fun stopRecording()
    }

    interface AudioPlayerManager {
        fun startPlayback()
        fun stopPlayback()
        fun pausePlayback()
        fun resumePlayback()
    }

    interface InputAudioDeviceManager {
        fun getConnectedDevices(): List<AudioDeviceInfo>
        fun setDefaultInputDevice(device: AudioDeviceInfo)
    }

}