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

import static android.content.Intent.ACTION_BATTERY_CHANGED;
import static android.os.BatteryManager.EXTRA_LEVEL;
import static android.os.BatteryManager.EXTRA_PLUGGED;
import static android.os.BatteryManager.EXTRA_SCALE;

/** Keep track of battery level events */
public class BatteryLevelReceiver extends SpecificReceiver {
    private final BatteryLevelListener listener;
    private float level;
    private boolean isPlugged;

    public BatteryLevelReceiver(@NonNull Context context, BatteryLevelListener listener) {
        super(context);
        this.listener = listener;
        this.level = 1.0f;
        this.isPlugged = true;
    }

    @Override
    protected String getAction() {
        return ACTION_BATTERY_CHANGED;
    }

    @Override
    protected void onSpecificReceive(Intent intent) {
        int level = intent.getIntExtra(EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(EXTRA_SCALE, -1);

        this.level = level / (float)scale;
        this.isPlugged = intent.getIntExtra(EXTRA_PLUGGED, 0) > 0;

        if (listener != null) {
            listener.onBatteryLevelChanged(this.level, this.isPlugged);
        }
    }

    public float getLevel() {
        return level;
    }

    public boolean isPlugged() {
        return isPlugged;
    }

    public boolean hasMinimumLevel(float minLevel) {
        return level >= minLevel || isPlugged;
    }

    public interface BatteryLevelListener {
        void onBatteryLevelChanged(float level, boolean isPlugged);
    }
}
