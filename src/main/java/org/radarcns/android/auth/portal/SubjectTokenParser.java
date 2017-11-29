package org.radarcns.android.auth.portal;

import android.support.annotation.NonNull;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AuthStringParser;
import org.radarcns.android.util.SynchronousCallback;

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
        SynchronousCallback<AppAuthState> callback = new SynchronousCallback<>(new GetSubjectParser(newState));
        client.getSubject(newState, new SynchronousCallback<>(new GetSubjectParser(newState)));
        try {
            return callback.get();
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        }
    }
}
