package org.radarbase.android.source

import org.radarbase.android.auth.SourceMetadata

class UnavailableSourceManager(
    override val name: String,
    override val state: BaseSourceState,
) : SourceManager<BaseSourceState> {
    override var isClosed: Boolean = false
        private set

    init {
        state.status = SourceStatusListener.Status.UNAVAILABLE
    }

    override fun start(acceptableIds: Set<String>) = Unit

    override fun close() {
        isClosed = true
    }

    override fun didRegister(source: SourceMetadata) = Unit
}
