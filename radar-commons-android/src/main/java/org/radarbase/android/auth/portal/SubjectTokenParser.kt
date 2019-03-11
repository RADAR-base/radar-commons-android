package org.radarbase.android.auth.portal

import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthStringParser

import java.io.IOException

class SubjectTokenParser(private val client: ManagementPortalClient, state: AppAuthState) : AuthStringParser {
    private val accessTokenParser: AccessTokenParser = AccessTokenParser(state)

    @Throws(IOException::class)
    override fun parse(authString: String): AppAuthState {
        val newState = this.accessTokenParser.parse(authString)
        return client.getSubject(newState, GetSubjectParser(newState))
    }
}
