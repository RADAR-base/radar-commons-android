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
