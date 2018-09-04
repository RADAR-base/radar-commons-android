package org.radarcns.android.auth.portal;

import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AppSource;
import org.radarcns.config.ServerConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import static org.junit.Assert.assertEquals;
import static org.radarcns.android.auth.portal.ManagementPortalClient.SOURCES_PROPERTY;

public class ManagementPortalClientTest {
    private static final String EXAMPLE_REQUEST = "{\n"
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
            + "  \"login\": \"sub-1\",\n"
            + "  \"project\": {\n"
            + "    \"attributes\": [\n"
            + "      {\n"
            + "        \"key\": \"string\",\n"
            + "        \"value\": \"string\"\n"
            + "      }\n"
            + "    ],\n"
            + "    \"description\": \"string\",\n"
            + "    \"sourceTypes\": [\n"
            + "      {\n"
            + "        \"canRegisterDynamically\": true,\n"
            + "        \"catalogVersion\": \"v\",\n"
            + "        \"model\": \"m\",\n"
            + "        \"producer\": \"p\",\n"
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
            + "    \"projectName\": \"proj-name\",\n"
            + "    \"projectStatus\": \"PLANNING\",\n"
            + "    \"startDate\": \"2017-10-03T14:07:56.708Z\"\n"
            + "  },\n"
            + "  \"sources\": [\n"
            + "    {\n"
            + "      \"assigned\": true,\n"
            + "      \"sourceTypeId\": 0,\n"
            + "      \"sourceTypeProducer\": \"dp\",\n"
            + "      \"sourceTypeModel\": \"dm\",\n"
            + "      \"sourceTypeCatalogVersion\": \"dv\",\n"
            + "      \"expectedSourceName\": \"e\",\n"
            + "      \"id\": 0,\n"
            + "      \"sourceId\": \"i\",\n"
            + "      \"sourceName\": \"s\",\n"
            + "      \"attributes\": {\"k\": \"v\"}\n"
            + "    }\n"
            + "  ],\n"
            + "  \"status\": \"DEACTIVATED\"\n"
            + "}";

    @Test
    public void parseSources() throws Exception {
        JSONObject object = new JSONObject(EXAMPLE_REQUEST);
        JSONObject project = object.getJSONObject("project");
        JSONArray sources = object.getJSONArray("sources");

        SparseArray<AppSource> deviceTypes = GetSubjectParser.parseSourceTypes(project);
        ArrayList<AppSource> sourceList = GetSubjectParser.parseSources(deviceTypes, sources);

        AppSource expected = new AppSource(0, "p", "m", "v", true);
        expected.setSourceId("i");
        expected.setExpectedSourceName("e");
        expected.setSourceName("s");
        expected.setAttributes(Collections.singletonMap("k", "v"));

        assertEquals(Collections.singletonList(expected), sourceList);
    }

    @Test
    public void parseEmptySources() throws Exception {
        JSONObject object = new JSONObject(EXAMPLE_REQUEST);
        JSONObject project = object.getJSONObject("project");

        SparseArray<AppSource> deviceTypes = GetSubjectParser.parseSourceTypes(project);

        JSONArray sources = new JSONArray();
        ArrayList<AppSource> sourceList = GetSubjectParser.parseSources(deviceTypes, sources);

        AppSource expected = new AppSource(0, "p", "m", "v", true);

        assertEquals(Collections.singletonList(expected), sourceList);
    }


    @Test
    public void parseNonDynamicSources() throws Exception {
        SparseArray<AppSource> deviceTypes = new SparseArray<>(1);
        deviceTypes.put(0,
                new AppSource(0, "p", "m", "v", false));

        JSONArray sources = new JSONArray();
        ArrayList<AppSource> sourceList = GetSubjectParser.parseSources(deviceTypes, sources);

        assertEquals(Collections.emptyList(), sourceList);
    }

    @Test
    public void parseProjectId() throws Exception {
        JSONObject object = new JSONObject(EXAMPLE_REQUEST);
        JSONObject project = object.getJSONObject("project");
        assertEquals("proj-name", GetSubjectParser.parseProjectId(project));
    }

    @Test
    public void parseUserId() throws Exception {
        JSONObject object = new JSONObject(EXAMPLE_REQUEST);
        assertEquals("sub-1", GetSubjectParser.parseUserId(object));
    }

    @Test
    public void requestSubject() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {

            // Schedule some responses.
            server.enqueue(new MockResponse()
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(EXAMPLE_REQUEST));

            // Start the server.
            server.start();

            ServerConfig serverConfig = new ServerConfig(server.url("/").url());

            ManagementPortalClient client = new ManagementPortalClient(serverConfig);
            AppAuthState authState = new AppAuthState.Builder().build();
            AppAuthState retAuthState = client.getSubject(authState, new GetSubjectParser(authState));

            AppSource expected = new AppSource(0, "p", "m", "v", true);
            expected.setSourceId("i");
            expected.setSourceName("s");
            expected.setExpectedSourceName("e");
            expected.setAttributes(Collections.singletonMap("k", "v"));

            assertEquals(Collections.singletonList(expected), retAuthState.getProperty(SOURCES_PROPERTY));
            assertEquals("proj-name", retAuthState.getProjectId());
            assertEquals("sub-1", retAuthState.getUserId());
        }
    }

    @Test
    public void sourceRegistrationBody() throws JSONException {
        AppSource source = new AppSource(0, "p", "m", "v", true);
        source.setSourceName("something");
        source.setAttributes(Collections.singletonMap("firmware", "0.11"));

        String body = ManagementPortalClient.sourceRegistrationBody(source).toString();
        JSONObject object = new JSONObject(body);
        assertEquals("something", object.getString("sourceName"));
        assertEquals(0, object.getInt("sourceTypeId"));
        JSONObject attr = object.getJSONObject("attributes");
        assertEquals(3, object.names().length());
        assertEquals("0.11", attr.getString("firmware"));
        assertEquals(1, attr.names().length());
    }

    @Test
    public void sourceRegistrationBodyWithSourceNameSanitizing() throws JSONException {
        AppSource source = new AppSource(0, "p", "m", "v", true);
        source.setSourceName("something(With)_others+");

        String body = ManagementPortalClient.sourceRegistrationBody(source).toString();
        JSONObject object = new JSONObject(body);
        assertEquals("something-With-_others-", object.getString("sourceName"));
        assertEquals(0, object.getInt("sourceTypeId"));
        assertEquals(2, object.names().length());
    }

    @Test
    public void parseSourceRegistration() throws JSONException {
        AppSource source = new AppSource(0, "p", "m", "v", true);
        source.setSourceName("something");
        String response = "{\"sourceName\": \"something_18131\", \"sourceId\": \"uuid-abcdef\", \"deviceTypeId\": 0, \"attributes\":{\"firmware\":\"0.11\"}, \"expectedSourceName\": \"abc\"}";
        ManagementPortalClient.parseSourceRegistration(response, source);
        assertEquals("something_18131", source.getSourceName());
        assertEquals("uuid-abcdef", source.getSourceId());
        assertEquals(Collections.singletonMap("firmware", "0.11"), source.getAttributes());
        assertEquals("abc", source.getExpectedSourceName());
    }
}
