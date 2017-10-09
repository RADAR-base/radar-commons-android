package org.radarcns.android.auth;

import android.support.annotation.NonNull;
import android.util.SparseArray;
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

public class ManagementPortalClient implements Closeable {
    public static final String SOURCES_PROPERTY =
            ManagementPortalClient.class.getName() + ".sources";
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalClient.class);
    private static final MediaType APPLICATION_JSON = MediaType
            .parse("application/json; charset=utf-8");

    private final RestClient client;

    public ManagementPortalClient(ServerConfig managementPortal) {
        client = new RestClient(managementPortal);
    }

    public AppAuthState getSubject(AppAuthState state) throws IOException {
        Request request = client.requestBuilder("api/subjects/" + state.getUserId())
                .headers(state.getOkHttpHeaders())
                .build();

        String bodyString = client.requestString(request);

        try {
            JSONObject object = new JSONObject(bodyString);
            JSONObject project = object.getJSONObject("project");
            JSONArray sources = object.getJSONArray("sources");

            SparseArray<AppSource> deviceTypes = parseDeviceTypes(project);

            return state.newBuilder()
                    .property(SOURCES_PROPERTY, parseSources(deviceTypes, sources))
                    .userId(parseUserId(object))
                    .projectId(parseProjectId(project))
                    .build();
        } catch (JSONException e) {
            throw new IOException(
                    "ManagementPortal did not give a valid response: " + bodyString, e);
        }
    }

    public AppSource registerSource(AppAuthState auth, AppSource source)
            throws IOException, JSONException {
        RequestBody body = RequestBody.create(APPLICATION_JSON,
                sourceRegistrationBody(source).toString());

        Request request = client.requestBuilder(
                "api/subjects/" + auth.getUserId() + "/sources")
                .post(body)
                .headers(auth.getOkHttpHeaders())
                .header("Content-Type", "application/json; charset=utf-8")
                .build();

        try (Response response = client.request(request)) {
            ResponseBody responseBody = response.body();
            if (response.code() == 409) {
                throw new IOException("Device type is already registered with the ManagementPortal");
            } else if (response.code() == 404) {
                throw new IOException("User " + auth.getUserId() + " is no longer registered with the ManagementPortal.");
            } else if (response.code() == 400) {
                throw new IOException("Bad request to request credentials with the ManagementPortal.");
            } else if (response.code() == 401) {
                throw new AuthenticationException("Authentication failure with the ManagementPortal.");
            } else if (!response.isSuccessful()) {
                if (responseBody != null) {
                    throw new IOException(
                            "Cannot complete device registration with the ManagementPortal: "
                                    + responseBody.string());
                } else {
                    throw new IOException("Cannot complete device registration with the ManagementPortal.");
                }
            } else if (responseBody == null) {
                throw new IOException("Device registration with the ManagementPortal did not yield result.");
            } else {
                parseSourceRegistration(responseBody.string(), source);
                return source;
            }
        }
    }

    static JSONObject sourceRegistrationBody(AppSource source) throws JSONException {
        JSONObject requestBody = new JSONObject();
        if (source.getSourceName() != null) {
            requestBody.put("sourceName", source.getSourceName());
        }
        requestBody.put("deviceTypeId", source.getDeviceTypeId());
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

    static SparseArray<AppSource> parseDeviceTypes(JSONObject project) throws JSONException {
        JSONArray deviceTypesArr = project.getJSONArray("deviceTypes");
        int numDevices = deviceTypesArr.length();

        SparseArray<AppSource> devices = new SparseArray<>(numDevices);
        for (int i = 0; i < numDevices; i++) {
            JSONObject deviceTypeObj = deviceTypesArr.getJSONObject(i);
            int deviceTypeId = deviceTypeObj.getInt("id");

            AppSource device = new AppSource(
                    deviceTypeId,
                    deviceTypeObj.getString("deviceProducer"),
                    deviceTypeObj.getString("deviceModel"),
                    deviceTypeObj.getString("catalogVersion"),
                    deviceTypeObj.getBoolean("canRegisterDynamically"));
            devices.put(deviceTypeId, device);
        }
        return devices;
    }

    static ArrayList<AppSource> parseSources(SparseArray<AppSource> devices, JSONArray sources)
            throws JSONException {

        ArrayList<AppSource> actualSources = new ArrayList<>(devices.size());

        int numSources = sources.length();
        for (int i = 0; i < numSources; i++) {
            JSONObject sourceObj = sources.getJSONObject(i);
            int id = sourceObj.getInt("deviceTypeId");
            AppSource device = devices.get(id);
            if (device == null) {
                logger.error("AppSource type {} not recognized");
                continue;
            }
            devices.remove(id);
            device.setExpectedSourceName(sourceObj.optString("expectedSourceName"));
            device.setSourceId(sourceObj.getString("sourceId"));
            device.setAttributes(attributesToMap(sourceObj.optJSONObject("attributes")));
            actualSources.add(device);
        }

        for (int i = 0; i < devices.size(); i++) {
            AppSource device = devices.valueAt(i);
            if (device.hasDynamicRegistration()) {
                actualSources.add(device);
            }
        }

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
}
