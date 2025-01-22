/*
 * Copyright 2017 The Hyve
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

package org.radarbase.passive.phone.audio.input.ui

import android.os.Process
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.radarbase.android.util.SafeHandler
import org.radarbase.passive.phone.audio.input.PhoneAudioInputState
import org.radarbase.passive.phone.audio.input.utils.AudioDeviceUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PhoneAudioInputViewModel: ViewModel() {

    private val _elapsedTime: MutableLiveData<String> = MutableLiveData()
    val elapsedTime: LiveData<String>
        get() = _elapsedTime
    val phoneAudioState: MutableLiveData<PhoneAudioInputState> = MutableLiveData()
    private var timeElapsedMillis: Long = 0L

    private var startTime: Long? = null
    private var isRecording: Boolean = false

    private val timerHandler: SafeHandler = SafeHandler.getInstance("TimerThread", Process.THREAD_PRIORITY_BACKGROUND)
    private var futureHandlerRef: SafeHandler.HandlerFuture? = null

    private val timerRunnable: () -> Boolean = {
        val currentTime = System.currentTimeMillis()
        timeElapsedMillis = currentTime - startTime!!
        _elapsedTime.postValue(AudioDeviceUtils.formatMsToReadableTime(timeElapsedMillis))
        isRecording
    }

    init {
        timerHandler.start()
    }

    fun startTimer() {
        logger.info("Starting timer")
        startTime = System.currentTimeMillis()
        isRecording = true
        futureHandlerRef = timerHandler.repeatWhile(20, timerRunnable)
        logger.debug("Created timer future handler ref: {}", System.identityHashCode(timerRunnable))
    }

    fun pauseTimer() {
        logger.debug("Pause timer")
        val elapsedTime = timeElapsedMillis
        futureHandlerRef?.let {
            it.cancel()
            logger.debug("Cancelled timer future handler ref, {}", System.identityHashCode(timerRunnable))
            futureHandlerRef = null
        }
        isRecording = false
        timeElapsedMillis = elapsedTime
        _elapsedTime.postValue(AudioDeviceUtils.formatMsToReadableTime(timeElapsedMillis))
    }

    fun resumeTimer() {
        logger.debug("Resumed Timer")
        startTime = System.currentTimeMillis() - timeElapsedMillis
        isRecording = true
        futureHandlerRef = timerHandler.repeatWhile(20, timerRunnable)
    }

    fun stopTimer() {
        timerHandler.execute {
            isRecording = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerHandler.stop {
            futureHandlerRef?.let {
                it.cancel()
                logger.debug("Cancelled timer future handler ref 2, {}", System.identityHashCode(timerRunnable))
            }
        }
        logger.trace("PhoneAudioInputViewmodel: ON Cleared")
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PhoneAudioInputViewModel::class.java)
    }
}
