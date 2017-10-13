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

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Pair;

import org.apache.avro.specific.SpecificRecord;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.data.Record;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.kafka.ObservationKey;
import org.radarcns.topic.AvroTopic;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface DeviceServiceBinder {
    /** Start scanning and recording from a compatible device.
     * @param acceptableIds a set of source IDs that may be connected to.
     *                      If empty, no selection is made.
     */
    BaseDeviceState startRecording(@NonNull Set<String> acceptableIds);
    /** Stop scanning and recording */
    void stopRecording();
    <V extends SpecificRecord> List<Record<ObservationKey, V>> getRecords(@NonNull AvroTopic<ObservationKey, V> topic, int limit) throws IOException;
    /** Get the current device status */
    BaseDeviceState getDeviceStatus();
    /** Get the current device name, or null if unknown. */
    String getDeviceName();
    /** Get the current server status */
    ServerStatusListener.Status getServerStatus();
    /** Get the last number of records sent */
    Map<String, Integer> getServerRecordsSent();
    /** Update the configuration of the service */
    void updateConfiguration(Bundle bundle);
    /** Number of records in cache [unsent] and [sent] */
    Pair<Long, Long> numberOfRecords();

    void setDataHandler(TableDataHandler dataHandler);
}
