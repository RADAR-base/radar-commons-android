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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_WIFI;

/**
 * Keeps track of whether there is a network connection (e.g., WiFi or Ethernet).
 */
public class NetworkConnectedReceiver implements SpecificReceiver {
    private static final Logger logger = LoggerFactory.getLogger(NetworkConnectedReceiver.class);
    protected final Context context;

    @Nullable
    private final NetworkConnectedListener listener;
    @Nullable
    private final ConnectivityManager connectivityManager;
    private boolean hasWifiOrEthernet;
    private boolean isConnected;
    private boolean isReceiverRegistered;
    @Nullable
    private BroadcastReceiver receiver;
    private ConnectivityManager.NetworkCallback callback;

    public NetworkConnectedReceiver(@NonNull Context context, @Nullable NetworkConnectedListener listener) {
        this.context = Objects.requireNonNull(context);
        this.listener = listener;
        this.isConnected = true;
        this.hasWifiOrEthernet = true;
        this.isReceiverRegistered = false;
        this.receiver = null;
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
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

    public void register() {
        if (connectivityManager == null) {
            logger.warn("Connectivity cannot be checked: System ConnectivityManager is unavailable.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerCallback(connectivityManager);
        } else {
            registerBroadcastReceiver(connectivityManager);
        }
    }

    @SuppressWarnings("deprecation")
    private void registerBroadcastReceiver(@NonNull ConnectivityManager cm) {
        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !Objects.equals(intent.getAction(), CONNECTIVITY_ACTION)) {
                        return;
                    }
                    NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                    if (activeNetwork == null) {
                        isConnected = false;
                        hasWifiOrEthernet = false;
                    } else {
                        isConnected = activeNetwork.isConnected();
                        int networkType = activeNetwork.getType();
                        hasWifiOrEthernet = networkType == TYPE_WIFI || networkType == TYPE_ETHERNET;
                    }

                    updateListener();
                }
            };
        }
        BroadcastReceiver localReceiver = receiver;
        Intent init = context.registerReceiver(localReceiver, new IntentFilter(CONNECTIVITY_ACTION));
        isReceiverRegistered = true;
        localReceiver.onReceive(context, init);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private void registerCallback(@NonNull ConnectivityManager cm) {
        if (callback == null) {
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    isConnected = true;
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    hasWifiOrEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    updateListener();
                }

                @Override
                public void onUnavailable() {
                    // nothing happened
                }

                @Override
                public void onLost(Network network) {
                    isConnected = false;
                    hasWifiOrEthernet = false;
                    updateListener();
                }
            };
        }
        cm.registerDefaultNetworkCallback(callback);
        isReceiverRegistered = true;

        Network network = cm.getActiveNetwork();
        NetworkInfo networkInfo = network != null ? cm.getNetworkInfo(network) : null;
        if (networkInfo != null && networkInfo.isConnected()) {
            isConnected = true;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            hasWifiOrEthernet = capabilities != null
                    && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } else {
            isConnected = false;
            hasWifiOrEthernet = false;
        }
        updateListener();
    }

    private void updateListener() {
        if (listener != null) {
            listener.onNetworkConnectionChanged(isConnected, hasWifiOrEthernet);
        }
    }

    public void unregister() {
        if (!isReceiverRegistered) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (connectivityManager != null) {
                connectivityManager.unregisterNetworkCallback(callback);
            }
        } else {
            context.unregisterReceiver(receiver);
        }
        isReceiverRegistered = false;
    }

    public interface NetworkConnectedListener {
        void onNetworkConnectionChanged(boolean isConnected, boolean hasWifiOrEthernet);
    }
}
