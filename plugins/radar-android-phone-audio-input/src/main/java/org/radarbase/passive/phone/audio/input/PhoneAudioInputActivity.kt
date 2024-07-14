package org.radarbase.passive.phone.audio.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore.Audio
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Observer
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

    private var lastRecordedAudioFile: String? = null

    private var recorderProvider: PhoneAudioInputProvider? = null
    private val state: PhoneAudioInputState?
        get() = recorderProvider?.connection?.sourceState
    private var preferences: SharedPreferences? = null
    private val microphones: MutableList<AudioDeviceInfo> = mutableListOf()

    private val fileNameObserver: Observer<String> = Observer {
//        binding.tvCurrentAudioFile.text = it
    }

    private val isRecordingObserver: Observer<Boolean> = Observer { isRecording->
        if (isRecording) {
            logger.info("Switching to Stop Recording mode")
            binding.btnStartStopRec.text = getString(R.string.stop_recording)
//            val currentFileName = state?.currentRecordingFileName ?: return@Observer
//            binding.tvCurrentFileHead.visibility = View.VISIBLE
//            binding.tvCurrentAudioFile.visibility = View.VISIBLE
//            currentFileName.observe(this@PhoneAudioInputActivity, fileNameObserver)
//            binding.tvCurrentAudioFile.text = currentFile
//            binding.btnStartStopRec.setBackgroundColor(getColor(R.color.color_btn_stop_record))
        } else {
            logger.info("Switching to Start Recording mode")
            binding.btnStartStopRec.text = getString(R.string.start_recording)
//            state?.currentRecordingFileName?.removeObserver(fileNameObserver)
//            binding.tvCurrentFileHead.visibility = View.INVISIBLE
//            binding.tvCurrentAudioFile.visibility = View.INVISIBLE
//            binding.btnStartStopRec.setBackgroundColor(getColor(R.color.color_btn_start_record))
        }
    }

    private val currentMicrophoneObserver: Observer<AudioDeviceInfo> = Observer { microphone: AudioDeviceInfo? ->
        microphone?.let {
//            binding.textView2.visibility = View.VISIBLE
//            binding.textView2.text = microphone.productName
        } ?: run {
//            binding.textView2.visibility = View.INVISIBLE
        }
    }

    private val radarServiceConnection = object : ServiceConnection{
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val radarService = service as IRadarBinder
            recorderProvider = null
            for (provider in radarService.connections) {
                if (provider is PhoneAudioInputProvider) {
                    recorderProvider = provider
                }
            }
            if (state == null) {
                Boast.makeText(this@PhoneAudioInputActivity, R.string.unable_to_record_toast, Toast.LENGTH_SHORT).show(true)
                return
            }
            state?.isRecording?.observe(this@PhoneAudioInputActivity, isRecordingObserver)
            logger.warn("Refresh Input Devices 1")
            refreshInputDevices()
            if (state == null) {
                logger.info("Cannot set the microphone state is null")
            }

            state?.finalizedMicrophone?.observe(this@PhoneAudioInputActivity, currentMicrophoneObserver)

        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorderProvider = null
            state?.isRecording?.removeObserver(isRecordingObserver)
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
    }

    private fun createDropDown() {
        spinner.onItemSelectedListener = object: OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val selectedMicrophone = parent?.getItemAtPosition(position).toString()
                val microphoneExists = preferMicrophone(selectedMicrophone, position)
                microphoneExists?.let {
                    state?.audioRecordManager?.setPreferredMicrophone(it)
                }
                Toast.makeText(this@PhoneAudioInputActivity, parent?.getItemAtPosition(position).toString(), Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // No Action
            }
        }
        logger.warn("Creating dropDown: microphones: ${microphones.map { it.productName.toString() }}")
        adapter = ArrayAdapter<String>(this, R.layout.dropdown_item, microphones.map { it.productName.toString() } )
        adapter!!.setDropDownViewResource(R.layout.dropdown_item)
        spinner.adapter = adapter
        binding.refreshButton.setOnClickListener{
            logger.warn("Refresh Input Devices 2")
            refreshInputDevices()
        }
    }

    private fun preferMicrophone(microphoneName: String, position: Int): AudioDeviceInfo? {
        val currentMicrophones = state?.connectedMicrophones?.value
        ((currentMicrophones as ArrayList)[position].productName == microphoneName).also {  doesExists ->
            return if (doesExists)currentMicrophones[position] else null
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
//        binding.button.setOnClickListener {
//            state?.audioRecordManager?.clear()
//        }
    }

    override fun onResume() {
        super.onResume()
        binding.btnStartStopRec.setOnClickListener {
            if (state != null && state?.status == SourceStatusListener.Status.CONNECTED) {
                if (binding.btnStartStopRec.text == getString(R.string.start_recording)) {
                    logger.warn("Starting Recording")
                    val pluginState = state ?: return@setOnClickListener
                    logger.warn("Refresh Input Devices: 3")
                    refreshInputDevices()
                    pluginState.audioRecordManager?.startRecording()
                } else if (binding.btnStartStopRec.text == getString(R.string.stop_recording)) {
                    logger.warn("Stopping Recording")
                    state?.audioRecordManager?.stopRecording()
                    disableRecordingAndEnablePlayback()
                }
            } else {
                Boast.makeText(this, R.string.unable_to_record_toast, Toast.LENGTH_SHORT).show(true)
            }
        }

    }

    private fun refreshInputDevices() {
        val connectedMicrophones: List<AudioDeviceInfo> = AudioDeviceUtils.getConnectedMicrophones(this@PhoneAudioInputActivity)
        state?.connectedMicrophones?.postValue(connectedMicrophones)
        microphones.clear()
        microphones.addAll(connectedMicrophones)
        adapter = ArrayAdapter<String>(this, R.layout.dropdown_item, microphones.map { it.productName.toString() } )
        adapter!!.setDropDownViewResource(R.layout.dropdown_item)
        spinner.adapter = adapter
        logger.info("Modifying dropdown: microphones: ${microphones.map { it.productName.toString() }}")
        if (state?.microphonePrioritized!! && state?.finalizedMicrophone?.value !in connectedMicrophones) {
            state?.microphonePrioritized = false
            logger.info("Activity: statMicrophone prioritize?: false")
        }
        if (state!!.microphonePrioritized) {
            val microphone = adapter?.getPosition(state!!.finalizedMicrophone.value?.productName.toString())
            microphone ?: return
            spinner.setSelection(microphone)
        }
    }

    private fun disableRecordingAndEnablePlayback() {
        lastRecordedAudioFile = preferences?.getString(LAST_RECORDED_AUDIO_FILE, null)
//        startAudioPlaybackFragment()
    }

    private fun startAudioPlaybackFragment() {
        logger.info("Starting audio playback fragment")
        if (lastRecordedAudioFile == null) {
            logger.error("Last recorded audio file lost!! Not Starting playback fragment")
            return
        }
        try {
            val fragment = PhoneAudioInputPlaybackFragment.newInstance(this, lastRecordedAudioFile!!)
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

    override fun onPause() {
        state?.finalizedMicrophone?.removeObserver(currentMicrophoneObserver)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        unbindService(radarServiceConnection)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(PhoneAudioInputActivity::class.java)

        const val AUDIO_FILE_NAME = "phone-audio-playback-audio-file-name"
    }
}