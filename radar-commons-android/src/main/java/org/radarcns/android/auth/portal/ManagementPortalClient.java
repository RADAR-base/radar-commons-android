package org.radarcns.android.auth.portal;

import android.support.annotation.NonNull;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AppSource;
import org.radarcns.android.auth.AuthStringParser;
import org.radarcns.android.util.Parser;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Map;

public class ManagementPortalClient implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalClient.class);

    public static final String SOURCES_PROPERTY =
            ManagementPortalClient.class.getName() + ".sources";
    public static final String MP_REFRESH_TOKEN_PROPERTY = ManagementPortalClient.class.getName() + ".refreshToken";
    public static final String PRIVACY_POLICY_URL_PROPERTY = ManagementPortalClient.class.getName() + ".privacyPolicyUrl";
    public static final String BASE_URL_PROPERTY = ManagementPortalClient.class.getName() + ".baseUrl";
    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_JSON_UTF8 = APPLICATION_JSON + "; charset=utf-8";
    private static final MediaType APPLICATION_JSON_MEDIA_TYPE = MediaType
            .parse(APPLICATION_JSON_UTF8);

    private final RestClient client;

    public ManagementPortalClient(ServerConfig managementPortal) {
        logger.info("Creating ManagementPortalClient with {} ", managementPortal.toString());
        client = new RestClient(managementPortal);
    }

    /**
     * Get refresh-token from meta-token url.
     * @param metaTokenUrl current token url
     * @param parser string parser
     * @throws IOException if the management portal could not be reached or it gave an erroneous
     *                     response.
     */
    public AppAuthState getRefreshToken(String metaTokenUrl, AuthStringParser parser)
            throws IOException {
        Request request = client.requestBuilder(metaTokenUrl)
                .header("Accept", APPLICATION_JSON)
                .build();

        logger.info("Requesting refreshToken with token-url {}", metaTokenUrl);

        return handleRequest(request, parser);
    }

    /**
     * Get subject information from the Management portal. This includes project ID, available
     * source types and assigned sources.
     * @param state current authentication state
     * @throws IOException if the management portal could not be reached or it gave an erroneous
     *                     response.
     */
    public AppAuthState getSubject(AppAuthState state, AuthStringParser parser) throws IOException {
        Request request = client.requestBuilder("api/subjects/" + state.getUserId())
                .headers(state.getOkHttpHeaders())
                .header("Accept", APPLICATION_JSON)
                .build();

        logger.info("Requesting subject {} with headers {}", state.getUserId(),
                state.getOkHttpHeaders());

        return handleRequest(request, parser);
    }

    /** Register a source with the Management Portal. */
    public AppSource registerSource(AppAuthState auth, AppSource source)
            throws IOException, JSONException {
        RequestBody body = RequestBody.create(APPLICATION_JSON_MEDIA_TYPE,
                sourceRegistrationBody(source).toString());

        Request request = client.requestBuilder(
                "api/subjects/" + auth.getUserId() + "/sources")
                .post(body)
                .headers(auth.getOkHttpHeaders())
                .header("Content-Type", APPLICATION_JSON_UTF8)
                .header("Accept", APPLICATION_JSON)
                .build();

        try (Response response = client.request(request)) {
            if (response.code() == 409) {
                throw new ConflictException();
            } else if (response.code() == 404) {
                throw new IOException("User " + auth.getUserId() + " is no longer registered with the ManagementPortal.");
            } else if (response.code() == 401) {
                throw new AuthenticationException("Authentication failure with the ManagementPortal.");
            }

            String responseBody = RestClient.responseBody(response);

            if (!response.isSuccessful()) {
                if (responseBody != null) {
                    throw new IOException(
                            "Cannot complete source registration with the ManagementPortal: "
                                    + responseBody);
                } else {
                    throw new IOException("Cannot complete source registration with the ManagementPortal.");
                }
            } else if (responseBody == null || responseBody.isEmpty()) {
                throw new IOException("Source registration with the ManagementPortal did not yield result.");
            } else {
                parseSourceRegistration(responseBody, source);
                return source;
            }
        }
    }

    static JSONObject sourceRegistrationBody(AppSource source) throws JSONException {
        JSONObject requestBody = new JSONObject();

        if (source.getSourceName() != null) {
            String sourceName = source.getSourceName().replaceAll("[^_'.@A-Za-z0-9- ]+", "-");
            requestBody.put("sourceName", sourceName);
            logger.info("Add {} as sourceName" , sourceName);
        }
        requestBody.put("sourceTypeId", source.getSourceTypeId());
        Map<String, String> sourceAttributes = source.getAttributes();
        if (!sourceAttributes.isEmpty()) {
            JSONObject attrs = new JSONObject();
            for (Map.Entry<String, String> attr : sourceAttributes.entrySet()) {
                attrs.put(attr.getKey(), attr.getValue());
            }
            requestBody.put("attributes", attrs);
        }
        return requestBody;
    }

    /**
     * Parse the response of a subject/source registration.
     * @param body registration response body
     * @param source existing source to update with server information.
     * @throws JSONException if the provided body is not valid JSON with the correct properties
     */
    static void parseSourceRegistration(@NonNull String body, @NonNull AppSource source)
            throws JSONException {
        JSONObject responseObject = new JSONObject(body);
        source.setSourceId(responseObject.getString("sourceId"));
        source.setSourceName(responseObject.getString("sourceName"));
        source.setExpectedSourceName(responseObject.getString("expectedSourceName"));
        source.setAttributes(GetSubjectParser.attributesToMap(responseObject.optJSONObject("attributes")));
    }

    @Override
    public void close() {
        client.close();
    }

    public AppAuthState refreshToken(AppAuthState authState, String clientId, String clientSecret,
            AuthStringParser parser) throws IOException {
        try {
            String refreshToken = (String)authState.getProperty(MP_REFRESH_TOKEN_PROPERTY);
            if (refreshToken == null) {
                throw new IllegalArgumentException("No refresh token found");
            }

            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build();

            Request request = client.requestBuilder("oauth/token")
                    .post(body)
                    .addHeader("Authorization", Credentials.basic(clientId, clientSecret))
                    .build();

            return handleRequest(request, parser);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Failed to create request from ManagementPortal url", e);
        }
    }

    private <T> T handleRequest(Request request, Parser<String, T> parser)
            throws IOException {
        try (Response response = client.request(request)) {
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
        }
    }
}
