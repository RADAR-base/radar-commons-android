package org.radarcns.android.auth.portal;

import android.support.annotation.NonNull;
import android.util.SparseArray;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.auth.AppSource;
import org.radarcns.android.auth.AuthStringParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

public class GetSubjectParser implements AuthStringParser {
    private static final Logger logger = LoggerFactory.getLogger(GetSubjectParser.class);
    private static final String MP_ATTRIBUTES = GetSubjectParser.class.getName() + ".attributes";
    private final AppAuthState state;

    public GetSubjectParser(AppAuthState state) {
        this.state = state;
    }

    @Override
    public AppAuthState parse(@NonNull String bodyString) throws IOException {
        try {
            JSONObject object = new JSONObject(bodyString);
            JSONObject project = object.getJSONObject("project");
            JSONArray sources = object.getJSONArray("sources");

            SparseArray<AppSource> sourceTypes = parseSourceTypes(project);

            AppAuthState.Builder builder = state.newBuilder()
                    .property(ManagementPortalClient.SOURCES_PROPERTY, parseSources(sourceTypes, sources))
                    .userId(parseUserId(object))
                    .projectId(parseProjectId(project));

            Object attrObjects = object.opt("attributes");
            if (attrObjects != null) {
                if (attrObjects instanceof JSONArray) {
                    JSONArray attrObjectArray = (JSONArray) attrObjects;
                    if (attrObjectArray.length() > 0) {
                        HashMap<String, String> attributes = new HashMap<>();
                        for (int i = 0; i < attrObjectArray.length(); i++) {
                            JSONObject attrObject = attrObjectArray.getJSONObject(i);
                            attributes.put(attrObject.getString("key"),
                                    attrObject.getString("value"));
                        }
                        builder.property(MP_ATTRIBUTES, attributes);
                    }
                } else {
                    HashMap<String, String> attributes = attributesToMap((JSONObject) attrObjects);
                    if (attributes != null) {
                        builder.property(MP_ATTRIBUTES, attributes);
                    }
                }
            }
            return builder.build();
        } catch (JSONException e) {
            throw new IOException(
                    "ManagementPortal did not give a valid response: " + bodyString, e);
        }
    }

    static SparseArray<AppSource> parseSourceTypes(JSONObject project)
            throws JSONException {
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

    static ArrayList<AppSource> parseSources(SparseArray<AppSource> sourceTypes,
            JSONArray sources) throws JSONException {

        ArrayList<AppSource> actualSources = new ArrayList<>(sourceTypes.size());

        int numSources = sources.length();
        for (int i = 0; i < numSources; i++) {
            JSONObject sourceObj = sources.getJSONObject(i);
            String sourceId = sourceObj.getString("sourceId");
            if (!sourceObj.optBoolean("assigned", true)) {
                logger.debug("Skipping unassigned source {}", sourceId);
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

    static HashMap<String, String> attributesToMap(JSONObject attrObj) throws JSONException {
        if (attrObj == null || attrObj.length() == 0) {
            return null;
        }
        HashMap<String, String> attrs = new HashMap<>();
        for (Iterator<String> it = attrObj.keys(); it.hasNext(); ) {
            String key = it.next();
            attrs.put(key, attrObj.getString(key));
        }
        return attrs;
    }

    static String parseProjectId(JSONObject project) throws JSONException {
        return project.getString("projectName");
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

    public static String getHumanReadableUserId(AppAuthState state) {
        @SuppressWarnings("unchecked")
        HashMap<String, String> attr = (HashMap<String, String>) state.getProperty(MP_ATTRIBUTES);
        if (attr != null) {
            for (String attrName : attr.keySet()) {
                if (attrName.equalsIgnoreCase("Human-readable-identifier")) {
                    return attr.get(attrName);
                }
            }
        }
        return state.getUserId();
    }
}
