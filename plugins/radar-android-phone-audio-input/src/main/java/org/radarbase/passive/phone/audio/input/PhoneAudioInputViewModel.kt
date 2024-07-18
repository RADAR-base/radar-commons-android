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

package org.radarbase.passive.phone.audio.input

import android.os.Process
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.radarbase.android.util.SafeHandler
import org.radarbase.passive.phone.audio.input.utils.AudioDeviceUtils


class PhoneAudioInputViewModel: ViewModel() {

    private val _elapsedTime: MutableLiveData<String> = MutableLiveData()
    val elapsedTime: LiveData<String>
        get() = _elapsedTime
    val phoneAudioState: MutableLiveData<PhoneAudioInputState> = MutableLiveData()

    private var startTime: Long? = null
    private var isRecording: Boolean = false

    private val timerHandler: SafeHandler = SafeHandler.getInstance("TimerThread", Process.THREAD_PRIORITY_BACKGROUND)

    private val timerRunnable: () -> Boolean = {
        val currentTime = System.currentTimeMillis()
        val timeElapsed: Long = currentTime - startTime!!
        _elapsedTime.postValue(AudioDeviceUtils.formatMsToReadableTime(timeElapsed))
        isRecording
    }

    init {
        timerHandler.start()
    }

    fun startTimer() {
        startTime = System.currentTimeMillis()
        isRecording = true
        timerHandler.repeatWhile(10, timerRunnable)
    }

    fun stopTimer() {
        timerHandler.execute {
            isRecording = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerHandler.stop()
    }


}
