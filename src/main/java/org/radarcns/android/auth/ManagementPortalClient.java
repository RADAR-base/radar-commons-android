package org.radarcns.android.auth;

import android.support.annotation.NonNull;
import android.util.SparseArray;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.AuthenticationException;
import org.radarcns.producer.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.radarcns.android.auth.LoginManager.AUTH_TYPE_BEARER;
import static org.radarcns.android.auth.ManagementPortalLoginManager.MP_REFRESH_TOKEN;

public class ManagementPortalClient implements Closeable {
    public static final String SOURCES_PROPERTY =
            ManagementPortalClient.class.getName() + ".sources";
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalClient.class);
    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_JSON_UTF8 = APPLICATION_JSON + "; charset=utf-8";
    private static final MediaType APPLICATION_JSON_MEDIA_TYPE = MediaType
            .parse(APPLICATION_JSON_UTF8);

    private final RestClient client;

    public ManagementPortalClient(ServerConfig managementPortal) {
        client = new RestClient(managementPortal);
    }

    /**
     * Get subject information from the Management portal. This includes project ID, available
     * source types and assigned sources.
     * @param state current authentication state
     * @return updated authentication state including sources and a project ID
     * @throws IOException if the management portal could not be reached or it gave an erroneous
     *                     response.
     */
    public AppAuthState getSubject(AppAuthState state) throws IOException {
        Request request = client.requestBuilder("api/subjects/" + state.getUserId())
                .headers(state.getOkHttpHeaders())
                .header("Accept", APPLICATION_JSON)
                .build();

        String bodyString = client.requestString(request);

        try {
            JSONObject object = new JSONObject(bodyString);
            JSONObject project = object.getJSONObject("project");
            JSONArray sources = object.getJSONArray("sources");

            SparseArray<AppSource> sourceTypes = parseSourceTypes(project);

            return state.newBuilder()
                    .property(SOURCES_PROPERTY, parseSources(sourceTypes, sources))
                    .userId(parseUserId(object))
                    .projectId(parseProjectId(project))
                    .build();
        } catch (JSONException e) {
            throw new IOException(
                    "ManagementPortal did not give a valid response: " + bodyString, e);
        }
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
            String responseBody = RestClient.responseBody(response);
            if (response.code() == 409) {
                throw new IOException("Source type is already registered with the ManagementPortal");
            } else if (response.code() == 404) {
                throw new IOException("User " + auth.getUserId() + " is no longer registered with the ManagementPortal.");
            } else if (response.code() == 400) {
                throw new IOException("Bad request to request credentials with the ManagementPortal.");
            } else if (response.code() == 401) {
                throw new AuthenticationException("Authentication failure with the ManagementPortal.");
            } else if (!response.isSuccessful()) {
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
        // TODO: in a regression from MP 0.2.0 -> 0.2.1 this was removed
//        if (source.getSourceName() != null) {
//            requestBody.put("sourceName", source.getSourceName());
//        }
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
        source.setAttributes(attributesToMap(responseObject.optJSONObject("attributes")));
    }

    /**
     * Parse the user ID from a subject response object.
     * @param object response JSON object of a subject call
     * @return user ID
     * @throws JSONException if the given JSON object does not contain a login property
     */
    static String parseUserId(JSONObject object) throws JSONException {
        return object.getString("login");
    }

    static SparseArray<AppSource> parseSourceTypes(JSONObject project) throws JSONException {
        JSONArray sourceTypesArr = project.getJSONArray("sourceTypes");
        int numSources = sourceTypesArr.length();

        SparseArray<AppSource> sources = new SparseArray<>(numSources);
        for (int i = 0; i < numSources; i++) {
            JSONObject sourceTypeObj = sourceTypesArr.getJSONObject(i);
            int sourceTypeId = sourceTypeObj.getInt("id");

            sources.put(sourceTypeId, new AppSource(
                    sourceTypeId,
                    sourceTypeObj.getString("producer"),
                    sourceTypeObj.getString("model"),
                    sourceTypeObj.getString("catalogVersion"),
                    sourceTypeObj.getBoolean("canRegisterDynamically")));
        }
        return sources;
    }

    static ArrayList<AppSource> parseSources(SparseArray<AppSource> sourceTypes, JSONArray sources)
            throws JSONException {

        ArrayList<AppSource> actualSources = new ArrayList<>(sourceTypes.size());

        int numSources = sources.length();
        for (int i = 0; i < numSources; i++) {
            JSONObject sourceObj = sources.getJSONObject(i);
            String sourceId = sourceObj.getString("sourceId");
            if (!sourceObj.optBoolean("assigned", true)) {
                logger.info("Skipping unassigned source {}", sourceId);
            }
            int sourceTypeId = sourceObj.getInt("sourceTypeId");
            AppSource source = sourceTypes.get(sourceTypeId);
            if (source == null) {
                logger.error("Source {} type {} not recognized", sourceId, sourceTypeId);
                continue;
            }
            sourceTypes.remove(sourceTypeId);
            source.setExpectedSourceName(sourceObj.optString("expectedSourceName"));
            source.setSourceName(sourceObj.optString("sourceName"));
            source.setSourceId(sourceId);
            source.setAttributes(attributesToMap(sourceObj.optJSONObject("attributes")));
            actualSources.add(source);

        }

        for (int i = 0; i < sourceTypes.size(); i++) {
            AppSource source = sourceTypes.valueAt(i);
            if (source.hasDynamicRegistration()) {
                actualSources.add(source);
            }
        }

        logger.info("Sources from Management Portal: {}", actualSources);
        return actualSources;
    }

    private static Map<String, String> attributesToMap(JSONObject attrObj) throws JSONException {
        if (attrObj == null) {
            return null;
        }
        Map<String, String> attrs = new HashMap<>();
        for (Iterator<String> it = attrObj.keys(); it.hasNext(); ) {
            String key = it.next();
            attrs.put(key, attrObj.getString(key));
        }
        return attrs;
    }

    static String parseProjectId(JSONObject project) throws JSONException {
        return project.getString("projectName");
    }

    @Override
    public void close() {
        client.close();
    }

    public void refreshToken(AppAuthState authState, String clientId, String clientSecret, Callback callback) throws IOException, JSONException {
        try {
            String refreshToken = (String)authState.getProperty(MP_REFRESH_TOKEN);
            if (refreshToken == null) {
                throw new IllegalArgumentException("No refresh token found");
            }

            RequestBody body = new FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", authState.getProperty(MP_REFRESH_TOKEN).toString())
                    .build();

            Request request = client.requestBuilder("oauth/token")
                    .post(body)
                    .addHeader("Authorization", Credentials.basic(clientId, clientSecret))
                    .build();

            client.request(request, callback);
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Failed to create request from ManagementPortal url", e);
        }
    }

    public static AppAuthState parseAccessToken(AppAuthState state, Response response) throws IOException {
        String responseBody = RestClient.responseBody(response);
        if (!response.isSuccessful()) {
            throw new IOException("ManagementPortal returned error code " + response.code() + " with body " + responseBody);
        }
        if (responseBody == null) {
            throw new IOException("ManagementPortal did not return response");
        }

        String refreshToken = (String) state.getProperty(MP_REFRESH_TOKEN);
        try {
            JSONObject json = new JSONObject(responseBody);
            String accessToken = json.getString("access_token");
            refreshToken = json.optString("refresh_token", refreshToken);
            return state.newBuilder()
                    .token(accessToken)
                    .tokenType(AUTH_TYPE_BEARER)
                    .userId(json.getString("sub"))
                    .property(MP_REFRESH_TOKEN, refreshToken)
                    .setHeader("Authorization", "Bearer " + accessToken)
                    .expiration(TimeUnit.SECONDS.toMillis(json.getLong("expires_in")
                            + System.currentTimeMillis()))
                    .build();
        } catch (JSONException ex) {
            throw new IOException("Failed to parse json string " + responseBody, ex);
        }
    }
}
