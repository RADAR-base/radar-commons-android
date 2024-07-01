package org.radarbase.passive.phone.audio.input

import android.media.AudioFormat
import android.media.MediaRecorder
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService

class PhoneAudioInputService: SourceService<PhoneAudioInputState>() {

    override val defaultState: PhoneAudioInputState
        get() = PhoneAudioInputState()

    override fun createSourceManager(): PhoneAudioInputManager = PhoneAudioInputManager(this)

    override fun configureSourceManager(
        manager: SourceManager<PhoneAudioInputState>,
        config: SingleRadarConfiguration
    ) {
        manager as PhoneAudioInputManager
        manager.audioSource = config.getInt(PHONE_AUDIO_INPUT_AUDIO_SOURCE, PHONE_AUDIO_INPUT_AUDIO_SOURCE_DEFAULT)
        manager.bufferSize = config.getInt(PHONE_AUDIO_INPUT_RECORDER_BUFFER_SIZE, PHONE_AUDIO_INPUT_RECORDER_BUFFER_SIZE_DEFAULT)
        manager.audioFormat = config.getInt(PHONE_AUDIO_INPUT_CURRENT_AUDIO_FORMAT, PHONE_AUDIO_INPUT_CURRENT_AUDIO_FORMAT_DEFAULT)
        manager.channel = config.getInt(PHONE_AUDIO_INPUT_CURRENT_CHANNEL, PHONE_AUDIO_INPUT_CURRENT_CHANNEL_DEFAULT)
        manager.sampleRate = config.getInt(PHONE_AUDIO_INPUT_CURRENT_SAMPLE_RATE, PHONE_AUDIO_INPUT_CURRENT_SAMPLE_RATE_DEFAULT)
    }

    companion object {
        private const val PHONE_AUDIO_INPUT_PREFIX = "phone-audio-input-"
        const val PHONE_AUDIO_INPUT_AUDIO_SOURCE = PHONE_AUDIO_INPUT_PREFIX + "audio-source"
        const val PHONE_AUDIO_INPUT_RECORDER_BUFFER_SIZE = PHONE_AUDIO_INPUT_PREFIX + "recorder-buffer-size"
        const val PHONE_AUDIO_INPUT_CURRENT_AUDIO_FORMAT = PHONE_AUDIO_INPUT_PREFIX + "current-audio-format"
        const val PHONE_AUDIO_INPUT_CURRENT_CHANNEL = PHONE_AUDIO_INPUT_PREFIX + "current-channel"
        const val PHONE_AUDIO_INPUT_CURRENT_SAMPLE_RATE = PHONE_AUDIO_INPUT_PREFIX + "current-sample-rate"

        const val PHONE_AUDIO_INPUT_AUDIO_SOURCE_DEFAULT = MediaRecorder.AudioSource.MIC
        const val PHONE_AUDIO_INPUT_RECORDER_BUFFER_SIZE_DEFAULT = -1
        const val PHONE_AUDIO_INPUT_CURRENT_AUDIO_FORMAT_DEFAULT = AudioFormat.ENCODING_PCM_16BIT
        const val PHONE_AUDIO_INPUT_CURRENT_CHANNEL_DEFAULT = AudioFormat.CHANNEL_IN_MONO
        const val PHONE_AUDIO_INPUT_CURRENT_SAMPLE_RATE_DEFAULT = 16000
    }
}