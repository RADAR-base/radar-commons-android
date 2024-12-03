package org.radarbase.android.auth.entities.source

import kotlinx.serialization.Serializable

@Serializable
data class SourceRegistrationBody(
    val sourceTypeId: Int,
    val sourceName: String? = null,
    val attributes: Map<String, String>? = null
): PostSourceBody()
