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
import android.support.annotation.NonNull;

import java.util.Objects;

public abstract class SpecificReceiver extends BroadcastReceiver {
    protected final Context context;

    public SpecificReceiver(@NonNull Context context) {
        Objects.requireNonNull(context);
        this.context = context;
    }

    protected abstract String getAction();
    protected abstract void onSpecificReceive(@NonNull Intent intent);

    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Objects.equals(intent.getAction(), getAction())) {
            return;
        }
        onSpecificReceive(intent);
    }

    public void register() {
        Intent init = context.registerReceiver(this, new IntentFilter(getAction()));
        onReceive(context, init);
    }

    public void unregister() {
        context.unregisterReceiver(this);
    }
}
