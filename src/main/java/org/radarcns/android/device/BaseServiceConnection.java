/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.android.device;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.util.Pair;

import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.data.Record;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.topic.AvroTopic;
import org.radarcns.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class BaseServiceConnection<S extends BaseDeviceState> implements ServiceConnection {
    private static final Logger logger = LoggerFactory.getLogger(BaseServiceConnection.class);
    private DeviceStatusListener.Status deviceStatus;
    public String deviceName;
    private DeviceServiceBinder serviceBinder;
    private final String serviceClassName;

    public BaseServiceConnection(String serviceClassName) {
        this.serviceBinder = null;
        this.deviceName = null;
        this.deviceStatus = DeviceStatusListener.Status.DISCONNECTED;
        this.serviceClassName = serviceClassName;
    }

    @Override
    public void onServiceConnected(final ComponentName className, IBinder service) {
        if (serviceBinder == null) {
            logger.info("Bound to service {}", className);
            try {
                synchronized (this) {
                    serviceBinder = (DeviceServiceBinder) service;
                }
            } catch (ClassCastException ex) {
                throw new IllegalStateException("Cannot process remote device services.");
            }

            deviceStatus = getDeviceData().getStatus();
        } else {
            logger.info("Trying to re-bind service, from {} to {}", serviceBinder, service);
        }
    }

    public <V extends SpecificRecord> List<Record<ObservationKey, V>> getRecords(@NonNull AvroTopic<ObservationKey, V> topic, int limit) throws IOException {
        LinkedList<Record<ObservationKey, V>> result = new LinkedList<>();

        for (Record<ObservationKey, V> record : serviceBinder.getRecords(topic, limit)) {
            result.addFirst(record);
        }

        return result;
    }

    /**
     * Start looking for devices to record.
     * @param acceptableIds case insensitive parts of device ID's that are allowed to connect.
     * @throws IllegalStateException if the user ID was not set yet
     */
    public void startRecording(@NonNull Set<String> acceptableIds) {
        deviceStatus = serviceBinder.startRecording(acceptableIds).getStatus();
    }

    public void stopRecording() {
        serviceBinder.stopRecording();
    }

    public boolean isRecording() {
        return deviceStatus != DeviceStatusListener.Status.DISCONNECTED;
    }

    public boolean hasService() {
        return serviceBinder != null;
    }

    public ServerStatusListener.Status getServerStatus() {
        return serviceBinder.getServerStatus();
    }

    public Map<String, Integer> getServerSent() {
        return serviceBinder.getServerRecordsSent();
    }

    public S getDeviceData() {
        //noinspection unchecked
        return (S)serviceBinder.getDeviceStatus();
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
        // only do these steps once
        if (hasService()) {
            synchronized (this) {
                deviceName = null;
                serviceBinder = null;
                deviceStatus = DeviceStatusListener.Status.DISCONNECTED;
            }
        }
    }

    public String getDeviceName() {
        return serviceBinder.getDeviceName();
    }

    public void updateConfiguration(Bundle bundle) {
        serviceBinder.updateConfiguration(bundle);
    }

    public void setUserId(String userId) {
        serviceBinder.setUserId(userId);
    }

    public Pair<Long, Long> numberOfRecords() {
        return serviceBinder.numberOfRecords();
    }

    /**
     * True if given string is a substring of the device name.
     */
    public boolean isAllowedDevice(Collection<String> values) {
        if (values.isEmpty()) {
            return true;
        }
        for (String value : values) {
            Pattern pattern = Strings.containsIgnoreCasePattern(value);
            String deviceName = serviceBinder.getDeviceName();
            if (deviceName != null && pattern.matcher(deviceName).find()) {
                return true;
            }

            String sourceId = serviceBinder.getDeviceStatus().getId().getSourceId();

            if (sourceId != null && pattern.matcher(sourceId).find()) {
                return true;
            }
        }
        return false;
    }

    public synchronized DeviceStatusListener.Status getDeviceStatus() {
        return deviceStatus;
    }

    protected synchronized void setDeviceStatus(DeviceStatusListener.Status status) {
        this.deviceStatus = status;
    }

    protected synchronized DeviceServiceBinder getServiceBinder() {
        return serviceBinder;
    }

    public String getServiceClassName() {
        return serviceClassName;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        BaseServiceConnection otherService = (BaseServiceConnection) other;
        return Objects.equals(this.serviceClassName, otherService.serviceClassName);
    }

    @Override
    public int hashCode() {
        return serviceClassName.hashCode();
    }

    public String toString() {
        return getClass().getSimpleName() + "<" + getServiceClassName() + ">";
    }
}
