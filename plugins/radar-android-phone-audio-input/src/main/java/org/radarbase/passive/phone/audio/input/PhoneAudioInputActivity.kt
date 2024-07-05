package org.radarbase.passive.phone.audio.input

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import org.radarbase.android.IRadarBinder
import org.radarbase.passive.phone.audio.input.databinding.ActivityPhoneAudioInputBinding

class PhoneAudioInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneAudioInputBinding
    private var recorderProvider: PhoneAudioInputProvider? = null

    private val state: PhoneAudioInputState?
        get() = recorderProvider?.connection?.sourceState

    private val radarServiceConnection = object : ServiceConnection{
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val radarService = service as IRadarBinder
            recorderProvider = null
            for (provider in radarService.connections) {
                if (provider is PhoneAudioInputProvider) {
                    recorderProvider = provider
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorderProvider = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPhoneAudioInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

    }
}