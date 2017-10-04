package org.radarcns.android.auth;

import android.util.SparseArray;
import okhttp3.Request;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.config.ServerConfig;
import org.radarcns.producer.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

public class ManagementPortalClient implements Closeable {
    public static final String SOURCES_PROPERTY =
            ManagementPortalClient.class.getName() + ".sources";
    private static final Logger logger = LoggerFactory.getLogger(ManagementPortalClient.class);

    private final RestClient client;

    public ManagementPortalClient(ServerConfig managementPortal) {
        client = new RestClient(managementPortal);
    }

    public AppAuthState getSubject(AppAuthState state) throws IOException {
        Request.Builder builder = client.requestBuilder("api/subjects/" + state.getUserId());
        for (Map.Entry<String, String> header : state.getHeaders()) {
            builder.header(header.getKey(), header.getValue());
        }
        String bodyString = client.requestString(builder.build());

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

    static String parseUserId(JSONObject object) throws JSONException {
        return object.getString("login");
    }

    static SparseArray<AppSource> parseDeviceTypes(JSONObject project) throws JSONException {
        JSONArray deviceTypesArr = project.getJSONArray("deviceTypes");
        int numDevices = deviceTypesArr.length();

        SparseArray<AppSource> devices = new SparseArray<>(numDevices);
        for (int i = 0; i < numDevices; i++) {
            JSONObject deviceTypeObj = deviceTypesArr.getJSONObject(i);
            AppSource device = new AppSource(
                    deviceTypeObj.getString("deviceProducer"),
                    deviceTypeObj.getString("deviceModel"),
                    deviceTypeObj.getString("catalogVersion"),
                    deviceTypeObj.getBoolean("canRegisterDynamically"));
            devices.put(deviceTypeObj.getInt("id"), device);
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

    static String parseProjectId(JSONObject project) throws JSONException {
        return project.getString("projectName");
    }

    @Override
    public void close() {
        client.close();
    }
}
