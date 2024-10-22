package org.radarbase.android.auth.entities

import kotlinx.serialization.Serializable
import org.radarbase.android.util.equalTo
import java.util.Objects

@Serializable
class MetaTokenEntity(
    val refreshToken: String,
    val baseUrl: String,
    val privacyPolicyUrl: String
) : AuthResponseEntity {

    override fun equals(other: Any?): Boolean = equalTo(
        other,
        MetaTokenEntity::refreshToken,
        MetaTokenEntity::baseUrl,
        MetaTokenEntity::privacyPolicyUrl
    )

    override fun hashCode(): Int {
        return Objects.hash(refreshToken, baseUrl, privacyPolicyUrl)
    }

    override fun toString(): String {
        return "MetaTokenEntity{" +
                "refreshToken='$refreshToken', " +
                "baseUrl='$baseUrl', " +
                "privacyPolicyUrl='$privacyPolicyUrl'}"
    }
}