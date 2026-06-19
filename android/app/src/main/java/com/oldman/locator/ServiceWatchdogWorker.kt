package com.oldman.locator

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ServiceWatchdog {

    private const val TAG = "ServiceWatchdog"
    private const val HEARTBEAT_THRESHOLD = 3 * 60 * 1000L // 3 minutes
    private const val MQTT_DISCONNECT_THRESHOLD = 5 * 60 * 1000L // 5 minutes

    fun checkAndRestart(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val active = prefs.getBoolean("monitoring_active", false)
        if (!active) {
            Log.d(TAG, "Monitoring not active, skip")
            return
        }

        val heartbeat = prefs.getLong("service_heartbeat", 0)
        val serviceAlive = heartbeat > 0 && System.currentTimeMillis() - heartbeat < HEARTBEAT_THRESHOLD

        if (serviceAlive) {
            // Service is alive, check MQTT
            val mqttConnected = prefs.getBoolean("mqtt_connected", false)
            val mqttDisconnectedAt = prefs.getLong("mqtt_disconnected_at", 0)
            if (mqttConnected) {
                Log.d(TAG, "Service & MQTT OK, skip")
                return
            }
            // Service alive but MQTT dead for too long → force restart
            if (mqttDisconnectedAt > 0 && System.currentTimeMillis() - mqttDisconnectedAt > MQTT_DISCONNECT_THRESHOLD) {
                Log.w(TAG, "MQTT dead for ${((System.currentTimeMillis() - mqttDisconnectedAt) / 60000)}min, force restarting service")
                restartService(context)
            } else {
                Log.d(TAG, "Service alive, MQTT disconnected but within threshold, skip")
            }
            return
        }

        Log.w(TAG, "Service not running (heartbeat stale: ${if (heartbeat > 0) "${((System.currentTimeMillis() - heartbeat) / 60000)}min ago" else "never"})! Restarting...")
        restartService(context)
    }

    private fun restartService(context: Context) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val pwd = prefs.getString("monitor_password", "") ?: ""
        if (pwd.isEmpty()) {
            Log.w(TAG, "No password saved, skip")
            return
        }
        val intent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("password", pwd)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Service restart requested")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart service: ${e.message}")
        }
    }
}
