package org.radarcns.android.auth;

import java.util.Objects;

public class AppSource {
    private final String deviceProducer;
    private final String deviceModel;
    private final String catalogVersion;
    private final boolean dynamicRegistration;
    private String sourceId;
    private String expectedSourceName;

    public AppSource(String deviceProducer, String deviceModel, String catalogVersion,
            boolean dynamicRegistration) {
        this.deviceProducer = deviceProducer;
        this.deviceModel = deviceModel;
        this.catalogVersion = catalogVersion;
        this.dynamicRegistration = dynamicRegistration;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AppSource appSource = (AppSource) o;
        return dynamicRegistration == appSource.dynamicRegistration
                && Objects.equals(deviceProducer, appSource.deviceProducer)
                && Objects.equals(deviceModel, appSource.deviceModel)
                && Objects.equals(catalogVersion, appSource.catalogVersion)
                && Objects.equals(sourceId, appSource.sourceId)
                && Objects.equals(expectedSourceName, appSource.expectedSourceName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceProducer, deviceModel, catalogVersion, dynamicRegistration,
                sourceId, expectedSourceName);
    }

    @Override
    public String toString() {
        return "AppSource{" + "deviceProducer='" + deviceProducer + '\''
                + ", deviceModel='" + deviceModel + '\''
                + ", catalogVersion='" + catalogVersion + '\''
                + ", dynamicRegistration=" + dynamicRegistration
                + ", sourceId='" + sourceId + '\''
                + ", expectedSourceName='" + expectedSourceName + '\''
                + '}';
    }
}
