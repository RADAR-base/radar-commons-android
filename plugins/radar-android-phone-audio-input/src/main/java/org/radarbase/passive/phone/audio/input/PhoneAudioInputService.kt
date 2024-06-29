package org.radarbase.passive.phone.audio.input

import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService

class PhoneAudioInputService: SourceService<PhoneAudioInputState>() {

    override val defaultState: PhoneAudioInputState
        get() = PhoneAudioInputState()

    override fun createSourceManager(): PhoneAudioInputManager = PhoneAudioInputManager(this)

    override fun configureSourceManager(
        manager: SourceManager<PhoneAudioInputState>,
        config: SingleRadarConfiguration
    ) {
        manager as PhoneAudioInputManager

    }
}