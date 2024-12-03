package org.radarbase.android.auth.entities.source

import kotlinx.serialization.Serializable

@Serializable
data class SourceUpdateBody(
    val attributes: Map<String, String>
): PostSourceBody()
