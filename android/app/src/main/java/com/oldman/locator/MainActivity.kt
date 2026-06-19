package com.oldman.locator

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvCoordinates: TextView
    private lateinit var etPassword: EditText

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startLocating()
        } else {
            Toast.makeText(this, "需要定位权限才能工作", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvCoordinates = findViewById(R.id.tvCoordinates)
        etPassword = findViewById(R.id.etPassword)

        tvDeviceId.text = "设备ID: ${obtainDeviceId()}"

        // Restore saved password
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val savedPwd = prefs.getString("monitor_password", "")
        etPassword.setText(savedPwd)

        // Check if monitoring is active and update UI
        val isActive = prefs.getBoolean("monitoring_active", false)
        if (isActive) {
            btnToggle.text = "停止监控"
            tvStatus.text = "状态: 运行中"
            etPassword.isEnabled = false
        }

        btnToggle.setOnClickListener {
            if (isServiceRunning()) {
                stopLocating()
            } else {
                val pwd = etPassword.text.toString().trim()
                if (pwd.isEmpty()) {
                    Toast.makeText(this, "请设置监控密码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // Save password
                prefs.edit().putString("monitor_password", pwd).apply()
                tvDeviceId.text = "设备ID: ${obtainDeviceId()}  密码: $pwd"
                checkPermissions()
            }
        }

        // Check Xiaomi permissions on first launch
        checkAndGuideMiuiPermissions()
    }

    private fun obtainDeviceId(): String {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: "device_${(1000..9999).random()}".also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private fun isServiceRunning(): Boolean {
        // Use heartbeat-based detection instead of deprecated getRunningServices
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val heartbeat = prefs.getLong("service_heartbeat", 0)
        return heartbeat > 0 && System.currentTimeMillis() - heartbeat < 3 * 60 * 1000L
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startLocating()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startLocating() {
        val pwd = etPassword.text.toString().trim()
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_START
            putExtra("password", pwd)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // Set monitoring_active only after successful service start
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putBoolean("monitoring_active", true).apply()
            btnToggle.text = "停止监控"
            tvStatus.text = "状态: 运行中"
            etPassword.isEnabled = false

            // Request battery optimization exemption
            requestBatteryOptimization()

            // Schedule AlarmManager watchdog
            scheduleWatchdog()
        } catch (e: Exception) {
            Toast.makeText(this, "启动服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            getSharedPreferences("prefs", MODE_PRIVATE).edit()
                .putBoolean("monitoring_active", false).apply()
        }
    }

    private fun stopLocating() {
        getSharedPreferences("prefs", MODE_PRIVATE).edit()
            .putBoolean("monitoring_active", false).apply()
        val intent = Intent(this, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Service may already be dead
        }
        btnToggle.text = "开始监控"
        tvStatus.text = "状态: 已停止"
        tvCoordinates.text = ""
        etPassword.isEnabled = true

        // Cancel AlarmManager watchdog
        cancelWatchdog()
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // User denied or setting not available
                }
            }
        }
    }

    private fun scheduleWatchdog() {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WatchdogAlarmReceiver::class.java).apply {
                action = WatchdogAlarmReceiver.ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = 15 * 60 * 1000L
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + interval,
                interval,
                pendingIntent
            )
        } catch (e: Exception) {
            // Alarm scheduling failed, watchdog won't work
        }
    }

    private fun cancelWatchdog() {
        try {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WatchdogAlarmReceiver::class.java).apply {
                action = WatchdogAlarmReceiver.ACTION_WATCHDOG
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            // Cancel failed
        }
    }

    private fun checkAndGuideMiuiPermissions() {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: return
        if (manufacturer != "xiaomi" && manufacturer != "redmi") return

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (prefs.getBoolean("miui_guide_shown", false)) return

        prefs.edit().putBoolean("miui_guide_shown", true).apply()

        AlertDialog.Builder(this)
            .setTitle("小米手机保活设置")
            .setMessage(
                "为了保证后台定位稳定运行，请开启以下权限：\n\n" +
                "1. 自启动权限\n" +
                "   设置 → 应用设置 → 应用管理 → 右上角 ⋮ → 权限 → 自启动\n\n" +
                "2. 电池无限制\n" +
                "   设置 → 应用设置 → 应用管理 → 本应用 → 电池 → 无限制\n\n" +
                "3. 关闭神隐模式\n" +
                "   设置 → 电池与性能 → 后台耗电管理 → 本应用 → 无限制"
            )
            .setPositiveButton("前往设置") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // fallback
                }
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }
}
