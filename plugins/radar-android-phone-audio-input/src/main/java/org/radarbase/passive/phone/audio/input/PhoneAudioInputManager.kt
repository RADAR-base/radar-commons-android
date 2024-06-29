package org.radarbase.passive.phone.audio.input

import android.annotation.SuppressLint
import android.media.AudioRecord
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PhoneAudioInputManager(service: PhoneAudioInputService) :
    AbstractSourceManager<PhoneAudioInputService, PhoneAudioInputState>(service) {

    private var audioRecord: AudioRecord? = null

    @get: Synchronized
    @set: Synchronized
    var sampleRates: Array<Int> = arrayOf(44100, 22050, 16000, 11025, 8000)

    var audioSource: Int
        get() = state.audioSource.get()
        set(value) { state.audioSource.set(value) }
    var currentSampleRate: Int
        get() = state.currentSampleRate.get()
        set(value) { state.currentSampleRate.set(value) }
    var currentChannel: Int
        get() = state.currentChannel.get()
        set(value) { state.currentChannel.set(value) }
    var currentAudioFormat: Int
        get() = state.audioFormat.get()
        set(value) { state.audioFormat.set(value) }
    var recorderBufferSize: Int
        get() = state.recorderBufferSize.get()
        set(value) { state.recorderBufferSize.set(value) }

    init {
        name = service.getString(R.string.phone_audio_input_display_name)
    }

    override fun start(acceptableIds: Set<String>) {
        register()
        status = SourceStatusListener.Status.READY
        createRecorder()
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder() {
        status = SourceStatusListener.Status.CONNECTING
        var i = 0
        try {
            do {
                recorderBufferSize = AudioRecord.getMinBufferSize(
                    currentSampleRate,
                    currentChannel,
                    currentAudioFormat
                )
                audioRecord = AudioRecord(
                    audioSource,
                    currentSampleRate,
                    currentChannel,
                    currentAudioFormat,
                    recorderBufferSize
                )
            } while ((++i < sampleRates.size) && (audioRecord?.state != AudioRecord.STATE_INITIALIZED))
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                status = SourceStatusListener.Status.DISCONNECTED
            } else if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                logger.info("Successfully initialized AudioRecord")
                status = SourceStatusListener.Status.CONNECTED
            }
        } catch (ex: IllegalArgumentException) {
            logger.error("Invalid parameters passed to AudioRecord constructor. ", ex)
        } catch (ex: Exception) {
            logger.error("Exception while initializing AudioRecord. ", ex)
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PhoneAudioInputManager::class.java)
    }
}