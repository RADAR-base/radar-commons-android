/*
 * Copyright 2017 Universität Passau and The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.passive.audio

import android.os.Process
import android.util.Base64
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarbase.android.util.SafeHandler
import org.radarbase.passive.audio.OpenSmileAudioService.Companion.DEFAULT_RECORD_RATE
import org.radarbase.passive.audio.opensmile.SmileJNI
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.opensmile.OpenSmile2PhoneAudio
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Manages Phone sensors  */
class OpensmileAudioManager constructor(service: OpenSmileAudioService) : AbstractSourceManager<OpenSmileAudioService, BaseSourceState>(service) {
    private val audioTopic: DataCache<ObservationKey, OpenSmile2PhoneAudio> = createCache("android_processed_audio", OpenSmile2PhoneAudio())

    @get:Synchronized
    @set:Synchronized
    lateinit var config: AudioConfiguration

    private val processor: OfflineProcessor
    private var isRunning: Boolean = false

    init {
        processor = OfflineProcessor(service) {
            process = listOf(this@OpensmileAudioManager::processAudio)
            requestCode = AUDIO_REQUEST_CODE
            requestName = AUDIO_REQUEST_NAME
            interval(DEFAULT_RECORD_RATE, TimeUnit.SECONDS)
            handler(SafeHandler.getInstance("RADARAudio", Process.THREAD_PRIORITY_BACKGROUND))
            wake = true
        }
        status = SourceStatusListener.Status.READY
    }

    override fun start(acceptableIds: Set<String>) {
        isRunning = true
        processor.start {
            SmileJNI.init(service)
            register()
        }
        // Audio recording
        status = SourceStatusListener.Status.CONNECTED
    }

    private fun processAudio() {
        status = SourceStatusListener.Status.CONNECTED
        logger.info("Setting up audio recording")
        val dataPath = File(service.getExternalFilesDir(""),"audio_" + System.currentTimeMillis() + ".bin")
        //openSMILE.clas.SMILExtractJNI(conf,1,dataPath);
        val localConfig = config
        val smileJNI = SmileJNI(localConfig.configFile, dataPath.absolutePath, localConfig.recordDurationMillis)
        logger.info("Starting audio recording with configuration {} and duration {}, stored to {}",
                localConfig.configFile, localConfig.recordDurationMillis, dataPath)
        val startTime = currentTime
        try {
            smileJNI.run()
            logger.info("Finished recording audio to file {}", dataPath)
        } catch (ex: RuntimeException) {
            logger.error("Failed to record audio", ex)
            return
        }

        try {
            if (dataPath.exists()) {
                send(audioTopic, OpenSmile2PhoneAudio(
                        startTime,
                        currentTime,
                        localConfig.configFile,
                        Base64.encodeToString(readAll(dataPath), Base64.DEFAULT)))
                status = SourceStatusListener.Status.READY
            } else {
                logger.warn("Failed to read audio file")
            }
        } catch (e: IOException) {
            logger.error("Failed to read audio file")
        }
    }

    override fun onClose() {
        if (isRunning) {
            processor.close()
        }
    }

    fun setRecordRate(audioRecordRateMs: Long) {
        processor.interval(audioRecordRateMs, TimeUnit.SECONDS)
    }

    data class AudioConfiguration(val configFile: String, val recordDuration: Long, val unit: TimeUnit) {
        val recordDurationMillis: Long
            get() = unit.toMillis(recordDuration)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(OpensmileAudioManager::class.java)
        private const val AUDIO_REQUEST_CODE = 130102
        private const val AUDIO_REQUEST_NAME = "org.radarcns.audio.AudioDeviceManager"

        @Throws(IOException::class)
        fun readAll(file: File): ByteArray {
            return ByteArrayOutputStream().use { output ->
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(4096)
                    var numRead = input.read(buffer)
                    while (numRead != -1) {
                        output.write(buffer, 0, numRead)
                        numRead = input.read(buffer)
                    }
                }
                output.toByteArray()
            }
        }
    }
}
