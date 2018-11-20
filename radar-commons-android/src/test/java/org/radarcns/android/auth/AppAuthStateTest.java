package org.radarcns.android.auth;

import android.content.Context;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.radarcns.android.auth.LoginManager.AUTH_TYPE_BEARER;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class AppAuthStateTest {
    private AppAuthState state;
    private ArrayList<SourceMetadata> sources;

    @Before
    public void setUp() {
        sources = new ArrayList<>();
        SourceMetadata source = new SourceMetadata(1, "radar", "test", "1.0", true);
        source.setExpectedSourceName("something");
        sources.add(source);

        state = new AppAuthState.Builder()
                .token("abcd")
                .tokenType(AUTH_TYPE_BEARER)
                .projectId("p")
                .userId("u")
                .attribute(MP_REFRESH_TOKEN_PROPERTY, "efgh")
                .sourceMetadata(sources)
                .header("Authorization", "Bearer abcd")
                .expiration(System.currentTimeMillis() + 10_000L)
                .build();

        testProperties(state);
    }

    @Test
    public void addToPreferences() {
        Context context = RuntimeEnvironment.application.getApplicationContext();
        state.addToPreferences(context);

        AppAuthState readState = AppAuthState.Builder.from(context).build();
        testProperties(readState);
    }

    private void testProperties(AppAuthState state) {
        testProperties(state, "efgh");
    }

    private void testProperties(AppAuthState state, String refreshToken) {
        assertEquals("abcd", state.getToken());
        assertEquals(refreshToken, state.getAttribute(MP_REFRESH_TOKEN_PROPERTY));
        assertEquals("p", state.getProjectId());
        assertEquals("u", state.getUserId());
        assertTrue(state.isValidFor(9, TimeUnit.SECONDS));
        assertFalse(state.isValidFor(11, TimeUnit.SECONDS));
        assertEquals(AUTH_TYPE_BEARER, state.getTokenType());
        assertEquals("Bearer abcd", state.getHeaders().get(0).getValue());
        assertEquals(sources, state.getSourceMetadata());
    }

    @Test
    public void addToBundle() {
        Bundle bundle = new Bundle();
        state.addToBundle(bundle);

        AppAuthState readState = AppAuthState.Builder.from(bundle).build();
        testProperties(readState);
    }

    @Test
    public void newBuilder() {
        AppAuthState builtState = state.newBuilder()
                .attribute(MP_REFRESH_TOKEN_PROPERTY, "else")
                .build();

        testProperties(builtState, "else");
        testProperties(state);
    }
}
