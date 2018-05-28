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

import android.support.annotation.NonNull;
import org.radarcns.android.auth.AppSource;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

/** AppSource manager of a wearable device. */
public interface DeviceManager<T> extends Closeable {

    /**
     * Start scanning and try to connect. Check that {@link #isClosed()} is false before calling
     * this.
     * @param acceptableIds IDs that are acceptable to connect to. If empty, no selection is made.
     */
    void start(@NonNull Set<String> acceptableIds);

    /**
     * Close the device manager. After calling this, {@link #start(Set)} may no longer be called.
     */
    @Override
    void close() throws IOException;

    /** Whether the device manager was already closed. */
    boolean isClosed();

    /**
     * Get the state of a wearable device.
     *
     * If no wearable is connected, it returns a state with DeviceStatusListener.Status.DISCONNECTED
     * status.
     * @return device state
     */
    T getState();

    /**
     * Get the name of a connected wearable device.
     */
    String getName();

    /**
     * Called when a source registration succeeds.
     * @param source source metadata
     */
    void didRegister(AppSource source);
}
