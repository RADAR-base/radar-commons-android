package org.radarbase.android.source

import kotlinx.coroutines.flow.MutableStateFlow

interface SourceStatusTracker {
    val sourceStatus: MutableStateFlow<SourceStatusTrace>
}

data class SourceStatusTrace(
    val plugin: String? = null,
    val status: SourceStatusListener.Status
)