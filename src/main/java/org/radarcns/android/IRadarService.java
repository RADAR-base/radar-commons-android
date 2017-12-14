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

package org.radarcns.android;

import org.radarcns.android.auth.AppAuthState;
import org.radarcns.android.device.DeviceServiceConnection;
import org.radarcns.android.device.DeviceServiceProvider;
import org.radarcns.android.kafka.ServerStatusListener;
import org.radarcns.data.TimedInt;

import java.util.List;
import java.util.Set;

public interface IRadarService {
    ServerStatusListener.Status getServerStatus();

    TimedInt getLatestNumberOfRecordsSent();

    List<DeviceServiceProvider> getConnections();

    AppAuthState getAuthState();

    void setAllowedDeviceIds(DeviceServiceConnection connection, Set<String> allowedIds);
}
