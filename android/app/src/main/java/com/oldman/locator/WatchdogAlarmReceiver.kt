package com.oldman.locator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WatchdogAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG) return
        Log.d(TAG, "Watchdog alarm triggered")
        ServiceWatchdog.checkAndRestart(context)
    }

    companion object {
        private const val TAG = "WatchdogAlarm"
        const val ACTION_WATCHDOG = "com.oldman.locator.WATCHDOG"
    }
}
