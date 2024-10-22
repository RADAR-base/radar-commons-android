package org.radarbase.android.auth.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.util.equalTo
import java.util.Objects

@Serializable
data class GetSubjectEntity(
    @SerialName("project")
    val mpProject: ManagementPortalProject,
    @SerialName("sources")
    val mpSources: List<SourceMetadata>,
    @SerialName("login")
    val userId: String,
    @SerialName("attributes")
    val attributes: Map<String, String>,
    @SerialName("externalId")
    val radarExternalId: String?,
    @SerialName("externalLink")
    val radarExternalUrl: String?
) {
    override fun equals(other: Any?): Boolean = equalTo(
        other,
        GetSubjectEntity::userId,
        GetSubjectEntity::mpProject,
        GetSubjectEntity::mpSources,
        GetSubjectEntity::userId,
        GetSubjectEntity::attributes,
        GetSubjectEntity::radarExternalId,
        GetSubjectEntity::radarExternalUrl
    )

    override fun hashCode(): Int {
        return Objects.hash(userId, mpProject)
    }

    override fun toString(): String {
        return "GetSubjectEntity(" +
                "mpProject=$mpProject, " +
                "mpSources=$mpSources, " +
                "userId='$userId', " +
                "attributes=$attributes, " +
                "radarExternalId=$radarExternalId, " +
                "radarExternalUrl=$radarExternalUrl" +
                ")"
    }
}