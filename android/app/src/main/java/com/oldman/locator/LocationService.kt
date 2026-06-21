package com.oldman.locator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.Executors

class LocationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var mqttManager: MqttManager? = null
    private var locationManager: LocationManager? = null
    private var deviceId = "unknown"
    private var password = ""
    @Volatile private var isRunning = false
    private var reportInterval = 5 * 60 * 1000L

    private var mqttStatus = "连接中..."
    private var gpsStatus = "等待定位..."
    private var lastLat = ""
    private var lastLng = ""
    private var lastPublishedLat = 0.0
    private var lastPublishedLng = 0.0
    private val stationaryThreshold = 0.0001
    private var useCacheCount = 0
    private val maxCacheUses = 2

    private val reportTask = object : Runnable {
        override fun run() {
            reportLocation()
            handler.postDelayed(this, reportInterval)
        }
    }

    private val healthCheckTask = object : Runnable {
        override fun run() {
            if (isRunning) {
                val connected = mqttManager?.isConnected == true
                mqttStatus = if (connected) "MQTT已连接" else "MQTT断线"
                updateNotification()
                // Write heartbeat
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putLong("service_heartbeat", System.currentTimeMillis()).apply()
            }
            handler.postDelayed(this, 60000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        deviceId = prefs.getString("device_id", null) ?: "device_${(1000..9999).random()}"
        prefs.edit().putString("device_id", deviceId).apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                password = intent.getStringExtra("password") ?: ""
                if (password.isNotEmpty()) {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit()
                        .putString("monitor_password", password).apply()
                }
                start()
            }
            ACTION_STOP -> stopService()
            null -> {
                // OS restarted us after being killed (START_STICKY). Restore from saved state.
                val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
                password = prefs.getString("monitor_password", "") ?: ""
                if (password.isNotEmpty()) {
                    Log.d(TAG, "OS restart, restoring with saved password")
                    start()
                } else {
                    Log.w(TAG, "OS restart but no saved password, stopping")
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun start() {
        if (isRunning) return
        if (password.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        isRunning = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        // Write initial heartbeat
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putLong("service_heartbeat", System.currentTimeMillis()).apply()

        // Schedule watchdog alarm
        scheduleWatchdog()

        // Check location enabled
        if (!isLocationEnabled()) {
            gpsStatus = "请开启位置服务"
            updateNotification()
        }

        mqttManager = MqttManager(clientId = deviceId, context = this).apply {
            onConnected = {
                mqttStatus = "MQTT已连接"
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean("mqtt_connected", true)
                    .putLong("mqtt_disconnected_at", 0).apply()
                updateNotification()
                flushQueue("oldman/pwd/$password/location")
            }
            onConnectFailed = { err ->
                mqttStatus = "MQTT失败: $err"
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean("mqtt_connected", false)
                    .putLong("mqtt_disconnected_at", System.currentTimeMillis()).apply()
                updateNotification()
            }
            onConnectionLost = {
                mqttStatus = "MQTT断线"
                getSharedPreferences("prefs", MODE_PRIVATE).edit()
                    .putBoolean("mqtt_connected", false)
                    .putLong("mqtt_disconnected_at", System.currentTimeMillis()).apply()
                updateNotification()
            }
        }
        connect()

        reportLocation()
        handler.postDelayed(reportTask, reportInterval)
        handler.postDelayed(healthCheckTask, 60000)

        Log.d(TAG, "Service started, deviceId=$deviceId")
    }

    private fun connect() {
        mqttStatus = "MQTT重连中..."
        updateNotification()
        executor.execute {
            mqttManager?.connect()
        }
    }

    private fun stopService() {
        isRunning = false
        handler.removeCallbacks(reportTask)
        handler.removeCallbacks(healthCheckTask)
        mqttManager?.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Service stopped")
    }

    private fun scheduleWatchdog() {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(this, WatchdogAlarmReceiver::class.java).apply {
                action = WatchdogAlarmReceiver.ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = 15 * 60 * 1000L
            alarmManager.setRepeating(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + interval,
                interval,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule watchdog: ${e.message}")
        }
    }

    private fun isLocationEnabled(): Boolean {
        return locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                || locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }

    private fun reportLocation() {
        if (!isRunning) return

        if (!isLocationEnabled()) {
            gpsStatus = "位置服务已关闭"
            updateNotification()
            return
        }

        gpsStatus = "获取定位中..."
        updateNotification()

        val forceFresh = useCacheCount >= maxCacheUses

        // 1) Try last-known from Network (fast, works indoors)
        if (!forceFresh) {
            var loc = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null && isRecent(loc)) {
                useCacheCount++
                onLocationReceived(loc)
                return
            }

            // 2) Try last-known from GPS
            loc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null && isRecent(loc)) {
                useCacheCount++
                onLocationReceived(loc)
                return
            }
        }

        // 3) No recent/acceptable cache, request a fresh fix
        useCacheCount = 0
        requestFreshLocation()
    }

    private fun isRecent(loc: Location): Boolean {
        return System.currentTimeMillis() - loc.time < 600000 // 10 minutes
    }

    private fun requestFreshLocation() {
        val timeout = 15000L
        var cleanedUp = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (cleanedUp) return
                cleanedUp = true
                cleanup(arrayOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER), this)
                onLocationReceived(location)
            }
            override fun onProviderDisabled(provider: String) {
                if (!cleanedUp) {
                    cleanedUp = true
                    cleanup(arrayOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER), this)
                    gpsStatus = "定位provider已禁用"
                    updateNotification()
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
            try {
                locationManager?.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.e(TAG, "Network requestSingleUpdate error: ${e.message}")
            }
        }
        if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
            try {
                locationManager?.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                Log.e(TAG, "GPS requestSingleUpdate error: ${e.message}")
            }
        }

        handler.postDelayed({
            if (!cleanedUp) {
                cleanedUp = true
                cleanup(arrayOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER), listener)
                // Fallback: use best available cached location (any age) for heartbeat
                var fallback = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (fallback == null) {
                    fallback = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                }
                if (fallback != null) {
                    onLocationReceived(fallback)
                } else {
                    gpsStatus = "定位超时(无缓存)"
                }
                updateNotification()
            }
        }, timeout)
    }

    private fun cleanup(providers: Array<String>, listener: LocationListener) {
        providers.forEach {
            try { locationManager?.removeUpdates(listener) } catch (_: Exception) {}
        }
    }

    private fun onLocationReceived(location: Location) {
        lastLat = "%.4f".format(location.latitude)
        lastLng = "%.4f".format(location.longitude)

        val moved = lastPublishedLat == 0.0 ||
            Math.abs(location.latitude - lastPublishedLat) >= stationaryThreshold ||
            Math.abs(location.longitude - lastPublishedLng) >= stationaryThreshold

        if (moved) {
            reportInterval = 2 * 60 * 1000L
            gpsStatus = "移动中(2min) $lastLat, $lastLng"
        } else {
            reportInterval = 5 * 60 * 1000L
            gpsStatus = "已定位(5min) $lastLat, $lastLng"
        }
        lastPublishedLat = location.latitude
        lastPublishedLng = location.longitude

        updateNotification()
        publishLocation(location)
    }

    private fun publishLocation(location: Location) {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch(_: Exception) { "?" }
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("time", System.currentTimeMillis())
            put("battery", getBatteryLevel())
            put("version", ver ?: "?")
        }.toString()

        mqttManager?.publish("oldman/pwd/$password/location", json)
    }

    private fun getBatteryLevel(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra("level", 0) ?: 0
            val scale = intent?.getIntExtra("scale", 100) ?: 100
            level * 100 / scale
        } catch (e: Exception) {
            -1
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "location_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "位置监控", NotificationManager.IMPORTANCE_DEFAULT
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = "$mqttStatus | $gpsStatus"

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("老人定位 [pwd:$password]")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(reportTask)
        handler.removeCallbacks(healthCheckTask)
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putBoolean("mqtt_connected", false).apply()
        mqttManager?.disconnect()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.oldman.locator.START"
        const val ACTION_STOP = "com.oldman.locator.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "LocationService"
    }
}
