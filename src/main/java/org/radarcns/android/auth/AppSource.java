package org.radarcns.android.auth;

import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class AppSource implements Parcelable, Serializable {
    private final long deviceTypeId;
    private final String deviceProducer;
    private final String deviceModel;
    private final String catalogVersion;
    private final boolean dynamicRegistration;
    private String sourceId;
    private String sourceName;
    private String expectedSourceName;
    private Map<String, String> attributes;

    public static final Creator<AppSource> CREATOR = new Creator<AppSource>() {
        @Override
        public AppSource createFromParcel(Parcel parcel) {
            AppSource source = new AppSource(parcel.readLong(), parcel.readString(), parcel.readString(),
                    parcel.readString(), parcel.readByte() == 1);
            source.setSourceId(parcel.readString());
            source.setSourceName(parcel.readString());
            source.setExpectedSourceName(parcel.readString());
            int len = parcel.readInt();
            Map<String, String> attr = new HashMap<>(len * 4 / 3 + 1);
            for (int i = 0; i < len; i++) {
                attr.put(parcel.readString(), parcel.readString());
            }
            source.setAttributes(attr);
            return source;
        }

        @Override
        public AppSource[] newArray(int i) {
            return new AppSource[i];
        }
    };

    public AppSource(long deviceTypeId, String deviceProducer, String deviceModel, String catalogVersion,
            boolean dynamicRegistration) {
        this.deviceTypeId = deviceTypeId;
        this.deviceProducer = deviceProducer;
        this.deviceModel = deviceModel;
        this.catalogVersion = catalogVersion;
        this.dynamicRegistration = dynamicRegistration;
        this.attributes = new HashMap<>();
    }

    public String getDeviceProducer() {
        return deviceProducer;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public String getCatalogVersion() {
        return catalogVersion;
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
        this.attributes.putAll(attributes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AppSource appSource = (AppSource) o;
        return deviceTypeId == appSource.deviceTypeId
                && dynamicRegistration == appSource.dynamicRegistration
                && Objects.equals(deviceProducer, appSource.deviceProducer)
                && Objects.equals(deviceModel, appSource.deviceModel)
                && Objects.equals(catalogVersion, appSource.catalogVersion)
                && Objects.equals(sourceId, appSource.sourceId)
                && Objects.equals(sourceName, appSource.sourceName)
                && Objects.equals(expectedSourceName, appSource.expectedSourceName)
                && Objects.equals(attributes, appSource.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceTypeId, deviceProducer, deviceModel, catalogVersion,
                dynamicRegistration, sourceId, sourceName, expectedSourceName, attributes);
    }

    @Override
    public String toString() {
        return "AppSource{"
                + "deviceTypeId='" + deviceTypeId + '\''
                + ", deviceProducer='" + deviceProducer + '\''
                + ", deviceModel='" + deviceModel + '\''
                + ", catalogVersion='" + catalogVersion + '\''
                + ", dynamicRegistration=" + dynamicRegistration
                + ", sourceId='" + sourceId + '\''
                + ", sourceName='" + sourceName + '\''
                + ", expectedSourceName='" + expectedSourceName + '\''
                + ", attributes=" + attributes + '\''
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeLong(deviceTypeId);
        parcel.writeString(deviceProducer);
        parcel.writeString(deviceModel);
        parcel.writeString(catalogVersion);
        parcel.writeByte(dynamicRegistration ? (byte)1 : (byte)0);
        parcel.writeString(sourceId);
        parcel.writeString(sourceName);
        parcel.writeString(expectedSourceName);
        parcel.writeInt(attributes.size());
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            parcel.writeString(entry.getKey());
            parcel.writeString(entry.getValue());
        }
    }

    public String getSourceName() {
        return sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    public long getDeviceTypeId() {
        return deviceTypeId;
    }
}
