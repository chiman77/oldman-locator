package com.oldman.locator

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray

class MqttManager(
    private val serverUri: String = SERVER_PRIMARY,
    private val clientId: String,
    private val context: Context? = null
) {
    private var client: MqttClient? = null
    @Volatile private var isConnecting = false
    @Volatile private var intentionallyClosing = false
    private val handler = Handler(Looper.getMainLooper())
    private val pendingQueue = mutableListOf<String>()
    private var serverIndex = 0

    private val serverList: List<String> = listOf(
        serverUri,
        SERVER_CN
    )

    var onConnectionLost: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onConnectFailed: ((String) -> Unit)? = null

    val isConnected: Boolean get() = client?.isConnected == true

    fun connect() {
        if (isConnecting) {
            Log.d(TAG, "Already connecting, skipping")
            return
        }
        isConnecting = true
        try {
            if (client?.isConnected == true) return
            val uri = serverList[serverIndex]
            val persistence = MemoryPersistence()
            intentionallyClosing = true
            try { client?.close() } catch (_: Exception) {}
            intentionallyClosing = false
            client = MqttClient(uri, clientId, persistence)
            client?.setCallback(object : org.eclipse.paho.client.mqttv3.MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    if (intentionallyClosing) return
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    onConnectionLost?.invoke()
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                connectionTimeout = 10
                keepAliveInterval = 60
                isCleanSession = true
            }
            client?.connect(options)
            serverIndex = 0
            onConnected?.invoke()
            Log.d(TAG, "Connected to $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed (server ${serverList[serverIndex]}): ${e.message}")
            serverIndex = (serverIndex + 1) % serverList.size
            onConnectFailed?.invoke(e.message ?: "unknown error")
        } finally {
            isConnecting = false
        }
    }

    fun publish(topic: String, payload: String, qos: Int = 1) {
        try {
            if (client?.isConnected != true) {
                queueMessage(payload)
                Log.w(TAG, "Not connected, queued message (${pendingQueue.size})")
                return
            }
            val message = MqttMessage(payload.toByteArray()).apply { this.qos = qos }
            client?.publish(topic, message)
            Log.d(TAG, "Published to $topic")
        } catch (e: Exception) {
            Log.e(TAG, "Publish failed: ${e.message}")
            queueMessage(payload)
        }
    }

    fun flushQueue(topic: String) {
        loadFromPrefs()
        if (pendingQueue.isEmpty()) return
        Log.d(TAG, "Flushing ${pendingQueue.size} queued messages")
        var delay = 0L
        while (pendingQueue.isNotEmpty()) {
            val payload: String
            synchronized(pendingQueue) {
                if (pendingQueue.isEmpty()) return
                payload = pendingQueue.removeAt(0)
            }
            saveToPrefs()
            val d = delay
            handler.postDelayed({
                try {
                    if (client?.isConnected == true) {
                        val message = MqttMessage(payload.toByteArray()).apply { this.qos = 1 }
                        client?.publish(topic, message)
                        Log.d(TAG, "Flushed message")
                    } else {
                        queueMessage(payload)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Flush failed: ${e.message}")
                    queueMessage(payload)
                }
            }, d)
            delay += 800
        }
    }

    fun disconnect() {
        isConnecting = false
        intentionallyClosing = true
        try {
            client?.disconnect()
            client?.close()
            client = null
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        } finally {
            intentionallyClosing = false
        }
    }

    private fun queueMessage(payload: String) {
        synchronized(pendingQueue) {
            if (pendingQueue.size >= MAX_QUEUE_SIZE) {
                pendingQueue.removeAt(0)
            }
            pendingQueue.add(payload)
            saveToPrefs()
        }
    }

    private fun saveToPrefs() {
        context ?: return
        try {
            val arr = JSONArray()
            synchronized(pendingQueue) {
                pendingQueue.forEach { arr.put(it) }
            }
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .edit().putString("pending_reports", arr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Save queue error: ${e.message}")
        }
    }

    private fun loadFromPrefs() {
        context ?: return
        try {
            synchronized(pendingQueue) {
                if (pendingQueue.isNotEmpty()) return
                val raw = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    .getString("pending_reports", null) ?: return
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    pendingQueue.add(arr.getString(i))
                }
                Log.d(TAG, "Loaded ${pendingQueue.size} queued messages from prefs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load queue error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MqttManager"
        private const val MAX_QUEUE_SIZE = 3
        private const val SERVER_PRIMARY = "tcp://broker.emqx.io:1883"
        private const val SERVER_CN = "tcp://broker-cn.emqx.io:1883"
    }
}
