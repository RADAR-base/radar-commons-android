package org.radarbase.passive.phone.audio.input

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.radarbase.passive.phone.audio.input.PhoneAudioInputActivity.Companion.AUDIO_FILE_NAME
import org.radarbase.passive.phone.audio.input.databinding.FragmentAudioInputPlaybackBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class PhoneAudioInputPlaybackFragment : Fragment() {

    private var binding: FragmentAudioInputPlaybackBinding? = null
    private var mediaPlayer: MediaPlayer? = null

    private var audioFileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val args: Bundle = checkNotNull(arguments) { "Cannot Start Playback without the recorded file location" }
        audioFileName = args.getString(AUDIO_FILE_NAME)

        mediaPlayer = MediaPlayer()
        mediaPlayer?.apply {
            setDataSource(audioFileName)
            prepare()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentAudioInputPlaybackBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding?.apply {
            startPlayback.setOnClickListener {
                mediaPlayer?.start()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }


    interface OnPhoneAudioFragmentInteractionListener {
        fun onSendAudio()
        fun discardLatestRecording()
    }

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(PhoneAudioInputPlaybackFragment::class.java)

        fun newInstance(context: Context, audioFileName: String): PhoneAudioInputPlaybackFragment =
            PhoneAudioInputPlaybackFragment().apply {
                arguments = Bundle().apply {
                    putString(AUDIO_FILE_NAME, audioFileName)
                }
            }
    }
}