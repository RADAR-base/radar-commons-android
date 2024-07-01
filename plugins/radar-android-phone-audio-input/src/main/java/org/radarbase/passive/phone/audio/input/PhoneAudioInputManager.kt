package org.radarbase.passive.phone.audio.input

import android.annotation.SuppressLint
import android.media.AudioFormat
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
    /**
     * The total number of bytes needed to hold the audio data for one `framePeriod`.
     */
    var bufferSize: Int
        get() = state.bufferSize.get()
        set(value) { state.bufferSize.set(value) }

    /** The interval(ms) in which the recorded samples are output to the file */
    private val TIMER_INTERVAL = 120

    /**
     * The framePeriod is calculated as the number of samples in `TIMER_INTERVAL` milliseconds.
     * It represents how many samples correspond to the given interval.
     */
    private var framePeriod: Int
    private var bitsPerSample: Int
    private var numChannels: Int

    init {
        name = service.getString(R.string.phone_audio_input_display_name)
        bitsPerSample = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 16 else 8
        numChannels = if (channel == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        framePeriod = sampleRate * TIMER_INTERVAL/1000
    }

    override fun start(acceptableIds: Set<String>) {
        register()
        status = SourceStatusListener.Status.READY
        createRecorder()
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder() {
        status = SourceStatusListener.Status.CONNECTING
            framePeriod = sampleRate * TIMER_INTERVAL/1000
            bufferSize = framePeriod * bitsPerSample * numChannels * 2 / 8
            logger.info("Calculated buffer size: $bufferSize (bytes), and frame period: $framePeriod")

            val calculatedBufferSize: Int = AudioRecord.getMinBufferSize(sampleRate, channel, audioFormat)
            if (calculatedBufferSize != AudioRecord.ERROR_BAD_VALUE) {
                if (bufferSize < calculatedBufferSize) {
                    bufferSize = calculatedBufferSize
                    framePeriod = bufferSize / (2 * bitsPerSample * numChannels / 8)
                    logger.info("Updating buffer size to: $bufferSize, and frame period to: $framePeriod")
                }
                try {
                    audioRecord = AudioRecord(audioSource, sampleRate, channel, audioFormat, bufferSize
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
        } else {
                logger.error("Error in calculating buffer size")
                status = SourceStatusListener.Status.DISCONNECTED
            }
        }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PhoneAudioInputManager::class.java)
    }
}