package org.radarcns.android.util;

import okhttp3.Response;
import org.radarcns.producer.rest.RestClient;

import java.io.IOException;

public final class ResponseHandler {
    private ResponseHandler() {
        // utility class
    }

    public static <T> T handle(Response response, Parser<String, T> parser) throws IOException {
        try {
            String body = RestClient.responseBody(response);

            if (!response.isSuccessful()) {
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
