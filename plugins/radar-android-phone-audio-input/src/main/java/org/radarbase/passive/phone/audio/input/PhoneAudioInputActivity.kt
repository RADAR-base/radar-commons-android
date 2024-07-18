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

import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.Boast
import org.radarbase.passive.phone.audio.input.PhoneAudioInputService.Companion.LAST_RECORDED_AUDIO_FILE
import org.radarbase.passive.phone.audio.input.PhoneAudioInputService.Companion.PHONE_AUDIO_INPUT_SHARED_PREFS
import org.radarbase.passive.phone.audio.input.databinding.ActivityPhoneAudioInputBinding
import org.radarbase.passive.phone.audio.input.utils.AudioDeviceUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PhoneAudioInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneAudioInputBinding
    private lateinit var spinner: Spinner
    private var adapter: ArrayAdapter<String>? = null

    private var isUserInitiatedSection: Boolean = false
    private var isRecording: Boolean = false
    private val lastRecordedAudioFile: String?
        get() = preferences?.getString(LAST_RECORDED_AUDIO_FILE, null)
    private var addStateToVM: (() -> Unit)? = null
    private var postNullState: (() -> Unit)? = null
    private var viewModelInitializer: (() -> Unit)? = null

    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private var recorderProvider: PhoneAudioInputProvider? = null
    private var audioInputViewModel: PhoneAudioInputViewModel? = null
    private val state: PhoneAudioInputState?
        get() = recorderProvider?.connection?.sourceState
    private var preferences: SharedPreferences? = null
    private val microphones: MutableList<AudioDeviceInfo> = mutableListOf()

    private val radarServiceConnection = object : ServiceConnection{
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logger.debug("Service bound to PhoneAudiInputActivity")
            val radarService = service as IRadarBinder
            recorderProvider = null
            for (provider in radarService.connections) {
                if (provider is PhoneAudioInputProvider) {
                    recorderProvider = provider
                }
            }
            if (state == null) {
                logger.info("Cannot set the microphone state is null")
                Boast.makeText(this@PhoneAudioInputActivity,
                    R.string.unable_to_record_toast, Toast.LENGTH_SHORT).show(true)
                return
            }
            state?.let {
                it.isRecording.observe(this@PhoneAudioInputActivity, isRecordingObserver)
                it.finalizedMicrophone.observe(this@PhoneAudioInputActivity, currentMicrophoneObserver)
                addStateToVM = {
                    audioInputViewModel?.phoneAudioState?.postValue(it)
                }
                mainHandler.postDelayed (addStateToVM!!, 500)
            }
            logger.warn("Refresh Input Devices 1")
            refreshInputDevices()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            logger.debug("Service unbound from PhoneAudiInputActivity")
            state?.let {
                it.isRecording.removeObserver(isRecordingObserver)
                it.finalizedMicrophone.removeObserver(currentMicrophoneObserver)
                postNullState = {
                    audioInputViewModel?.phoneAudioState?.postValue(null)
                }
                mainHandler.postDelayed (postNullState!!, 500)
            }
            recorderProvider = null
        }
    }

    private val isRecordingObserver: Observer<Boolean> = Observer { isRecording: Boolean?->
        logger.error("Is Recording: $isRecording")
        isRecording ?: return@Observer
        if (isRecording) {
            this@PhoneAudioInputActivity.isRecording = true
            logger.info("Switching to Stop Recording mode")
            audioInputViewModel?.startTimer()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onRecordingViewUpdate()
        } else {
            this@PhoneAudioInputActivity.isRecording = false
            logger.info("Switching to Start Recording mode")
            audioInputViewModel?.stopTimer()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            notRecordingViewUpdate()
        }
    }

    private val currentMicrophoneObserver: Observer<AudioDeviceInfo> = Observer { microphone: AudioDeviceInfo? ->
        microphone?.productName?.toString()?.let { productName ->
            adapter?.apply {
                val deviceName = getPosition(productName)
                spinner.setSelection(deviceName)
            }
        }
    }

    private val recordTimeObserver: Observer<String> = Observer{elapsedTime ->
        binding.tvRecordTimer.text = elapsedTime
    }

    private fun getAudioDeviceByPosition(position: Int): AudioDeviceInfo? =
        (state?.connectedMicrophones?.value)?.get(position)

    private val setPreferredMicrophone: String.(Int) -> Unit = {  pos: Int ->
        pos.let(::getAudioDeviceByPosition)?.also {
            if (it.productName != this) return@also
        }?.run {
            state?.audioRecordManager?.setPreferredMicrophone(this)
            Boast.makeText(this@PhoneAudioInputActivity, getString(R.string.input_audio_device,
                productName), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPhoneAudioInputBinding.inflate(layoutInflater)
        setContentView(binding.root)
        spinner = binding.spinnerSelectDevice
        preferences = getSharedPreferences(PHONE_AUDIO_INPUT_SHARED_PREFS, Context.MODE_PRIVATE)
        createDropDown()
        disableButtonsInitially()
        logger.error("ON Create Activity")
        viewModelInitializer = {
            audioInputViewModel = ViewModelProvider(this)[PhoneAudioInputViewModel::class.java].apply {
                elapsedTime.observe(this@PhoneAudioInputActivity, recordTimeObserver)
            }
            logger.debug("Making buttons visible now")
            if (isRecording) {
                onRecordingViewUpdate()
            } else {
                notRecordingViewUpdate()
            }
        }
        mainHandler.postDelayed (viewModelInitializer!!, 500)
        manageBackPress()
    }

    private fun manageBackPress() {
        this.onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                logger.debug("PhoneAudioInputActivity: On Back Pressed")
                if (isRecording) {
                    AudioDeviceUtils.showAlertDialog(this@PhoneAudioInputActivity) {
                        setTitle(getString(R.string.cannot_close_activity))
                            .setMessage(getString(R.string.cannot_close_activity_message))
                            .setNeutralButton(getString(R.string.ok)) { dialog: DialogInterface, _ ->
                                dialog.dismiss()
                            }
                    }
                } else {
                    isEnabled = false
                    this@PhoneAudioInputActivity.onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun createDropDown() {
        spinner.setOnTouchListener { v, event ->
            isUserInitiatedSection = true
            if (event.action == MotionEvent.ACTION_UP) {
                v.performClick()
            }
            false
        }
        spinner.onItemSelectedListener = object: OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUserInitiatedSection) {
                    parent?.getItemAtPosition(position).toString().apply { setPreferredMicrophone(position) }
                    isUserInitiatedSection = false
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // No Action
            }
        }
        logger.warn("Creating dropDown: microphones: ${microphones.map { it.productName.toString() }}")
        createAdapter()
    }

    private fun createAdapter() {
        adapter = ArrayAdapter<String>(this, R.layout.dropdown_item, microphones.map { it.productName.toString() } )
        adapter!!.setDropDownViewResource(R.layout.dropdown_item)
        logger.info("Creating adapter with items: ${microphones.map { it.productName.toString() }}")
        spinner.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        logger.error("ON start Activity")
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
        binding.refreshButton.setOnClickListener{
            logger.warn("Refresh Input Devices 2")
            refreshInputDevices()
        }
    }

    private fun workOnStateElseShowToast(work: PhoneAudioInputState.() -> Unit) {
        if (state != null && state?.status == SourceStatusListener.Status.CONNECTED) {
            state?.apply(work) ?: return
        } else {
            Boast.makeText(this, R.string.unable_to_record_toast, Toast.LENGTH_SHORT).show(true)
        }
    }

    private fun onRecordingViewUpdate() {
        binding.btnStartRec.visibility = View.INVISIBLE
        binding.btnStartRec.isEnabled = false
        binding.btnStopRec.visibility = View.VISIBLE
        binding.btnStopRec.isEnabled = true
    }

    private fun notRecordingViewUpdate() {
        binding.btnStartRec.visibility = View.VISIBLE
        binding.btnStartRec.isEnabled = true
        binding.btnStopRec.visibility = View.INVISIBLE
        binding.btnStopRec.isEnabled = false
    }

    private fun disableButtonsInitially() {
        binding.apply {
            btnStartRec.visibility = View.VISIBLE
            btnStartRec.isEnabled = false
            btnStopRec.visibility = View.INVISIBLE
            btnStopRec.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        logger.error("ON resume Activity")
        binding.btnStartRec.setOnClickListener {
            workOnStateElseShowToast {
                logger.warn("Starting Recording")
                logger.warn("Refresh Input Devices: 3")
                refreshInputDevices()
                audioRecordManager?.startRecording()
            }
        }

        binding.btnStopRec.setOnClickListener {
            workOnStateElseShowToast {
                logger.warn("Stopping Recording")
                audioRecordManager?.stopRecording()
                proceedAfterRecording()
            }
        }
    }

    private fun refreshInputDevices() {
        state ?: return
        val connectedMicrophones: List<AudioDeviceInfo> = AudioDeviceUtils.getConnectedMicrophones(this@PhoneAudioInputActivity)
        state?.let { state ->
            state.connectedMicrophones.postValue(connectedMicrophones)
            microphones.clear()
            microphones.addAll(connectedMicrophones)
            logger.info("Modifying dropdown: microphones: ${microphones.map { it.productName.toString() }}")
            if (state.microphonePrioritized && state.finalizedMicrophone.value !in connectedMicrophones) {
                state.microphonePrioritized = false
                logger.info("Activity: state Microphone prioritize?: false")
            }
            runOnUiThread {
                createAdapter()
                if (state.microphonePrioritized) {
                    val microphone = adapter?.getPosition(state.finalizedMicrophone.value?.productName.toString())
                    microphone ?: return@runOnUiThread
                    spinner.setSelection(microphone)
                }
            }
        }
    }

    private fun proceedAfterRecording() {
        AudioDeviceUtils.showAlertDialog (this) {
            setTitle(getString(R.string.proceed_title))
                .setMessage(getString(R.string.proceed_message))
                .setPositiveButton(getString(R.string.send)) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
                logger.debug("Sending the data")
            }.setNeutralButton(getString(R.string.play)) { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
                logger.debug("Playing the audio")
                startAudioPlaybackFragment()
            }.setNegativeButton(getString(R.string.discard)) { dialog: DialogInterface, _: Int ->
                logger.debug("Discarding the last recorded file")
                dialog.cancel()
                clearLastRecordedFile()
                state?.audioRecordManager?.clear()
            }
        }
    }

    private fun clearLastRecordedFile() {
        preferences?.apply {
            edit()
                .putString(LAST_RECORDED_AUDIO_FILE, null)
                .apply()
        }
    }

    private fun startAudioPlaybackFragment() {
        logger.info("Starting audio playback fragment")
        if (lastRecordedAudioFile == null) {
            logger.error("Last recorded audio file lost!! Not Starting playback fragment")
            return
        }
        try {
            val fragment = PhoneAudioInputPlaybackFragment.newInstance(lastRecordedAudioFile!!)
            createPlaybackFragmentLayout(R.id.phone_audio_playback_fragment, fragment)
        } catch (ex: IllegalStateException) {
            logger.error("Failed to start audio playback fragment: is PhoneAudioInputActivity already closed?", ex)
        }
    }

    private fun createPlaybackFragmentLayout(id: Int, fragment: Fragment) {
        setContentView(FrameLayout(this).apply {
            this.id= id
            layoutParams =ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        supportFragmentManager.commit {
            add(id, fragment)
        }
    }

    override fun onStop() {
        super.onStop()
        logger.error("ON stop Activity")
        state?.apply{
            microphonePrioritized = false
            isRecording.postValue(null)
        }
        unbindService(radarServiceConnection)
    }

    private fun removeVMCallbacks() {
        logger.error("Removing callbacks")
        viewModelInitializer?.toRunnable()?.let(mainHandler::removeCallbacks)
        addStateToVM?.toRunnable()?.let(mainHandler::removeCallbacks)
        postNullState?.toRunnable()?.let(mainHandler::removeCallbacks)
    }

    private fun (() -> Unit).toRunnable(): Runnable = Runnable(this)

    override fun onDestroy() {
        logger.error("ON destroy Activity")
        removeVMCallbacks()
        super.onDestroy()
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PhoneAudioInputActivity::class.java)

        const val AUDIO_FILE_NAME = "phone-audio-playback-audio-file-name"
    }
}