package org.radarbase.passive.phone.audio.input

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.Boast
import org.radarbase.passive.phone.audio.input.databinding.ActivityPhoneAudioInputBinding

class PhoneAudioInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneAudioInputBinding
    private var recorderProvider: PhoneAudioInputProvider? = null
    private val state: PhoneAudioInputState?
        get() = recorderProvider?.connection?.sourceState

    private val isRecordingObserver: Observer<Boolean> = Observer { isRecording->
        if (isRecording) {
            binding.btnStartStopRec.text = getString(R.string.stop_recording)
            binding.btnStartStopRec.setBackgroundColor(getColor(R.color.color_btn_stop_record))
        } else {
            binding.btnStartStopRec.text = getString(R.string.start_recording)
            binding.btnStartStopRec.setBackgroundColor(getColor(R.color.color_btn_start_record))
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
                Boast.makeText(this@PhoneAudioInputActivity, R.string.unable_to_record_toast, Toast.LENGTH_SHORT)
                return
            }
            state?.isRecording?.observe(this@PhoneAudioInputActivity, isRecordingObserver)
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

    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
    }

    override fun onResume() {
        super.onResume()
        binding.btnStartStopRec.setOnClickListener {
            if (state != null && state?.status == SourceStatusListener.Status.CONNECTED) {
                if (binding.btnStartStopRec.text == getString(R.string.start_recording)) {
                    val pluginState = state ?: return@setOnClickListener
                    pluginState.audioRecordManager?.startRecording()
                } else if (binding.btnStartStopRec.text == getString(R.string.stop_recording)) {
                    state?.audioRecordManager?.stopRecording()
                    disableRecordingAndEnablePlayback()
                }
            } else {
                Boast.makeText(this, R.string.unable_to_record_toast, Toast.LENGTH_SHORT)
            }
        }
    }

    private fun disableRecordingAndEnablePlayback() {

    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        unbindService(radarServiceConnection)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}