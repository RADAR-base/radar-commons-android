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

import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.data.DataCache;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.key.MeasurementKey;
import org.radarcns.topic.AvroTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Abstract DeviceManager that handles some common functionality.
 *
 * @param <S> service type the manager is started by
 * @param <T> state type that the manager will update.
 */
public abstract class AbstractDeviceManager<S extends DeviceService, T extends BaseDeviceState> implements DeviceManager {
    private static final Logger logger = LoggerFactory.getLogger(AbstractDeviceManager.class);

    private final TableDataHandler dataHandler;
    private final T deviceStatus;
    private String deviceName;
    private final S service;
    private boolean closed;

    /**
     * Device manager initialization. After initialization, be sure to call
     * {@link #setName(String)}.
     *
     * @param service service that the manager is started by
     * @param state new empty state variable
     * @param dataHandler data handler for sending data and getting topics.
     * @param userId user ID that data should be sent with
     * @param sourceId initial source ID, override later by calling getId().setSourceId()
     */
    public AbstractDeviceManager(S service, T state, TableDataHandler dataHandler, String userId, String sourceId) {
        this.dataHandler = dataHandler;
        this.service = service;
        this.deviceStatus = state;
        this.deviceStatus.getId().setUserId(userId);
        this.deviceStatus.getId().setSourceId(sourceId);
        this.deviceName = android.os.Build.MODEL;
        closed = false;
    }

    /**
     * Update the device status. The device status should be updated with the following meanings:
     * <ul>
     *     <li>DISABLED if the manager will not be able to record any data.</li>
     *     <li>READY if the manager is searching for a device to connect with.</li>
     *     <li>CONNECTING if the manager has found a device to connect with.</li>
     *     <li>CONNECTED if the manager is connected to a device.</li>
     *     <li>DISCONNECTED if the device has disconnected OR the manager is closed.</li>
     * </ul>
     * If DISABLED is set, no other status may be set.  Once DISCONNECTED has been set, no other
     * status may be set, and the manager will be closed by the service if not already closed.
     *
     * @param status status to set
     */
    protected void updateStatus(DeviceStatusListener.Status status) {
        this.deviceStatus.setStatus(status);
        this.service.deviceStatusUpdated(this, status);
    }

    /** Send a single record, using the cache to persist the data. */
    protected <V extends SpecificRecord> void send(DataCache<MeasurementKey, V> table, V value) {
        dataHandler.addMeasurement(table, deviceStatus.getId(), value);
    }

    /** Try to send a single record without any caching mechanism. */
    protected <V extends SpecificRecord> void trySend(AvroTopic<MeasurementKey, V> topic, long offset, V value) {
        dataHandler.trySend(topic, offset, deviceStatus.getId(), value);
    }

    /** Get a data cache to send data with at a later time. */
    protected <V extends SpecificRecord> DataCache<MeasurementKey, V> getCache(AvroTopic<MeasurementKey, V> topic) {
        return dataHandler.getCache(topic);
    }

    /** Get the service that started this device manager. */
    public S getService() {
        return service;
    }

    /** Whether this device manager has been closed. */
    @Override
    public boolean isClosed() {
        return closed;
    }

    /** Get the current device state. */
    @Override
    public T getState() {
        return deviceStatus;
    }

    /** Set the device name. Be sure to do this as soon as possible. */
    protected void setName(String name) {
        this.deviceName = name;
    }

    /** Get the name of the device. */
    @Override
    public String getName() {
        return deviceName;
    }

    /**
     * Close the manager, disconnecting any device if necessary. Override and call super if
     * additional resources should be cleaned up. This implementation calls updateStatus with status
     * DISCONNECTED.
     */
    @Override
    public void close() throws IOException {
        closed = true;
        updateStatus(DeviceStatusListener.Status.DISCONNECTED);
    }

    @Override
    public String toString() {
        return "DeviceManager{name='" + deviceName + "', status=" + deviceStatus + '}';
    }

    /** Tests equality based on the device MeasurementKey. */
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (other == null
                || !getClass().equals(other.getClass())
                || deviceStatus.getId().getSourceId() == null) {
            return false;
        }

        AbstractDeviceManager otherDevice = ((AbstractDeviceManager) other);
        return deviceStatus.getId().equals((otherDevice.deviceStatus.getId()));
    }

    @Override
    public int hashCode() {
        return deviceStatus.getId().hashCode();
    }
}
