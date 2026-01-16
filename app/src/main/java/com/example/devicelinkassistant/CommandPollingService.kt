package com.example.devicelinkassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.net.URL

class CommandPollingService : Service() {

    private val tag = "CommandPolling"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // IMPORTANT: your PC LAN IP
    private val baseUrl = "http://192.168.8.108:8080"
    private val deviceId = "phone1"

    override fun onCreate() {
        super.onCreate()

        startForeground(2001, buildNotification())

        Log.d(tag, "Service started. Polling $baseUrl for deviceId=$deviceId")

        scope.launch {
            while (isActive) {
                try {
                    val url = "$baseUrl/commands?deviceId=$deviceId"
                    val response = URL(url).readText().trim()

                    if (response.isNotBlank() && response != "{}") {
                        Log.d(tag, "Received command: $response")
                        // For now: log only. Next step we will ACK and trigger Spotify.
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Polling error", e)
                }

                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        Log.d(tag, "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "dla_polling"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                "DeviceLink Polling",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE
            else 0
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("DeviceLinkAssistant running")
            .setContentText("Polling for commands")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .build()
    }
}
