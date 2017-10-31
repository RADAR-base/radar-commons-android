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

    TimedInt getTopicsSent(DeviceServiceConnection connection);

    String getLatestTopicSent();

    TimedInt getLatestNumberOfRecordsSent();

    List<DeviceServiceProvider> getConnections();

    AppAuthState getAuthState();

    void setAllowedDeviceIds(DeviceServiceConnection connection, Set<String> allowedIds);
}
