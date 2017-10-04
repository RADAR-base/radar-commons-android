package org.radarcns.android.auth;

import android.util.SparseArray;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ManagementPortalClientTest {
    private static final String exampleRequest = "{\n"
            + "  \"attributes\": [\n"
            + "    {\n"
            + "      \"key\": \"string\",\n"
            + "      \"value\": \"string\"\n"
            + "    }\n"
            + "  ],\n"
            + "  \"createdBy\": \"string\",\n"
            + "  \"createdDate\": \"2017-10-03T14:07:56.708Z\",\n"
            + "  \"externalId\": \"string\",\n"
            + "  \"externalLink\": \"string\",\n"
            + "  \"id\": 0,\n"
            + "  \"lastModifiedBy\": \"string\",\n"
            + "  \"lastModifiedDate\": \"2017-10-03T14:07:56.708Z\",\n"
            + "  \"login\": \"string\",\n"
            + "  \"project\": {\n"
            + "    \"attributes\": [\n"
            + "      {\n"
            + "        \"key\": \"string\",\n"
            + "        \"value\": \"string\"\n"
            + "      }\n"
            + "    ],\n"
            + "    \"description\": \"string\",\n"
            + "    \"deviceTypes\": [\n"
            + "      {\n"
            + "        \"canRegisterDynamically\": true,\n"
            + "        \"catalogVersion\": \"v\",\n"
            + "        \"deviceModel\": \"m\",\n"
            + "        \"deviceProducer\": \"p\",\n"
            + "        \"id\": 0,\n"
            + "        \"sensorData\": [\n"
            + "          {\n"
            + "            \"dataClass\": \"RAW\",\n"
            + "            \"dataType\": \"RAW\",\n"
            + "            \"enabled\": true,\n"
            + "            \"frequency\": \"string\",\n"
            + "            \"id\": 0,\n"
            + "            \"keySchema\": \"string\",\n"
            + "            \"provider\": \"string\",\n"
            + "            \"sensorName\": \"string\",\n"
            + "            \"topic\": \"string\",\n"
            + "            \"unit\": \"string\",\n"
            + "            \"valueSchema\": \"string\"\n"
            + "          }\n"
            + "        ],\n"
            + "        \"sourceType\": \"ACTIVE\"\n"
            + "      }\n"
            + "    ],\n"
            + "    \"endDate\": \"2017-10-03T14:07:56.708Z\",\n"
            + "    \"id\": 0,\n"
            + "    \"location\": \"string\",\n"
            + "    \"organization\": \"string\",\n"
            + "    \"projectAdmin\": 0,\n"
            + "    \"projectName\": \"string\",\n"
            + "    \"projectStatus\": \"PLANNING\",\n"
            + "    \"startDate\": \"2017-10-03T14:07:56.708Z\"\n"
            + "  },\n"
            + "  \"sources\": [\n"
            + "    {\n"
            + "      \"assigned\": true,\n"
            + "      \"deviceTypeId\": 0,\n"
            + "      \"deviceTypeName\": \"n\",\n"
            + "      \"expectedSourceName\": \"e\",\n"
            + "      \"id\": 0,\n"
            + "      \"sourceId\": \"i\",\n"
            + "      \"sourceName\": \"s\"\n"
            + "    }\n"
            + "  ],\n"
            + "  \"status\": \"DEACTIVATED\"\n"
            + "}";

    @Test
    public void parseSources() throws Exception {
        JSONObject object = new JSONObject(exampleRequest);
        JSONObject project = object.getJSONObject("project");
        JSONArray sources = object.getJSONArray("sources");

        SparseArray<AppSource> deviceTypes = ManagementPortalClient.parseDeviceTypes(project);
        ArrayList<AppSource> sourceList = ManagementPortalClient.parseSources(deviceTypes, sources);

        AppSource expected = new AppSource("p", "m", "v", true);
        expected.setSourceId("i");
        expected.setExpectedSourceName("e");

        assertEquals(Collections.singletonList(expected), sourceList);
    }

    @Test
    public void parseEmptySources() throws Exception {
        JSONObject object = new JSONObject(exampleRequest);
        JSONObject project = object.getJSONObject("project");

        SparseArray<AppSource> deviceTypes = ManagementPortalClient.parseDeviceTypes(project);

        JSONArray sources = new JSONArray();
        ArrayList<AppSource> sourceList = ManagementPortalClient.parseSources(deviceTypes, sources);

        AppSource expected = new AppSource("p", "m", "v", true);

        assertEquals(Collections.singletonList(expected), sourceList);
    }


    @Test
    public void parseNonDynamicSources() throws Exception {
        SparseArray<AppSource> deviceTypes = new SparseArray<>(1);
        deviceTypes.put(0,
                new AppSource("p", "m", "v", false));

        JSONArray sources = new JSONArray();
        ArrayList<AppSource> sourceList = ManagementPortalClient.parseSources(deviceTypes, sources);

        assertEquals(Collections.emptyList(), sourceList);
    }

    @Test
    public void parseProjectId() throws Exception {
        JSONObject object = new JSONObject(exampleRequest);
        JSONObject project = object.getJSONObject("project");
        assertEquals("string", ManagementPortalClient.parseProjectId(project));
    }
}