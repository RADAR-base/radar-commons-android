package org.radarcns.android.auth

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.radarcns.android.auth.LoginManager.AUTH_TYPE_BEARER
import org.radarcns.android.auth.portal.ManagementPortalClient.Companion.MP_REFRESH_TOKEN_PROPERTY
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppAuthStateTest {
    private lateinit var state: AppAuthState
    private lateinit var sources: ArrayList<SourceMetadata>

    @Before
    fun setUp() {
        sources = ArrayList()
        SourceMetadata(1, "radar", "test", "1.0", true)
                .apply { expectedSourceName = "something" }
                .also { sources.add(it) }

        state = AppAuthState {
            token = "abcd"
            tokenType = AUTH_TYPE_BEARER
            projectId = "p"
            userId = "u"
            attributes[MP_REFRESH_TOKEN_PROPERTY] = "efgh"
            sourceMetadata += sources
            addHeader("Authorization", "Bearer abcd")
            expiration = System.currentTimeMillis() + 10_000L
            isPrivacyPolicyAccepted = true
        }

        testProperties(state)
    }

    @Test
    fun addToPreferences() {
        val readState = RuntimeEnvironment.application.applicationContext.let { context ->
            state.addToPreferences(context)
            AppAuthState.from(context)
        }

        testProperties(readState)
    }

    private fun testProperties(state: AppAuthState, refreshToken: String = "efgh") {
        assertEquals("abcd", state.token)
        assertEquals(refreshToken, state.getAttribute(MP_REFRESH_TOKEN_PROPERTY))
        assertEquals("p", state.projectId)
        assertEquals("u", state.userId)
        assertTrue(state.isValidFor(9, TimeUnit.SECONDS))
        assertFalse(state.isValidFor(11, TimeUnit.SECONDS))
        assertEquals(AUTH_TYPE_BEARER.toLong(), state.tokenType.toLong())
        assertEquals("Bearer abcd", state.headers[0].value)
        assertEquals(sources, state.sourceMetadata)
    }

    @Test
    fun addToBundle() {
        val readState = Bundle().let {
            state.addToBundle(it)
            AppAuthState.from(it)
        }
        testProperties(readState)
    }

    @Test
    fun newBuilder() {
        val builtState = state.alter {
            attributes[MP_REFRESH_TOKEN_PROPERTY] = "else"
        }

        testProperties(builtState, "else")
        testProperties(state)
    }
}
