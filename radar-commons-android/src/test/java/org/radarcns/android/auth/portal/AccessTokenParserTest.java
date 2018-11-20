package org.radarcns.android.auth.portal;

import org.junit.Test;
import org.radarcns.android.auth.AppAuthState;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.radarcns.android.auth.LoginManager.AUTH_TYPE_BEARER;
import static org.radarcns.android.auth.portal.ManagementPortalClient.MP_REFRESH_TOKEN_PROPERTY;

public class AccessTokenParserTest {
    @Test
    public void parse() throws Exception {
        AccessTokenParser parser = new AccessTokenParser(new AppAuthState.Builder().build());

        AppAuthState parsedState = parser.parse(
                "{\"access_token\":\"abcd\","
                        + "\"sub\":\"u\","
                        + "\"refresh_token\":\"efgh\","
                        + "\"expires_in\":10}");

        assertEquals("abcd", parsedState.getToken());
        assertEquals("efgh", parsedState.getAttribute(MP_REFRESH_TOKEN_PROPERTY));
        assertEquals("u", parsedState.getUserId());
        assertTrue(parsedState.isValidFor(9, TimeUnit.SECONDS));
        assertFalse(parsedState.isValidFor(11, TimeUnit.SECONDS));
        assertEquals(AUTH_TYPE_BEARER, parsedState.getTokenType());
        assertEquals("Bearer abcd", parsedState.getHeaders().get(0).getValue());
    }
}
