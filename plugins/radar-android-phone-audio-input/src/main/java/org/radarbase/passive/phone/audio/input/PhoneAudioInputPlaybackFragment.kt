package org.radarbase.passive.phone.audio.input

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.radarbase.android.util.Boast
import org.radarbase.android.util.SafeHandler
import org.radarbase.passive.phone.audio.input.PhoneAudioInputActivity.Companion.AUDIO_FILE_NAME
import org.radarbase.passive.phone.audio.input.databinding.FragmentAudioInputPlaybackBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException


class PhoneAudioInputPlaybackFragment : Fragment() {

    private var binding: FragmentAudioInputPlaybackBinding? = null
    private var mediaPlayer: MediaPlayer? = null

    private val phoneAudioViewModel: PhoneAudioInputViewModel by activityViewModels<PhoneAudioInputViewModel>()
    private var phoneAudioInputState: PhoneAudioInputState? = null
    private val mediaPlaybackHandler: SafeHandler = SafeHandler.getInstance("PhoneAudioInput", Process.THREAD_PRIORITY_AUDIO)

    private var audioFilePath: String? = null

    private val errorListener = { mp: MediaPlayer, what: Int, extra: Int ->
        logger.error("MediaPlayer Error: what=$what, extra=$extra")
        mp.reset()
        true
    }

    private val preparedListener = { mp: MediaPlayer ->
        mp.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val args: Bundle = checkNotNull(arguments) { "Cannot Start Playback without the recorded file location" }
        audioFilePath = args.getString(AUDIO_FILE_NAME)
        mediaPlayer = MediaPlayer()
        mediaPlaybackHandler.start()
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

        observeState()
        audioFilePath ?: run {
            Boast.makeText(requireActivity(), getString(R.string.cannot_start_player), Toast.LENGTH_SHORT).show(true)
            return
        }

        val audio: File = File(audioFilePath!!)

        binding?.apply {

            sendData.setOnClickListener {
                logger.debug("Sending the data triggered from fragment")
                phoneAudioInputState?.audioRecordingManager?.send()
            }

            startPlayback.setOnClickListener {
                if (audio.canRead()) {
                    logger.info("Can Read Audio file: ${audio.canRead()}")
                    mediaPlayer?.apply {
                        if (isPlaying) return@setOnClickListener
                        reset()
                        try {
                            setDataSource(audioFilePath)
                            mediaPlaybackHandler.execute {
                                prepareAsync()
                            }
                            setOnPreparedListener {
                                start()
                                getDuration(audio)
                                logger.info("Audio Playback: Current thread is: ${Thread.currentThread().name}. File duration is: ${mediaPlayer?.duration}")
                            }
                            setOnErrorListener(errorListener)
                        } catch (e: IOException) {
                            logger.error("IOException: ${e.message}")
                        } catch (e: IllegalArgumentException) {
                            logger.error("IllegalArgumentException: ${e.message}")
                        } catch (e: SecurityException) {
                            logger.error("SecurityException: ${e.message}")
                        } catch (e: IllegalStateException) {
                            logger.error("IllegalStateException: ${e.message}")
                        } catch (ex: Exception) {
                            logger.error("Exception while playing audio file: {}", ex.message)
                        }
                    }
                } else {
                    logger.error("Cannot read audio file")
                }
            }
        }
    }

    private fun getDuration(file: File) {
        val mediaMetadataRetriever = MediaMetadataRetriever()
        mediaMetadataRetriever.setDataSource(file.absolutePath)
        val durationStr = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        logger.debug("Duration: ${formateMilliSeccond(durationStr!!.toLong())}")
    }

    fun formateMilliSeccond(milliseconds: Long): String {
        var finalTimerString = ""
        var secondsString = ""

        // Convert total duration into time
        val hours = (milliseconds / (1000 * 60 * 60)).toInt()
        val minutes = (milliseconds % (1000 * 60 * 60)).toInt() / (1000 * 60)
        val seconds = ((milliseconds % (1000 * 60 * 60)) % (1000 * 60) / 1000).toInt()

        // Add hours if there
        if (hours > 0) {
            finalTimerString = "$hours:"
        }

        // Prepending 0 to seconds if it is one digit
        secondsString = if (seconds < 10) {
            "0$seconds"
        } else {
            "" + seconds
        }

        finalTimerString = "$finalTimerString$minutes:$secondsString"

        //      return  String.format("%02d Min, %02d Sec",
        //                TimeUnit.MILLISECONDS.toMinutes(milliseconds),
        //                TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
        //                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)));

        // return timer string
        return finalTimerString
    }


    private fun observeState() {
        phoneAudioViewModel.phoneAudioState.observe(viewLifecycleOwner) { state ->
            phoneAudioInputState = state
        }
    }

    private fun stopAudioPlayer() {
        mediaPlaybackHandler.stop {
            
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null

    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlaybackHandler.stop {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }


    interface OnPhoneAudioFragmentInteractionListener {
        fun onSendAudio()
        fun discardLatestRecording()
    }

    companion object {
        private val logger: Logger =
            LoggerFactory.getLogger(PhoneAudioInputPlaybackFragment::class.java)

        fun newInstance(audioFileName: String): PhoneAudioInputPlaybackFragment =
            PhoneAudioInputPlaybackFragment().apply {
                arguments = Bundle().apply {
                    putString(AUDIO_FILE_NAME, audioFileName)

                }
            }
    }
}