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
import android.support.annotation.Nullable;
import android.util.Pair;

import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.data.RecordData;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public interface DeviceServiceBinder {
    /** Start scanning and recording from a compatible device.
     * @param acceptableIds a set of source IDs that may be connected to.
     *                      If empty, no selection is made.
     */
    @Nullable
    BaseDeviceState startRecording(@NonNull Set<String> acceptableIds);
    /** Stop scanning and recording */
    void stopRecording();
    @Nullable
    RecordData<Object, Object> getRecords(@NonNull String topic, int limit) throws IOException;
    /** Get the current device status */
    @Nullable
    BaseDeviceState getDeviceStatus();
    /** Get the current device name, or null if unknown. */
    @Nullable
    String getDeviceName();
    /** Get the current server status */
    @NonNull
    ServerStatusListener.Status getServerStatus();
    /** Get the last number of records sent */
    @NonNull
    Map<String, Integer> getServerRecordsSent();
    /** Update the configuration of the service */
    void updateConfiguration(@NonNull Bundle bundle);
    /** Number of records in cache [unsent] and [sent] */
    @NonNull
    Pair<Long, Long> numberOfRecords();
    boolean needsBluetooth();

    void setDataHandler(@NonNull TableDataHandler dataHandler);
}
