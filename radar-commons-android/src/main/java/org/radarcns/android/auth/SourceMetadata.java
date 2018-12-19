package org.radarcns.android.auth;

import android.support.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public class SourceMetadata {
    private final long sourceTypeId;
    private final String sourceTypeProducer;
    private final String sourceTypeModel;
    private final String sourceTypeCatalogVersion;
    private final boolean dynamicRegistration;
    private String sourceId;
    private String sourceName;
    private String expectedSourceName;
    private Map<String, String> attributes;

    public SourceMetadata() {
        this(-1L, null, null, null, true);
    }

    @SuppressWarnings("deprecation")
    @Deprecated
    public SourceMetadata(AppSource appSource) {
        this.sourceTypeId = appSource.getSourceTypeId();
        this.sourceTypeProducer = appSource.getSourceTypeProducer();
        this.sourceTypeModel = appSource.getSourceTypeModel();
        this.sourceTypeCatalogVersion = appSource.getSourceTypeCatalogVersion();
        this.dynamicRegistration = appSource.hasDynamicRegistration();
        this.sourceId = appSource.getSourceId();
        this.sourceName = appSource.getSourceName();
        this.expectedSourceName = appSource.getExpectedSourceName();
        this.attributes = new HashMap<>(appSource.getAttributes());
    }

    public SourceMetadata(String jsonString) throws JSONException {
        JSONObject json = new JSONObject(jsonString);
        this.sourceTypeId = json.getLong("sourceTypeId");
        this.sourceTypeProducer = json.optString("sourceTypeProducer", null);
        this.sourceTypeModel = json.optString("sourceTypeModel", null);
        this.sourceTypeCatalogVersion = json.optString("sourceTypeCatalogVersion", null);
        this.dynamicRegistration = json.getBoolean("dynamicRegistration");
        this.sourceId = json.optString("sourceId", null);
        this.sourceName = json.optString("sourceName", null);
        this.expectedSourceName = json.optString("expectedSourceName", null);
        this.attributes = new HashMap<>();
        JSONObject attributesJson = json.optJSONObject("attributes");
        if (attributesJson != null) {
            for (Iterator<String> it = attributesJson.keys(); it.hasNext(); ) {
                String key = it.next();
                this.attributes.put(key, attributesJson.getString(key));
            }
        }
    }

    public SourceMetadata(long deviceTypeId, String deviceProducer, String deviceModel, String catalogVersion,
                          boolean dynamicRegistration) {
        this.sourceTypeId = deviceTypeId;
        this.sourceTypeProducer = deviceProducer;
        this.sourceTypeModel = deviceModel;
        this.sourceTypeCatalogVersion = catalogVersion;
        this.dynamicRegistration = dynamicRegistration;
        this.attributes = new HashMap<>();
    }

    public String getSourceTypeProducer() {
        return sourceTypeProducer;
    }

    public String getSourceTypeModel() {
        return sourceTypeModel;
    }

    public String getSourceTypeCatalogVersion() {
        return sourceTypeCatalogVersion;
    }

    public boolean hasDynamicRegistration() {
        return dynamicRegistration;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getExpectedSourceName() {
        return expectedSourceName;
    }

    public void setExpectedSourceName(String expectedSourceName) {
        this.expectedSourceName = expectedSourceName;
    }

    @NonNull
    public Map<String, String> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public void setAttributes(Map<? extends String, ? extends String> attributes) {
        this.attributes.clear();
        if (attributes != null) {
            this.attributes.putAll(attributes);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SourceMetadata appSource = (SourceMetadata) o;
        return sourceTypeId == appSource.sourceTypeId
                && dynamicRegistration == appSource.dynamicRegistration
                && Objects.equals(sourceTypeProducer, appSource.sourceTypeProducer)
                && Objects.equals(sourceTypeModel, appSource.sourceTypeModel)
                && Objects.equals(sourceTypeCatalogVersion, appSource.sourceTypeCatalogVersion)
                && Objects.equals(sourceId, appSource.sourceId)
                && Objects.equals(sourceName, appSource.sourceName)
                && Objects.equals(expectedSourceName, appSource.expectedSourceName)
                && Objects.equals(attributes, appSource.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceTypeId, sourceTypeProducer, sourceTypeModel, sourceTypeCatalogVersion,
                dynamicRegistration, sourceId, sourceName, expectedSourceName, attributes);
    }

    @Override
    public String toString() {
        return "SourceMetadata{"
                + "sourceTypeId='" + sourceTypeId + '\''
                + ", sourceTypeProducer='" + sourceTypeProducer + '\''
                + ", sourceTypeModel='" + sourceTypeModel + '\''
                + ", sourceTypeCatalogVersion='" + sourceTypeCatalogVersion + '\''
                + ", dynamicRegistration=" + dynamicRegistration
                + ", sourceId='" + sourceId + '\''
                + ", sourceName='" + sourceName + '\''
                + ", expectedSourceName='" + expectedSourceName + '\''
                + ", attributes=" + attributes + '\''
                + '}';
    }

    public String toJsonString() {
        try {
            JSONObject json = new JSONObject();
            json.put("sourceTypeId", sourceTypeId);
            json.put("sourceTypeProducer", sourceTypeProducer);
            json.put("sourceTypeModel", sourceTypeModel);
            json.put("sourceTypeCatalogVersion", sourceTypeCatalogVersion);
            json.put("dynamicRegistration", dynamicRegistration);
            json.put("sourceId", sourceId);
            json.put("sourceName", sourceName);
            json.put("expectedSourceName", expectedSourceName);

            JSONObject attributeJson = new JSONObject();
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                attributeJson.put(entry.getKey(), entry.getValue());
            }

            json.put("attributes", attributeJson);
            return json.toString();
        } catch (JSONException ex) {
            throw new IllegalStateException("Cannot serialize existing SourceMetadata");
        }
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public long getSourceTypeId() {
        return sourceTypeId;
    }

    @SuppressWarnings("deprecation")
    @Deprecated
    public AppSource toAppSource() {
        AppSource source = new AppSource(sourceTypeId, sourceTypeProducer, sourceTypeModel, sourceTypeCatalogVersion, dynamicRegistration);
        source.setSourceId(sourceId);
        source.setSourceName(sourceName);
        source.setExpectedSourceName(expectedSourceName);
        source.setAttributes(attributes);
        return source;
    }
}
