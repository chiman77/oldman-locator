package com.oldman.locator

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val active = prefs.getBoolean("monitoring_active", false)
        if (!active) {
            Log.d(TAG, "Boot completed but monitoring not active, skip")
            return
        }

        val pwd = prefs.getString("monitor_password", "") ?: ""
        if (pwd.isEmpty()) {
            Log.w(TAG, "Boot completed but no password saved, skip")
            return
        }

        Log.d(TAG, "Boot completed, starting LocationService")
        try {
            val serviceIntent = Intent(context, LocationService::class.java).apply {
                action = LocationService.ACTION_START
                putExtra("password", pwd)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service from boot: ${e.message}")
            return
        }

        // Re-schedule watchdog alarm (alarms don't survive reboot)
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val alarmIntent = Intent(context, WatchdogAlarmReceiver::class.java).apply {
                action = WatchdogAlarmReceiver.ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = 15 * 60 * 1000L
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + interval,
                interval,
                pendingIntent
            )
            Log.d(TAG, "Watchdog alarm re-scheduled after boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog alarm: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
