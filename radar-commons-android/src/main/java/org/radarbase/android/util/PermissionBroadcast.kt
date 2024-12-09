package org.radarbase.android.util

data class PermissionBroadcast(
    val extraPermissions: Array<String>,
    val extraGrants: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PermissionBroadcast

        if (!extraPermissions.contentEquals(other.extraPermissions)) return false
        if (!extraGrants.contentEquals(other.extraGrants)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = extraPermissions.contentHashCode()
        result = 31 * result + extraGrants.contentHashCode()
        return result
    }
}