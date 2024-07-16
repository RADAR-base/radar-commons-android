package org.radarbase.passive.phone.audio.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.Toast
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

    private var lastRecordedAudioFile: String? = null

    private var recorderProvider: PhoneAudioInputProvider? = null
    private var timerViewModel: TimerViewModel? = null
    private val state: PhoneAudioInputState?
        get() = recorderProvider?.connection?.sourceState
    private var preferences: SharedPreferences? = null
    private val microphones: MutableList<AudioDeviceInfo> = mutableListOf()

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
                logger.info("Cannot set the microphone state is null")
                Boast.makeText(this@PhoneAudioInputActivity,
                    R.string.unable_to_record_toast, Toast.LENGTH_SHORT).show(true)
                return
            }
            state?.let {
                it.isRecording.observe(this@PhoneAudioInputActivity, isRecordingObserver)
                it.finalizedMicrophone.observe(this@PhoneAudioInputActivity, currentMicrophoneObserver)
            }
            logger.warn("Refresh Input Devices 1")
            refreshInputDevices()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            state?.let {
                it.isRecording.removeObserver(isRecordingObserver)
                it.finalizedMicrophone.removeObserver(currentMicrophoneObserver)
            }
            recorderProvider = null
        }
    }

    private val isRecordingObserver: Observer<Boolean> = Observer { isRecording->
        if (isRecording) {
            logger.info("Switching to Stop Recording mode")
            timerViewModel?.startTimer()
        } else {
            logger.info("Switching to Start Recording mode")
            timerViewModel?.stopTimer()
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
        binding.tvRecordTimer.text = elapsedTime }

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

        timerViewModel = ViewModelProvider(this)[TimerViewModel::class.java]
        timerViewModel?.elapsedTime?.observe(this, recordTimeObserver)
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
//        refreshInputDevices()
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
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
        notRecordingViewUpdate()
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
        binding.btnStopRec.visibility = View.VISIBLE
    }

    private fun notRecordingViewUpdate() {
        binding.btnStartRec.visibility = View.VISIBLE
        binding.btnStopRec.visibility = View.INVISIBLE
    }

    override fun onResume() {
        super.onResume()
        binding.btnStartRec.setOnClickListener {
            workOnStateElseShowToast {
                onRecordingViewUpdate()
                logger.warn("Starting Recording")
                logger.warn("Refresh Input Devices: 3")
                refreshInputDevices()
                audioRecordManager?.startRecording()
            }
        }

        binding.btnStopRec.setOnClickListener {
            workOnStateElseShowToast {
                notRecordingViewUpdate()
                logger.warn("Stopping Recording")
                audioRecordManager?.stopRecording()
                disableRecordingAndEnablePlayback()
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

    private fun disableRecordingAndEnablePlayback() {
        lastRecordedAudioFile = preferences?.getString(LAST_RECORDED_AUDIO_FILE, null)
        startAudioPlaybackFragment()
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

    override fun onStop() {
        super.onStop()
        state?.microphonePrioritized = false
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