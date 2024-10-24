package org.radarbase.android.auth.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.radarbase.android.util.equalTo
import java.util.Objects

@Serializable
class AccessTokenEntity(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
    val sub: String,
    val sources: List<String>
) : AuthResponseEntity {
    override fun equals(other: Any?): Boolean = equalTo(
        other,
        AccessTokenEntity::accessToken,
        AccessTokenEntity::tokenType,
        AccessTokenEntity::refreshToken,
        AccessTokenEntity::expiresIn,
        AccessTokenEntity::sub,
        AccessTokenEntity::sources
    )

    override fun hashCode(): Int {
        return Objects.hash(sub, accessToken)
    }

    override fun toString(): String {
        return "AccessTokenEntity{" +
                "accessToken='$accessToken', " +
                "tokenType='$tokenType', " +
                "refreshToken='$refreshToken', " +
                "expiresIn=$expiresIn, " +
                "sub='$sub', " +
                "sources=$sources}"
    }
}