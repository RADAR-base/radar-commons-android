package org.radarbase.passive.google.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransitionResult

class ActivityTransitionReceiver(private val googleActivityManager: GoogleActivityManager) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        intent ?: return
        if (ActivityTransitionResult.hasResult(intent)) googleActivityManager.sendActivityTransitionUpdates(intent)
    }
}