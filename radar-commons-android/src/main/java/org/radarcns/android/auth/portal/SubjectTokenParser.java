package org.radarcns.android.auth.portal;

import android.support.annotation.NonNull;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AuthStringParser;

import java.io.IOException;

public class SubjectTokenParser implements AuthStringParser {
    private final AccessTokenParser accessTokenParser;
    private final ManagementPortalClient client;

    public SubjectTokenParser(ManagementPortalClient client, AppAuthState state) {
        this.client = client;
        this.accessTokenParser = new AccessTokenParser(state);
    }

    @Override
    public AppAuthState parse(@NonNull String body) throws IOException {
        AppAuthState newState = this.accessTokenParser.parse(body);
        return client.getSubject(newState, new GetSubjectParser(newState));
    }
}
