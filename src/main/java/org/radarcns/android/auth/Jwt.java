package org.radarcns.android.auth;

import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

import static android.util.Base64.NO_PADDING;
import static android.util.Base64.NO_WRAP;

public class Jwt {
    private final JSONObject header;
    private final JSONObject body;
    private final String originalString;

    public Jwt(String originalString, JSONObject header, JSONObject body) {
        this.originalString = originalString;
        this.header = header;
        this.body = body;
    }

    public JSONObject getHeader() {
        return header;
    }

    public JSONObject getBody() {
        return body;
    }

    public static Jwt parse(String token) throws JSONException {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Argument is not a valid JSON web token. Need 3 parts but got " + parts.length);
        }
        String headerString = new String(Base64.decode(parts[0], NO_PADDING | NO_WRAP));
        JSONObject header = new JSONObject(headerString);

        String bodyString = new String(Base64.decode(parts[1], NO_PADDING | NO_WRAP));
        JSONObject body = new JSONObject(bodyString);

        return new Jwt(token, header, body);
    }

    public String getOriginalString() {
        return originalString;
    }
}
