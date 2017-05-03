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
import android.net.NetworkInfo;
import android.support.annotation.NonNull;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_WIFI;

/** Keeps track of whether there is a network connection (e.g., WiFi or Ethernet). */
public class NetworkConnectedReceiver extends SpecificReceiver {
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
    protected void onSpecificReceive(Intent intent) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        isConnected = activeNetwork.isConnected();
        hasWifiOrEthernet = activeNetwork.getType() == TYPE_WIFI
                || activeNetwork.getType() == TYPE_ETHERNET;
        if (listener != null) {
            listener.onNetworkConnectionChanged(isConnected, hasWifiOrEthernet);
        }
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
