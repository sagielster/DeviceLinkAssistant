package com.example.devicelinkassistant

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Coach pipeline:
 * 1) OpenAI (vision) returns a short instruction: "Tap Open"
 * 2) Gemini (vision) returns normalized bbox for that instruction
 * 3) Overlay circles bbox center and stays stationary until next screen change
 *
 * Fast heuristic stays in ImageReader.onImageAvailable.
 */
class ScreenCoachService : Service() {

    companion object {
        private const val TAG = "ScreenCoachService"

        private const val NOTIF_CHANNEL_ID = "dla_coach"
        private const val NOTIF_ID = 1007

        private const val MIN_ANALYZE_GAP_MS = 350L
        private const val FAILSAFE_REANALYZE_MS = 6000L
        private const val API_COOLDOWN_MS = 900L

        // When we're "Locating", don't re-roll OpenAI—retry Gemini on the same instruction briefly.
        private const val LOCATING_HOLD_MS = 2500L

        private const val PREFS_COACH = "dla_coach_prefs"
        private const val PREF_OPENAI_API_KEY = "openai_api_key"
        private const val PREF_GEMINI_API_KEY = "gemini_api_key"
        private const val PREF_GEMINI_MODEL = "gemini_model"

        private const val SIG_W = 32
        private const val SIG_H = 32
        private const val SIG_CHANGE_THRESHOLD = 900_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_COACH, MODE_PRIVATE) }
    private val openAi = OpenAiStepCoach()

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    private var screenW: Int = 0
    private var screenH: Int = 0
    private var captureW: Int = 0
    private var captureH: Int = 0

    private val frameLock = Any()
    private var lastFrame: Bitmap? = null

    private var lastSignature: Long = 0L
    private var lastSignatureAtMs: Long = 0L
    private val inFlight = AtomicBoolean(false)
    private var didInitialAnalyze = false
    private var lastApiAtMs: Long = 0L
    private var lastLockedAtMs: Long = 0L

    // Locating mode: keep one instruction stable for a short time to avoid cycling ideas.
    private var locatingInstruction: String? = null
    private var locatingUntilMs: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var overlay: CoachPointerOverlay? = null
    private var statusOverlay: TextView? = null
    private var statusLp: WindowManager.LayoutParams? = null

    private var lastStatus: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Coach mode is running"))
        ScreenCoachState.setRunning(true)
        setStatus("Coach started.")
        CoachBus.update(CoachState.StartingCapture)
        initOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        val resultCode = intent.getIntExtra(
            MainActivity.EXTRA_PROJECTION_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(MainActivity.EXTRA_PROJECTION_DATA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(MainActivity.EXTRA_PROJECTION_DATA_INTENT)
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            setStatus("Screen share canceled.")
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, data)
        return START_STICKY
    }

    override fun onDestroy() {
        stopProjection()
        removeOverlay()
        ScreenCoachState.setRunning(false)
        ScreenCoachState.clear()
        CoachBus.update(CoachState.Idle)
        super.onDestroy()
    }

    private fun expectedAppName(): String =
        prefs.getString(MainActivity.KEY_COACH_EXPECTED_APP_NAME, "")?.trim().orEmpty()

    private fun expectedAppQuery(): String =
        prefs.getString(MainActivity.KEY_COACH_EXPECTED_APP_QUERY, "")?.trim().orEmpty()

    private fun selectedDevice(): String =
        prefs.getString(MainActivity.KEY_COACH_SELECTED_DEVICE, "")?.trim().orEmpty()

    private fun openAiKey(): String =
        prefs.getString(PREF_OPENAI_API_KEY, "")?.trim().orEmpty()

    private fun geminiKey(): String =
        prefs.getString(PREF_GEMINI_API_KEY, "")?.trim().orEmpty()

    private fun geminiModel(): String =
        prefs.getString(PREF_GEMINI_MODEL, "gemini-2.0-flash")?.trim().orEmpty()

    private fun setStatus(s: String) {
        if (s == lastStatus) return
        lastStatus = s
        ScreenCoachState.setHint(s)
        updateStatusOverlay(s)
    }

    private fun dp(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun initOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            setStatus("Overlay permission missing.")
            CoachBus.update(CoachState.Error("overlay permission missing"))
            return
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay = CoachPointerOverlay(this)
        statusOverlay = TextView(this).apply {
            text = "Coach: starting…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }

        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val pointerSizePx = dp(72f)
        val lp = WindowManager.LayoutParams(
            pointerSizePx,
            pointerSizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            wm?.addView(overlay, lp)
            overlay?.hide()

            statusLp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                x = 0
                y = 0
            }
            wm?.addView(statusOverlay, statusLp)
        } catch (_: Throwable) {
            setStatus("Overlay add failed.")
        }
    }

    private fun updateStatusOverlay(s: String) {
        try {
            statusOverlay?.text = "Coach: " + s.take(80)
            val v = statusOverlay
            val lp = statusLp
            if (v != null && lp != null) wm?.updateViewLayout(v, lp)
        } catch (_: Throwable) {}
    }

    private fun removeOverlay() {
        try { wm?.removeViewImmediate(overlay) } catch (_: Throwable) {}
        try { wm?.removeViewImmediate(statusOverlay) } catch (_: Throwable) {}
        overlay = null
        statusOverlay = null
        statusLp = null
        wm = null
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        stopProjection()

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try {
            pm.getMediaProjection(resultCode, data)
        } catch (_: Throwable) {
            null
        }

        if (projection == null) {
            setStatus("Could not start screen capture.")
            stopSelf()
            return
        }

        val metrics = applicationContext.resources.displayMetrics
        screenW = metrics.widthPixels.coerceAtLeast(1)
        screenH = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi.coerceAtLeast(1)

        // Preserve aspect ratio while downscaling to reduce coordinate drift.
        captureW = (screenW / 2).coerceAtLeast(360)
        captureH = ((captureW.toLong() * screenH.toLong()) / screenW.toLong()).toInt().coerceAtLeast(360)
        // Clamp to at most the original size.
        captureW = captureW.coerceAtMost(screenW)
        captureH = captureH.coerceAtMost(screenH)

        CoachBus.update(CoachState.Scanning)
        setStatus("Scanning…")

        reader = try {
            ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2)
        } catch (t: Throwable) {
            setStatus("Screen capture init failed: ${t.message}")
            stopSelf()
            return
        }

        virtualDisplay = try {
            projection!!.createVirtualDisplay(
                "dla-coach",
                captureW,
                captureH,
                density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                null
            )
        } catch (t: Throwable) {
            setStatus("VirtualDisplay failed: ${t.message}")
            stopSelf()
            return
        }

        reader!!.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bmp = imageToBitmap(img)
                synchronized(frameLock) {
                    lastFrame?.recycle()
                    lastFrame = bmp
                }
                maybeAnalyzeFast()
            } catch (t: Throwable) {
                Log.w(TAG, "frame handling failed: ${t.message}")
            } finally {
                try { img.close() } catch (_: Throwable) {}
            }
        }, handler)
    }

    private fun maybeAnalyzeFast() {
        val now = System.currentTimeMillis()
        if (now - lastSignatureAtMs < MIN_ANALYZE_GAP_MS) return
        lastSignatureAtMs = now

        val frame = synchronized(frameLock) { lastFrame } ?: return
        val sig = computeSignature(frame)
        val delta = abs(sig - lastSignature)

        // 1) Run exactly once at the beginning so we don't need "failsafe" churn.
        val shouldTriggerInitial = !didInitialAnalyze

        // 2) After we have a lock, ONLY re-analyze on meaningful screen change.
        //    (No periodic "failsafe" re-analysis while locked.)
        val isLocked = lastLockedAtMs > 0L
        val shouldTriggerByChange = (lastSignature != 0L && delta >= SIG_CHANGE_THRESHOLD)

        // 3) If we never got a lock (Gemini couldn't find bbox etc.), allow a slow retry.
        //    This avoids total dead-end without spamming.
        val shouldTriggerByNoLockRetry =
            (!isLocked && didInitialAnalyze && (now - lastApiAtMs) >= FAILSAFE_REANALYZE_MS && !inFlight.get())

        if (!shouldTriggerInitial && !shouldTriggerByChange && !shouldTriggerByNoLockRetry) return

        lastSignature = sig
        didInitialAnalyze = true
        triggerAnalyzeOnce()
    }

    private fun triggerAnalyzeOnce() {
        val now = System.currentTimeMillis()
        if (now - lastApiAtMs < API_COOLDOWN_MS) return
        if (!inFlight.compareAndSet(false, true)) return
        lastApiAtMs = now

        val srcCopy: Bitmap? = synchronized(frameLock) {
            val src = lastFrame ?: return@synchronized null
            try {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                null
            }
        }

        val ownedFrame = srcCopy
        if (ownedFrame == null) {
            inFlight.set(false)
            return
        }

        val oaKey = openAiKey()
        val gKey = geminiKey()
        if (oaKey.isBlank()) {
            setStatus("Missing OpenAI key in prefs: $PREF_OPENAI_API_KEY")
            CoachBus.update(CoachState.Error("missing OpenAI key"))
            try { ownedFrame.recycle() } catch (_: Throwable) {}
            inFlight.set(false)
            return
        }
        if (gKey.isBlank()) {
            setStatus("Missing Gemini key in prefs: $PREF_GEMINI_API_KEY")
            CoachBus.update(CoachState.Error("missing Gemini key"))
            try { ownedFrame.recycle() } catch (_: Throwable) {}
            inFlight.set(false)
            return
        }

        val expName = expectedAppName()
        val expQuery = expectedAppQuery()
        val dev = selectedDevice()

        setStatus("Analyzing…")
        CoachBus.update(CoachState.Scanning)

        scope.launch {
            try {
                val nowMs = System.currentTimeMillis()
                val reuseLocating = locatingInstruction != null && nowMs < locatingUntilMs

                val instruction: String = if (reuseLocating) {
                    locatingInstruction!!.trim()
                } else {
                    val oaResult = withContext(Dispatchers.IO) {
                        openAi.nextTapInstruction(
                            apiKey = oaKey,
                            expectedAppName = expName,
                            expectedAppQuery = expQuery,
                            selectedDevice = dev,
                            bitmap = ownedFrame
                        )
                    }
                    when (oaResult) {
                        is OpenAiStepCoach.Result.Ok -> {
                            val inst = oaResult.instruction.trim()
                            // Store for "locating" mode reuse.
                            locatingInstruction = inst
                            locatingUntilMs = nowMs + LOCATING_HOLD_MS
                            inst
                        }
                        is OpenAiStepCoach.Result.InsufficientQuota -> {
                            setStatus("OpenAI quota exceeded.")
                            CoachBus.update(CoachState.Error("OpenAI quota exceeded"))
                            return@launch
                        }
                        is OpenAiStepCoach.Result.Error -> {
                            setStatus("OpenAI error: ${oaResult.message}")
                            CoachBus.update(CoachState.Error("OpenAI: ${oaResult.message}"))
                            return@launch
                        }
                    }
                }

                if (instruction.isBlank()) {
                    setStatus("No instruction.")
                    CoachBus.update(CoachState.LostTarget)
                    return@launch
                }

                val box = withContext(Dispatchers.IO) {
                    GeminiNanoBanana.locateTapTarget(
                        apiKey = gKey,
                        modelId = geminiModel(),
                        instruction = instruction,
                        bitmap = ownedFrame
                    )
                }

                if (box == null) {
                    // Keep hint and circle consistent: no bbox => hide circle and show "Locating…"
                    overlay?.hide()
                    setStatus("Locating: ${instruction.take(60)}")
                    CoachBus.update(CoachState.TargetCandidate(label = "locating:${instruction.take(60)}"))
                    return@launch
                }

                val located = box
                val b = located.box

                // Validate Gemini matched the label we asked for (prevents "Tap Continue" on screens without Continue).
                val kw = instructionKeyword(instruction)
                if (kw.isNotBlank()) {
                    val matched = located.matchedText.lowercase()
                    if (!matched.contains(kw.lowercase())) {
                        overlay?.hide()
                        setStatus("Locating: ${instruction.take(60)}")
                        CoachBus.update(CoachState.TargetCandidate(label = "locating:${instruction.take(60)}"))
                        return@launch
                    }
                }

                val cx = ((b.x + b.w / 2.0).coerceIn(0.0, 1.0) * screenW).toInt()
                val cy = ((b.y + b.h / 2.0).coerceIn(0.0, 1.0) * screenH).toInt()

                val wPx = (b.w * screenW).toInt().coerceAtLeast(1)
                val hPx = (b.h * screenH).toInt().coerceAtLeast(1)
                // Reject obviously bad boxes (e.g., Gemini boxed the whole content area).
                if (b.w > 0.55 || b.h > 0.35) {
                    // Keep hint and circle consistent: bad bbox => hide circle and show "Locating…"
                    overlay?.hide()
                    setStatus("Locating: ${instruction.take(60)}")
                    CoachBus.update(CoachState.TargetCandidate(label = "locating:${instruction.take(60)}"))
                    return@launch
                }

                // Hard clamp ring size so it never dominates the screen.
                val dia = max(dp(44f), max(wPx, hPx)).coerceAtMost(dp(140f))

                overlay?.showAt(cx, cy, dia)
                setStatus(instruction)
                // Mark lock time so we stop periodic retries and wait for screen change.
                lastLockedAtMs = System.currentTimeMillis()
                CoachBus.update(CoachState.TargetLocked(label = "${instruction.take(60)}@$cx,$cy"))
            } finally {
                try { ownedFrame.recycle() } catch (_: Throwable) {}
                inFlight.set(false)
            }
        }
    }

    private fun instructionKeyword(instruction: String): String {
        // Very small heuristic: for "Tap Continue", returns "Continue".
        // For quoted or longer strings, returns the last token-ish word.
        var s = instruction.trim()
        if (s.lowercase().startsWith("tap ")) s = s.substring(4).trim()
        s = s.trim('"', '\'', '.', '!', '?')
        // If it contains spaces, take the last word (e.g., "Get Started" -> "Started").
        val parts = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.lastOrNull()?.take(32)?.trim() ?: ""
    }

    private fun computeSignature(bmp: Bitmap): Long {
        val w = max(1, bmp.width)
        val h = max(1, bmp.height)
        val stepX = max(1, w / SIG_W)
        val stepY = max(1, h / SIG_H)

        var acc = 0L
        var count = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = (c) and 0xFF
                acc += (r + g + b).toLong()
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count <= 0L) 0L else acc / count
    }

    private fun stopProjection() {
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        synchronized(frameLock) {
            try { lastFrame?.recycle() } catch (_: Throwable) {}
            lastFrame = null
        }
        overlay?.hide()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = android.app.NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Coach Mode",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("DeviceLinkAssistant")
            .setContentText(text.take(80))
            .setOngoing(true)
            .build()
    }

    private fun imageToBitmap(img: android.media.Image): Bitmap {
        val w = img.width
        val h = img.height
        val plane = img.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w

        val bmp = Bitmap.createBitmap(
            w + rowPadding / pixelStride,
            h,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)

        return Bitmap.createBitmap(bmp, 0, 0, w, h).also {
            try { bmp.recycle() } catch (_: Throwable) {}
        }
    }
}
