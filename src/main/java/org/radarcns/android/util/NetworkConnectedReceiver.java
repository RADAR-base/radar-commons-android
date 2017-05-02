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
import android.support.annotation.NonNull;

import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.EXTRA_NO_CONNECTIVITY;

public class NetworkConnectedReceiver extends SpecificReceiver {
    private final NetworkConnectedListener listener;
    private boolean isConnected;

    public NetworkConnectedReceiver(@NonNull Context context, NetworkConnectedListener listener) {
        super(context);
        this.listener = listener;
        this.isConnected = true;
    }

    @Override
    protected void onSpecificReceive(Intent intent) {
        this.isConnected = !intent.getBooleanExtra(EXTRA_NO_CONNECTIVITY, false);
        if (listener != null) {
            listener.onNetworkConnectionChanged(this.isConnected);
        }
    }

    @Override
    protected String getAction() {
        return CONNECTIVITY_ACTION;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public interface NetworkConnectedListener {
        void onNetworkConnectionChanged(boolean isConnected);
    }
}
