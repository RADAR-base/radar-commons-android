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

import static android.content.Intent.ACTION_BATTERY_CHANGED;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_SCALE;

/** Keep track of battery level events. */
public class BatteryLevelReceiver extends BroadcastReceiver implements SpecificReceiver {
    protected final Context context;
    private final BatteryLevelListener listener;
    private float level;
    private boolean isPlugged;

    public BatteryLevelReceiver(@NonNull Context context, BatteryLevelListener listener) {
        this.context = Objects.requireNonNull(context);
        this.listener = listener;
        this.level = 1.0f;
        this.isPlugged = true;
    }

    /** Latest battery level in range [0, 1]. */
    public float getLevel() {
        return level;
    }

    /** Latest value whether the device is plugged into a charger. */
    public boolean isPlugged() {
        return isPlugged;
    }

    /** Returns true if the current battery level is at least the given level, or that the device is
     * currently plugged in. Returns false otherwise. */
    public boolean hasMinimumLevel(float minLevel) {
        return level >= minLevel || isPlugged;
    }

    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Objects.equals(intent.getAction(), ACTION_BATTERY_CHANGED)) {
            return;
        }
        int level1 = intent.getIntExtra(EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(EXTRA_SCALE, -1);

        this.level = level1 / (float)scale;
        this.isPlugged = intent.getIntExtra(EXTRA_PLUGGED, 0) > 0;

        if (listener != null) {
            listener.onBatteryLevelChanged(this.level, this.isPlugged);
        }
    }

    public void register() {
        Intent init = context.registerReceiver(this, new IntentFilter(ACTION_BATTERY_CHANGED));
        onReceive(context, init);
    }

    public void unregister() {
        context.unregisterReceiver(this);
    }

    public interface BatteryLevelListener {
        /**
         * Latest battery level reported by the system.
         * @param level battery level in range [0, 1]
         * @param isPlugged whether the device is plugged into a charger
         */
        void onBatteryLevelChanged(float level, boolean isPlugged);
    }
}
