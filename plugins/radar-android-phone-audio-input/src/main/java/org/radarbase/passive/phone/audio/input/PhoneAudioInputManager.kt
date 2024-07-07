package org.radarbase.passive.phone.audio.input

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.STATE_INITIALIZED
import android.os.Process
import androidx.core.content.ContextCompat
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.SafeHandler
import org.radarbase.passive.phone.audio.input.utils.InputRecordInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.Short.reverseBytes

class PhoneAudioInputManager(service: PhoneAudioInputService) :
    AbstractSourceManager<PhoneAudioInputService, PhoneAudioInputState>(service), PhoneAudioInputState.AudioRecordManager {

    private var audioRecord: AudioRecord? = null
    private var randomAccessWriter: RandomAccessFile? = null
    private var bufferedOutputStream: BufferedOutputStream? = null
    private var buffer: ByteArray = byteArrayOf()
    private val audioRecordingHandler = SafeHandler.getInstance("PHONE-AUDIO-INPUT", Process.THREAD_PRIORITY_BACKGROUND)
    private val recordProcessingHandler: SafeHandler = SafeHandler.getInstance("AUDIO-RECORD-PROCESSING", Process.THREAD_PRIORITY_AUDIO)
    private val pluginStatusInfo: InputRecordInfo = InputRecordInfo()

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
    private var bitsPerSample: Short
    private var numChannels: Short
    private val audioDir: File?
    private var recordingFile: File? = null
    private var payloadSize: Int = 0

    init {
        name = service.getString(R.string.phone_audio_input_display_name)
        bitsPerSample = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 16 else 8
        numChannels = if (channel == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        framePeriod = sampleRate * TIMER_INTERVAL/1000

        val internalDirs = service.filesDir
        status = if (internalDirs != null) {
            audioDir = File(internalDirs, "org.radarbase.passive.phone.audio.input")
            val dirCreated = audioDir.mkdirs()
            val directoryExists = audioDir.exists()
            logger.info("Dir Created: $dirCreated.Exists: $directoryExists")
            clearAudioDirectory()
            SourceStatusListener.Status.READY
        } else {
            audioDir = null
            SourceStatusListener.Status.UNAVAILABLE
        }
    }

    override fun start(acceptableIds: Set<String>) {
        register()
        audioRecordingHandler.start()
        createRecorder()
        state.audioRecordManager = this
    }

    private fun createRecorder() {
        audioRecordingHandler.execute {
            if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                status = SourceStatusListener.Status.CONNECTING
                framePeriod = sampleRate * TIMER_INTERVAL / 1000
                bufferSize = framePeriod * bitsPerSample * numChannels * 2 / 8
                logger.info("Calculated buffer size: $bufferSize (bytes), and frame period: $framePeriod")

                val calculatedBufferSize: Int = AudioRecord.getMinBufferSize(sampleRate, channel, audioFormat)
                if (calculatedBufferSize != AudioRecord.ERROR_BAD_VALUE) {
                    if (bufferSize < calculatedBufferSize) {
                        bufferSize = calculatedBufferSize
                        framePeriod = bufferSize / (2 * bitsPerSample * numChannels / 8)
                        logger.info("Updating buffer size to: $bufferSize, and frame period to: $framePeriod")
                    }
                    buffer = ByteArray(framePeriod * bitsPerSample / 8 * numChannels)
                    try {
                        audioRecord = AudioRecord(
                            audioSource, sampleRate, channel, audioFormat, bufferSize
                        )
                        if (audioRecord?.state != STATE_INITIALIZED) {
                            disconnect()
                        } else if (audioRecord?.state == STATE_INITIALIZED) {
                            logger.info("Successfully initialized AudioRecord")
                            status = SourceStatusListener.Status.CONNECTED
                            pluginStatusInfo.recorderCreated = true
                        }
                    } catch (ex: IllegalArgumentException) {
                        logger.error("Invalid parameters passed to AudioRecord constructor. ", ex)
                    } catch (ex: Exception) {
                        logger.error("Exception while initializing AudioRecord. ", ex)
                    }
                } else {
                    logger.error("Error in calculating buffer size")
                    disconnect()
                }
            } else {
                logger.error("Permission not granted for RECORD_AUDIO, disconnecting now")
                disconnect()
            }
        }
    }

    override fun startRecording() {
        startAudioRecording()
    }

    override fun stopRecording() {
        stopAudioRecording()
    }

    private fun clearAudioDirectory() {
        audioRecordingHandler.execute {
            audioDir?.let { audioDir ->
                audioDir.parentFile
                    ?.list { _, name -> name.startsWith("phone_audio_input") && name.endsWith(".wav") }
                    ?.forEach {
                        File(audioDir.parentFile, it).delete()
                        logger.debug("Deleted audio file: {}", it)
                    }

                audioDir.walk()
                    .filter { it.name.startsWith("phone_audio_input") && it.path.endsWith(".wav") }
                    .forEach {
                        it.delete()
                        logger.debug("Deleted audio file: {}", it)
                    }
            }
        }
    }

    private fun startAudioRecording() {
        audioRecordingHandler.execute {
            setupRecording()
            if ((audioRecord?.state == STATE_INITIALIZED) && (recordingFile != null)) {
                bufferedOutputStream = BufferedOutputStream(FileOutputStream(randomAccessWriter!!.fd))
                audioRecord?.startRecording()
                state.isRecording.postValue(true)
                audioRecord?.read(buffer, 0, buffer.size)
                logger.trace("Started recording")
                audioRecord?.setRecordPositionUpdateListener(updateListener)
                audioRecord?.setPositionNotificationPeriod(framePeriod)
            } else {
                logger.error("Trying to start recording on uninitialized AudioRecord or filePath is null, state: ${audioRecord?.state}, $recordingFile")
                disconnect()
            }
        }
    }

    private fun setupRecording() {
            setRecordingPath()
            writeFileHeaders()
    }

    private fun setRecordingPath() {
        recordingFile = File(audioDir, "phone_audio_input"+System.currentTimeMillis()+".wav")
        randomAccessWriter = RandomAccessFile(recordingFile, "rw")
        pluginStatusInfo.recordingPathSet = true
    }

    private fun writeFileHeaders() {

        randomAccessWriter?.use {
            it.setLength(0) // Set file length to 0, to prevent unexpected behavior in case the file already existed
            // RIFF header
            it.writeBytes("RIFF")
            it.writeInt(0) // Final file size not known yet, write 0
            it.writeBytes("WAVE")
            // fmt sub-chunk
            it.writeBytes("fmt")
            it.writeInt(Integer.reverseBytes(16)) // Sub-chunk size, 16 for PCM
            it.writeShort(reverseBytes(1.toShort()).toInt()) // AudioFormat, 1 for PCM
            it.writeShort(reverseBytes(numChannels).toInt()) // Number of channels, 1 for mono, 2 for stereo
            it.writeInt(Integer.reverseBytes(sampleRate)) // Sample rate
            it.writeInt(Integer.reverseBytes(sampleRate * bitsPerSample * numChannels / 8)) // Byte rate, SampleRate*NumberOfChannels*BitsPerSample/8
            it.writeShort(reverseBytes((numChannels * bitsPerSample / 8).toShort()).toInt()) // Block align, NumberOfChannels*BitsPerSample/8
            it.writeShort(reverseBytes(bitsPerSample).toInt()) // Bits per sample
            // data sub-chunk
            it.writeBytes("data")
            it.writeInt(0) // Data chunk size not known yet
            pluginStatusInfo.fileHeadersWritten = true
        }
    }

    private val updateListener = object : AudioRecord.OnRecordPositionUpdateListener {
        override fun onMarkerReached(recorder: AudioRecord?) {
            // No Action
        }

        override fun onPeriodicNotification(recorder: AudioRecord?) {
            audioRecordingHandler.execute {
                audioRecord?.let {
                    val dataRead = it.read(buffer, 0, buffer.size)
                    bufferedOutputStream?.use { bos->
                        bos.write(buffer, 0, dataRead)
                    }
                    payloadSize += dataRead
                    logger.debug("onPeriodicNotification: Recording Audio")
                }
            }
        }
    }

    private fun stopAudioRecording() {
        logger.warn("Stopping Recording: Saving data")
        audioRecordingHandler.execute {
            audioRecord?.stop()
            state.isRecording.postValue(false)
            bufferedOutputStream?.close()
            randomAccessWriter?.use {
                it.seek(4)
                it.writeInt(Integer.reverseBytes(36 + payloadSize))
                it.seek(40)
                it.writeInt(Integer.reverseBytes(payloadSize))
            }
        }
    }

    override fun onClose() {
        audioRecordingHandler.stop{
            audioRecord?.release()
        }
        recordProcessingHandler.stop()
    }
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PhoneAudioInputManager::class.java)
    }
}