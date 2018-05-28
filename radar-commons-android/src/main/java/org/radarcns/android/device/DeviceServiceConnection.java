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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;

import org.radarcns.android.RadarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static org.radarcns.android.device.DeviceService.DEVICE_SERVICE_CLASS;
import static org.radarcns.android.device.DeviceService.DEVICE_STATUS_CHANGED;
import static org.radarcns.android.device.DeviceService.DEVICE_STATUS_NAME;

public class DeviceServiceConnection<S extends BaseDeviceState> extends BaseServiceConnection<S> {
    private static final Logger logger = LoggerFactory.getLogger(DeviceServiceConnection.class);
    private final RadarService radarService;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Objects.equals(intent.getAction(), DEVICE_STATUS_CHANGED)) {
                if (getServiceClassName().equals(intent.getStringExtra(DEVICE_SERVICE_CLASS))) {
                    if (intent.hasExtra(DEVICE_STATUS_NAME)) {
                        deviceName = intent.getStringExtra(DEVICE_STATUS_NAME);
                        logger.info("AppSource status changed of device {}", deviceName);
                    }
                    setDeviceStatus(DeviceStatusListener.Status.values()[intent.getIntExtra(DEVICE_STATUS_CHANGED, 0)]);
                    logger.info("Updated device status to {}", getDeviceStatus());
                    radarService.deviceStatusUpdated(DeviceServiceConnection.this, getDeviceStatus());
                }
            }
        }
    };

    public DeviceServiceConnection(@NonNull RadarService radarService, String serviceClassName) {
        super(serviceClassName);
        this.radarService = radarService;
    }

    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
        LocalBroadcastManager.getInstance(radarService)
                .registerReceiver(statusReceiver, new IntentFilter(DEVICE_STATUS_CHANGED));

        if (!hasService()) {
            super.onServiceConnected(className, service);
            if (hasService()) {
                getServiceBinder().setDataHandler(radarService.getDataHandler());
                radarService.serviceConnected(this);
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
        boolean hadService = hasService();
        super.onServiceDisconnected(className);

        if (hadService) {
            LocalBroadcastManager.getInstance(radarService).unregisterReceiver(statusReceiver);
            radarService.serviceDisconnected(this);
        }
    }

    public Context getContext() {
        return radarService;
    }
}
