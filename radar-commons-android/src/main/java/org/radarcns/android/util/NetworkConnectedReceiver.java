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

package org.radarcns.android.util;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_WIFI;

/**
 * Keeps track of whether there is a network connection (e.g., WiFi or Ethernet).
 *
 * <p>This class should be updated to use
 * {@link ConnectivityManager#registerDefaultNetworkCallback(ConnectivityManager.NetworkCallback)}
 * instead of a BroadcastReceiver.
 */
public class NetworkConnectedReceiver extends SpecificReceiver {
    private static final Logger logger = LoggerFactory.getLogger(NetworkConnectedReceiver.class);

    private final NetworkConnectedListener listener;
    private boolean hasWifiOrEthernet;
    private boolean isConnected;

    public NetworkConnectedReceiver(@NonNull Context context, NetworkConnectedListener listener) {
        super(context);
        this.listener = listener;
        this.isConnected = true;
        this.hasWifiOrEthernet = true;
    }

    @Override
    protected void onSpecificReceive(@NonNull Intent intent) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            logger.warn("Connectivity cannot be checked: System ConnectivityManager is unavailable.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            updateCapabilities(cm);
        } else {
            updateNetworkInfo(cm);
        }
        if (listener != null) {
            listener.onNetworkConnectionChanged(isConnected, hasWifiOrEthernet);
        }
    }

    @SuppressWarnings("deprecation")
    private void updateNetworkInfo(@NonNull ConnectivityManager cm) {
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork == null) {
            isConnected = false;
            hasWifiOrEthernet = false;
        } else {
            isConnected = activeNetwork.isConnected();
            int networkType = activeNetwork.getType();
            hasWifiOrEthernet = networkType == TYPE_WIFI || networkType == TYPE_ETHERNET;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void updateCapabilities(@NonNull ConnectivityManager cm) {
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        isConnected = activeNetworkInfo != null && activeNetworkInfo.isConnected();

        Network activeNetwork = cm.getActiveNetwork();
        NetworkCapabilities capabilities = activeNetwork != null ? cm.getNetworkCapabilities(activeNetwork) : null;
        hasWifiOrEthernet = capabilities != null
                && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    @Override
    protected String getAction() {
        return CONNECTIVITY_ACTION;
    }

    public boolean hasConnection(boolean wifiOrEthernetOnly) {
        return isConnected && (hasWifiOrEthernet || !wifiOrEthernetOnly);
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean hasWifiOrEthernet() {
        return hasWifiOrEthernet;
    }

    public interface NetworkConnectedListener {
        void onNetworkConnectionChanged(boolean isConnected, boolean hasWifiOrEthernet);
    }
}
