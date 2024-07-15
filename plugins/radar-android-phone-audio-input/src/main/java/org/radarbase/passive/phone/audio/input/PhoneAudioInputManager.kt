package org.radarbase.passive.phone.audio.input

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.STATE_INITIALIZED
import android.os.Environment
import android.os.Environment.DIRECTORY_DOCUMENTS
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.SafeHandler
import org.radarbase.passive.phone.audio.input.PhoneAudioInputService.Companion.LAST_RECORDED_AUDIO_FILE
import org.radarbase.passive.phone.audio.input.PhoneAudioInputService.Companion.PHONE_AUDIO_INPUT_SHARED_PREFS
import org.radarbase.passive.phone.audio.input.utils.InputRecordInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.RandomAccessFile

class PhoneAudioInputManager(service: PhoneAudioInputService) :
    AbstractSourceManager<PhoneAudioInputService, PhoneAudioInputState>(service), PhoneAudioInputState.AudioRecordManager {

    private var audioRecord: AudioRecord? = null
    private var randomAccessWriter: RandomAccessFile? = null
//    private var bufferedOutputStream: BufferedOutputStream? = null
    private var buffer: ByteArray = byteArrayOf()
    private val audioRecordingHandler = SafeHandler.getInstance("PHONE-AUDIO-INPUT", Process.THREAD_PRIORITY_BACKGROUND)
    private val recordProcessingHandler: SafeHandler = SafeHandler.getInstance("AUDIO-RECORD-PROCESSING", Process.THREAD_PRIORITY_AUDIO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pluginStatusInfo: InputRecordInfo = InputRecordInfo()
    private val preferences: SharedPreferences = service.getSharedPreferences(PHONE_AUDIO_INPUT_SHARED_PREFS, Context.MODE_PRIVATE)

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
    @Volatile
    private var currentlyRecording: Boolean = false

    init {
        name = service.getString(R.string.phone_audio_input_display_name)
        bitsPerSample = if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) 16 else 8
        numChannels = if (channel == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        framePeriod = sampleRate * TIMER_INTERVAL/1000

        val internalDirs = service.filesDir
        status = if (internalDirs != null) {
            audioDir = File(Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS), "org.radarbase.passive.audio.input")
//            audioDir = File(internalDirs, "org.radarbase.passive.phone.audio.input")
            val dirCreated = audioDir.mkdirs()
            val directoryExists = audioDir.exists()
            logger.debug("Directory for saving audio file, created: {}, exists: {}", dirCreated, directoryExists)
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
        recordProcessingHandler.start()
        createRecorder()
        state.audioRecordManager = this
    }

    private val setPreferredDeviceAndUpdate: (AudioDeviceInfo) -> Unit = {microphone ->
        audioRecord?.preferredDevice = microphone
        state.finalizedMicrophone.postValue(audioRecord?.preferredDevice)
    }


    private fun createRecorder() {
        audioRecordingHandler.execute {
            if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                status = SourceStatusListener.Status.CONNECTING
                // If using default values: framePeriod = 16000 * 0.12 = 1920 samples.
                framePeriod = sampleRate * TIMER_INTERVAL / 1000
                // For default values: bufferSize = 1920 * 16 * 1 * 2 / 8 = 7680 bytes.
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
                            mainHandler.post(::observeMicrophones)
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

    override fun clear() {
        clearAudioDirectory()
    }

    override fun setPreferredMicrophone(microphone: AudioDeviceInfo) {
        logger.warn("Setting prioritized microphone: true")
        state.microphonePrioritized = true
        microphone.let(setPreferredDeviceAndUpdate)
    }

    private fun observeMicrophones() {
        state.connectedMicrophones.observe(service) { connectedMicrophones ->
            audioRecordingHandler.execute {
                if (connectedMicrophones?.size == 0) {
                    logger.warn("No connected microphone")
                }
                if (state.microphonePrioritized && state.finalizedMicrophone.value !in connectedMicrophones) {
                    state.microphonePrioritized = false
                    logger.info("Microphone prioritize?: false")
                }
                logger.info("PhoneAudioInputManager: Connected microphones: {}", connectedMicrophones.map { it.productName })
                if (!state.microphonePrioritized) {
                    logger.info("Running device selection logic")
                    runDeviceSelectionLogic(connectedMicrophones)
                } else {
                    state.finalizedMicrophone.value?.let(setPreferredDeviceAndUpdate)
                }
                logger.info("Preferred audio input device: {}", audioRecord?.preferredDevice?.productName)
                logger.info("Selected audio input device: {}", audioRecord?.routedDevice?.productName)
            }
        }
    }

    private val deviceSelectionLogic: ((List<AudioDeviceInfo>) -> Unit) = { connectedMicrophones ->
        val finalizedMicrophone  =
            if (connectedMicrophones.any { it.type == TYPE_USB_DEVICE || it.type == TYPE_USB_HEADSET }) {
                val finalizedMic =
                    connectedMicrophones.firstOrNull { it.type == TYPE_USB_DEVICE || it.type == TYPE_USB_HEADSET }
                logger.info("Setting the default audio input device {}", finalizedMic)
                finalizedMic
            } else if (connectedMicrophones.any { it.type == TYPE_BLUETOOTH_A2DP || it.type == TYPE_BLUETOOTH_SCO }) {
                val finalizedMic =
                    connectedMicrophones.firstOrNull { it.type == TYPE_BLUETOOTH_SCO || it.type == TYPE_BLUETOOTH_A2DP }
                logger.info("Setting the default audio input device {}", finalizedMic)
                finalizedMic
            } else {
                connectedMicrophones.firstOrNull()
            }
        finalizedMicrophone?.let(setPreferredDeviceAndUpdate)
    }

//    private fun List<AudioDeviceInfo>.preferByDeviceType(types: IntArray): AudioDeviceInfo? {

//    }

    private fun runDeviceSelectionLogic(connectedMicrophones: List<AudioDeviceInfo>) {
        val finalizedMicrophone  =
            if (connectedMicrophones.any { it.type == TYPE_USB_DEVICE || it.type == TYPE_USB_HEADSET }) {
                val finalizedMic =
                    connectedMicrophones.firstOrNull { it.type == TYPE_USB_DEVICE || it.type == TYPE_USB_HEADSET }
                logger.info("Setting the default audio input device {}", finalizedMic)
                finalizedMic
            } else if (connectedMicrophones.any { it.type == TYPE_BLUETOOTH_A2DP || it.type == TYPE_BLUETOOTH_SCO }) {
                val finalizedMic =
                    connectedMicrophones.firstOrNull { it.type == TYPE_BLUETOOTH_SCO || it.type == TYPE_BLUETOOTH_A2DP }
                logger.info("Setting the default audio input device {}", finalizedMic)
                finalizedMic
            } else {
                connectedMicrophones.firstOrNull()
            }
        finalizedMicrophone?.let(setPreferredDeviceAndUpdate)
    }

    private fun clearAudioDirectory() {
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

    private fun startAudioRecording() {
        audioRecordingHandler.execute {
            setupRecording()
            if ((audioRecord?.state == STATE_INITIALIZED) && (recordingFile != null)) {
                if (!state.microphonePrioritized) {
                    state.connectedMicrophones.value?.let {
                        runDeviceSelectionLogic(it)
                    }
                } else {
                    state.finalizedMicrophone.value?.let(setPreferredDeviceAndUpdate)
                }
                audioRecord?.startRecording()
                state.isRecording.postValue(true)
//                state.currentRecordingFileName.postValue(recordingFile?.name)
                recordProcessingHandler.execute{
                    audioRecord?.read(buffer, 0, buffer.size)
                    currentlyRecording = true
                    state.finalizedMicrophone.postValue(audioRecord?.routedDevice)
                    logger.info("Finalized routed device: {}", state.finalizedMicrophone.value?.productName)
                }
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
//        clearAudioDirectory()
        setRecordingPath()
        writeFileHeaders()
    }

    private fun setRecordingPath() {
//        recordingFile = File(audioDir, "phone_audio_input"+System.currentTimeMillis()+".wav")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

        // Create the file in the Downloads directory
        recordingFile = File(audioDir, "phone_audio_input" + System.currentTimeMillis() + ".wav")

        preferences.edit()
            .putString(LAST_RECORDED_AUDIO_FILE, recordingFile!!.absolutePath)
            .apply()
        randomAccessWriter = RandomAccessFile(recordingFile, "rw")
        pluginStatusInfo.recordingPathSet = true
    }

    private fun writeFileHeaders() {

        randomAccessWriter?.apply {
            setLength(0) // Set file length to 0, to prevent unexpected behavior in case the file already existed

            val header = ByteArray(44)
            // RIFF header
            header[0] = 'R'.code.toByte() // RIFF/WAVE header
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (0 and 0xff).toByte() // Final file size not known yet, write 0
            header[5] = ((0 shr 8) and 0xff).toByte()
            header[6] = ((0 shr 16) and 0xff).toByte()
            header[7] = ((0 shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte() // 'fmt ' chunk
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16 // 4 bytes: size of 'fmt ' chunk
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // format = 1
            header[21] = 0
            header[22] = numChannels.toByte()
            header[23] = 0
            header[24] = (sampleRate.toLong() and 0xffL).toByte()
            header[25] = ((sampleRate.toLong() shr 8) and 0xffL).toByte()
            header[26] = ((sampleRate.toLong() shr 16) and 0xffL).toByte()
            header[27] = ((sampleRate.toLong() shr 24) and 0xffL).toByte()
            val byteRate = sampleRate * bitsPerSample * numChannels / 8
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            val blockAlign = numChannels * bitsPerSample / 8
            header[32] = blockAlign.toByte() // block align
            header[33] = 0
            header[34] = bitsPerSample.toByte() // bits per sample
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (0 and 0xff).toByte()
            header[41] = ((0 shr 8) and 0xff).toByte()
            header[42] = ((0 shr 16) and 0xff).toByte()
            header[43] = ((0 shr 24) and 0xff).toByte()
            write(header, 0, 44)
        }
        pluginStatusInfo.fileHeadersWritten = true
    }

    private val updateListener = object : AudioRecord.OnRecordPositionUpdateListener {
        override fun onMarkerReached(recorder: AudioRecord?) {
            // No Action
        }

        override fun onPeriodicNotification(recorder: AudioRecord?) {
            if (currentlyRecording) {
                audioRecordingHandler.execute {
                    audioRecord?.let {
                        val dataRead = it.read(buffer, 0, buffer.size)
                        randomAccessWriter?.write(buffer)
                        payloadSize += dataRead
                        logger.debug("onPeriodicNotification: Recording Audio")
                    }
                }
            } else {
                logger.warn("Callback: onPeriodicNotification after recording stopped.")
            }
        }
    }

    private fun stopAudioRecording() {
        logger.warn("Stopping Recording: Saving data")
        audioRecordingHandler.execute {
            state.isRecording.postValue(false)
            currentlyRecording = false
            audioRecord?.apply {
                setRecordPositionUpdateListener(null)
                stop()
            }
            randomAccessWriter?.apply {
                seek(4)
                writeInt(Integer.reverseBytes(36 + payloadSize))
                seek(40)
                writeInt(Integer.reverseBytes(payloadSize))
                close()
            }
        }
    }

    override fun onClose() {
        audioRecordingHandler.stop{
            audioRecord?.release()
            clearAudioDirectory()

        }
        recordProcessingHandler.stop()
    }
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PhoneAudioInputManager::class.java)

        /** The interval(ms) in which the recorded samples are output to the file */
        private const val TIMER_INTERVAL = 120
    }
}