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

    var audioSource: Int
        get() = state.audioSource.get()
        set(value) { state.audioSource.set(value) }
    var sampleRate: Int
        get() = state.sampleRate.get()
        set(value) { state.sampleRate.set(value) }
    var channel: Int
        get() = state.channel.get()
        set(value) { state.channel.set(value) }
    var audioFormat: Int
        get() = state.audioFormat.get()
        set(value) { state.audioFormat.set(value) }
    var bufferSize: Int
        get() = state.bufferSize.get()
        set(value) { state.bufferSize.set(value) }

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

                bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    channel,
                    audioFormat
                )
                audioRecord = AudioRecord(
                    audioSource,
                    sampleRate,
                    channel,
                    audioFormat,
                    bufferSize
                )
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