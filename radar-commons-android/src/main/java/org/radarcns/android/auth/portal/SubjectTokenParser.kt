package org.radarcns.android.auth.portal

import org.radarcns.android.auth.AppAuthState
import org.radarcns.android.auth.AuthStringParser

import java.io.IOException

class SubjectTokenParser(private val client: ManagementPortalClient, state: AppAuthState) : AuthStringParser {
    private val accessTokenParser: AccessTokenParser = AccessTokenParser(state)

    @Throws(IOException::class)
    override fun parse(body: String): AppAuthState {
        val newState = this.accessTokenParser.parse(body)
        return client.getSubject(newState, GetSubjectParser(newState))
    }
}
