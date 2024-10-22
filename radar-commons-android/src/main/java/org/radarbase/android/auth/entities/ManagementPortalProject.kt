package org.radarbase.android.auth.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.radarbase.android.auth.SourceType
import org.radarbase.android.util.equalTo
import java.util.Objects

@Serializable
data class ManagementPortalProject(
    @SerialName("projectName")
    val projectId: String,
    val sourceTypes: List<SourceType>,
) {
    override fun equals(other: Any?): Boolean = equalTo(
        other,
        ManagementPortalProject::projectId,
        ManagementPortalProject::sourceTypes
    )

    override fun hashCode(): Int {
        return Objects.hash(projectId)
    }

    override fun toString(): String {
        return "ManagementPortalProject(" +
                "projectId='$projectId', " +
                "sourceTypes=$sourceTypes" +
                ")"
    }
}