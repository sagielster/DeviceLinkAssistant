package com.example.devicelinkassistant

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.Settings
import android.provider.Settings.Secure
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeviceLinkFcmService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "DeviceLinkFcmService"

        // Must match Alexa's DEVICE_ID
        private const val DEVICE_ID = "phone1"

        // From your deploy output
        private const val REGISTER_URL =
            "https://us-central1-devicelink-73e77.cloudfunctions.net/register"

        // Spotify
        private const val SPOTIFY_PKG = "com.spotify.music"

        /**
         * OPTIONAL: If name-matching is flaky (common for Beats), set these to the
         * MAC addresses shown in Android Bluetooth settings for each device.
         * Leave blank to use name keyword matching.
         */
        private const val BEATS_MAC = "" // e.g. "20:FA:85:8F:DE:9C"
        private const val ONN_MAC = ""   // e.g. "A8:91:6D:56:91:C9"
        private const val ECHO_MAC = ""  // optional
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "FCM token refreshed (len=${token.length})")
        registerTokenToBackend(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val action = (data["action"] ?: data["command"]).orEmpty().trim()

        Log.i(TAG, "FCM message received: data=$data")
        if (action.isBlank()) return

        Log.i(TAG, "Action command: $action")

        when (action) {
            "OPEN_SPOTIFY" -> {
                val ok = launchFirstInstalled(SPOTIFY_PKG)
                if (!ok) Log.e(TAG, "Spotify not installed.")
            }

            "PLAY_RANDOM" -> {
                // Audible proof
                try {
                    ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                        .startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to beep", e)
                }

                requestMusicAudioFocus()

                val launched = launchFirstInstalled(SPOTIFY_PKG)
                if (!launched) {
                    Log.e(TAG, "Spotify not installed; cannot play.")
                    return
                }

                // Best-effort: try MediaSession play (if notif access enabled), otherwise media key.
                Thread {
                    try { Thread.sleep(600) } catch (_: Throwable) {}
                    val ok = trySpotifyTransportPlayWithRetry(totalWaitMs = 4000L)
                    if (!ok) {
                        Log.w(TAG, "PLAY_RANDOM: transport play failed; falling back to MEDIA_PLAY")
                        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                    }
                }.start()
            }

            "PAUSE", "STOP" -> {
                Log.i(TAG, "Pausing media via media key")
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }

            "BT_SWITCH" -> {
                val targetRaw = (data["target"] ?: "").trim()
                val target = normalizeBtTarget(targetRaw)

                if (target.isBlank()) {
                    Log.w(TAG, "BT_SWITCH missing/unknown target=$targetRaw")
                    return
                }

                Log.i(TAG, "BT_SWITCH target=$target")
                switchBluetoothOutputAsync(target)
            }

            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    // =========================
    // Media controls
    // =========================

    private fun sendMediaKey(keyCode: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            @Suppress("DEPRECATION")
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send media key $keyCode", e)
        }
    }

    private fun requestMusicAudioFocus() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).build()
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio focus request failed", e)
        }
    }

    private fun launchFirstInstalled(vararg packageNames: String): Boolean {
        for (pkg in packageNames) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) {
                    Log.i(TAG, "Launching package=$pkg")
                    startActivity(intent)
                    return true
                }
            } catch (_: Throwable) {
                // keep trying
            }
        }
        return false
    }

    // =========================
    // Notification listener / MediaSession (Spotify)
    // =========================

    private fun isNotificationListenerEnabled(): Boolean {
        return try {
            val enabled = Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
            enabled.contains(packageName)
        } catch (_: Throwable) {
            false
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to open notification listener settings", t)
        }
    }

    private fun getSpotifyControllerOrNull(): MediaController? {
        return try {
            val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(this, SpotifyNotificationListener::class.java)
            val sessions: List<MediaController> = msm.getActiveSessions(cn)
            sessions.firstOrNull { it.packageName == SPOTIFY_PKG }
        } catch (t: Throwable) {
            Log.e(TAG, "getSpotifyControllerOrNull failed", t)
            null
        }
    }

    private fun isSpotifyPlaying(c: MediaController): Boolean {
        val st = c.playbackState?.state ?: return false
        return st == PlaybackState.STATE_PLAYING ||
                st == PlaybackState.STATE_BUFFERING ||
                st == PlaybackState.STATE_CONNECTING
    }

    private fun trySpotifyTransportPlayOnce(): Boolean {
        if (!isNotificationListenerEnabled()) {
            Log.w(TAG, "Notification Access not enabled; cannot query active sessions.")
            return false
        }

        val spotify = getSpotifyControllerOrNull() ?: return false
        if (isSpotifyPlaying(spotify)) return true

        return try {
            spotify.transportControls.play()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "transportControls.play failed", t)
            false
        }
    }

    private fun trySpotifyTransportPlayWithRetry(totalWaitMs: Long): Boolean {
        val start = System.currentTimeMillis()
        var attempts = 0

        while (System.currentTimeMillis() - start < totalWaitMs) {
            attempts++
            if (trySpotifyTransportPlayOnce()) {
                // Nudge once more after a short delay; first play() can get dropped on route change
                try { Thread.sleep(350) } catch (_: Throwable) {}
                trySpotifyTransportPlayOnce()
                return true
            }
            try { Thread.sleep(250) } catch (_: Throwable) {}
        }

        Log.w(TAG, "Spotify play retry timed out after ${totalWaitMs}ms attempts=$attempts")
        return false
    }

    private fun settleDelayMsForTarget(target: String): Long {
        return when (target) {
            "echo" -> 2600L
            "onn" -> 1400L
            "beats" -> 1200L
            else -> 1600L
        }
    }

    private fun connectTimeoutMsForTarget(target: String): Long {
        return when (target) {
            "echo" -> 10000L
            "onn" -> 6000L
            "beats" -> 5000L
            else -> 7000L
        }
    }

    private fun resumeAfterBtSwitch(target: String) {
        try {
            requestMusicAudioFocus()
            try { Thread.sleep(settleDelayMsForTarget(target)) } catch (_: Throwable) {}

            launchFirstInstalled(SPOTIFY_PKG)

            try { Thread.sleep(700) } catch (_: Throwable) {}
            val ok = trySpotifyTransportPlayWithRetry(totalWaitMs = 6500L)

            if (!ok) {
                Log.w(TAG, "Transport play failed; falling back to MEDIA_PLAY spam.")
                if (!isNotificationListenerEnabled()) openNotificationListenerSettings()
                try { Thread.sleep(500) } catch (_: Throwable) {}
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                try { Thread.sleep(900) } catch (_: Throwable) {}
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                try { Thread.sleep(1400) } catch (_: Throwable) {}
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "resumeAfterBtSwitch failed", t)
        }
    }

    // =========================
    // BT switching
    // =========================

    private fun normalizeBtTarget(raw: String): String {
        val s = raw.trim().lowercase()
        if (s.isBlank()) return ""

        if (s.contains("echo") || s.contains("amazon") || s.contains("show") || s.contains("dot")) return "echo"
        if (s.contains("onn") || s.contains("o n n") || s.contains("karaoke") || s.contains("machine")) return "onn"
        if (s.contains("beats") || s.contains("headphone") || s.contains("headset")) return "beats"

        if (s == "echo" || s == "onn" || s == "beats") return s
        return ""
    }

    private fun hasBtConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun safeConnected(a2dp: BluetoothA2dp): List<BluetoothDevice> {
        return try { a2dp.connectedDevices?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
    }

    private fun waitForA2dpConnected(a2dp: BluetoothA2dp, target: BluetoothDevice, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val connected = safeConnected(a2dp)
            if (connected.any { it.address == target.address }) return true
            try { Thread.sleep(200) } catch (_: Throwable) {}
        }
        return false
    }

    private fun switchBluetoothOutputAsync(target: String) {
        Thread {
            try {
                if (!hasBtConnectPermission()) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT permission; cannot switch BT output.")
                    openBluetoothSettings()
                    return@Thread
                }

                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter
                if (adapter == null || !adapter.isEnabled) {
                    Log.e(TAG, "Bluetooth is off/unavailable.")
                    openBluetoothSettings()
                    return@Thread
                }

                val bonded = try { adapter.bondedDevices?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
                val targetDevice = findTargetDevice(bonded, target)
                if (targetDevice == null) {
                    Log.e(TAG, "No paired BT device found for target=$target")
                    openBluetoothSettings()
                    return@Thread
                }

                val a2dp = getA2dpProxyBlocking(adapter, timeoutMs = 3500)
                if (a2dp == null) {
                    Log.e(TAG, "A2DP proxy not available yet.")
                    openBluetoothSettings()
                    return@Thread
                }

                val before = safeConnected(a2dp)
                Log.i(TAG, "A2DP connected before: ${before.joinToString { "${it.name}/${it.address}" }}")

                // FAST SWITCH POLICY: disconnect all A2DP first, then connect target
                for (d in before) {
                    Log.i(TAG, "Disconnecting A2DP device: ${d.name}/${d.address}")
                    tryDisconnectA2dp(a2dp, d)
                }
                try { Thread.sleep(500) } catch (_: Throwable) {}

                val connectOk = tryConnectA2dp(a2dp, targetDevice)
                Log.i(TAG, "A2DP connect invoked target=${targetDevice.name} ok=$connectOk")

                val timeout = connectTimeoutMsForTarget(target)
                val becameConnected = waitForA2dpConnected(a2dp, targetDevice, timeoutMs = timeout)
                Log.i(TAG, "Target became A2DP-connected=$becameConnected timeoutMs=$timeout")

                val after = safeConnected(a2dp)
                Log.i(TAG, "A2DP connected after: ${after.joinToString { "${it.name}/${it.address}" }}")

                resumeAfterBtSwitch(target)

            } catch (t: Throwable) {
                Log.e(TAG, "BT switch failed", t)
                openBluetoothSettings()
            }
        }.start()
    }

    private fun findTargetDevice(bonded: List<BluetoothDevice>, target: String): BluetoothDevice? {
        val mac = when (target) {
            "beats" -> BEATS_MAC
            "onn" -> ONN_MAC
            "echo" -> ECHO_MAC
            else -> ""
        }.trim()

        if (mac.isNotBlank()) {
            bonded.firstOrNull { it.address.equals(mac, ignoreCase = true) }?.let { return it }
        }

        val kws = when (target) {
            "echo" -> listOf("echo", "amazon", "alexa", "show", "dot")
            "onn" -> listOf("onn", "karaoke", "party", "speaker", "mic")
            "beats" -> listOf("beats", "studio", "solo", "powerbeats", "fit pro")
            else -> emptyList()
        }

        return bonded.firstOrNull { d ->
            val name = (d.name ?: "").lowercase()
            kws.any { kw -> name.contains(kw) }
        }
    }

    private fun getA2dpProxyBlocking(
        adapter: android.bluetooth.BluetoothAdapter,
        timeoutMs: Long
    ): BluetoothA2dp? {
        var proxy: BluetoothA2dp? = null
        val latch = CountDownLatch(1)

        val ok = try {
            adapter.getProfileProxy(
                this,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            proxy = p as? BluetoothA2dp
                        }
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        // ignore
                    }
                },
                BluetoothProfile.A2DP
            )
        } catch (t: Throwable) {
            Log.e(TAG, "getProfileProxy(A2DP) failed", t)
            false
        }

        if (!ok) return null

        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            proxy
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun tryConnectA2dp(a2dp: BluetoothA2dp, device: BluetoothDevice): Boolean {
        return try {
            val m = a2dp.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val res = m.invoke(a2dp, device)
            when (res) {
                is Boolean -> res
                else -> true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "A2DP connect reflection failed", t)
            false
        }
    }

    private fun tryDisconnectA2dp(a2dp: BluetoothA2dp, device: BluetoothDevice): Boolean {
        return try {
            val m = a2dp.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            val res = m.invoke(a2dp, device)
            when (res) {
                is Boolean -> res
                else -> true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "A2DP disconnect reflection failed for ${device.name}", t)
            false
        }
    }

    private fun openBluetoothSettings() {
        try {
            val i = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (_: Throwable) {
            // ignore
        }
    }

    // =========================
    // Registration
    // =========================

    private fun registerTokenToBackend(token: String) {
        Thread {
            try {
                val conn = (URL(REGISTER_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }

                val body = """{"deviceId":"$DEVICE_ID","token":"$token","fcmToken":"$token"}"""
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

                val code = conn.responseCode
                Log.i(TAG, "Register responseCode=$code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register token", e)
            }
        }.start()
    }
}
