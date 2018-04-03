package org.radarcns.android.util;

import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.rest.RestClient;

import java.io.IOException;

import okhttp3.Response;

public final class ResponseHandler {
    private ResponseHandler() {
        // utility class
    }

    public static <T> T handle(Response response, Parser<String, T> parser) throws IOException {
        try {
            String body = RestClient.responseBody(response);

            if (response.code() == 401) {
                throw new AuthenticationException("QR code is invalid: " + body);
            } else if (!response.isSuccessful()) {
                throw new IOException("Failed to make request; response " + body);
            } else if (body == null || body.isEmpty()) {
                throw new IOException("Response body expected but not found");
            } else {
                return parser.parse(body);
            }
        } finally {
            response.close();
        }
    }
}
