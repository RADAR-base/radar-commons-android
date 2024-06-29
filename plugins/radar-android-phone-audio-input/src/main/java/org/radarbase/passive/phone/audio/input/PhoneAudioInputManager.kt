package org.radarbase.passive.phone.audio.input

import android.media.AudioRecord
import org.radarbase.android.source.AbstractSourceManager

class PhoneAudioInputManager(service: PhoneAudioInputService): AbstractSourceManager<PhoneAudioInputService, PhoneAudioInputState>(service) {

    private var audioRecord: AudioRecord? = null

    init {
        name = service.getString(R.string.phone_audio_input_display_name)
    }

    override fun start(acceptableIds: Set<String>) {

    }


}