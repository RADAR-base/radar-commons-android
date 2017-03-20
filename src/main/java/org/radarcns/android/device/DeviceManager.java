/*
 * Copyright 2017 Kings College London and The Hyve
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

import android.support.annotation.NonNull;

import java.io.Closeable;
import java.util.Set;

/** Device manager of a wearable device. */
public interface DeviceManager extends Closeable {

    /** Start scanning and try to connect
     * @param acceptableIds IDs that are acceptable to connect to. If empty, no selection is made.
     */
    void start(@NonNull Set<String> acceptableIds);

    /** Whether the device manager was already closed. */
    boolean isClosed();

    /**
     * Get the state of a wearable device.
     *
     * If no wearable is connected, it returns a state with DeviceStatusListener.Status.DISCONNECTED
     * status.
     * @return device state
     */
    BaseDeviceState getState();

    /**
     * Get the name of a connected wearable device.
     */
    String getName();
}
