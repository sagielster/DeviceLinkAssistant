package com.example.devicelinkassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Automates "click-through" during Play Store + package installer flows.
 * Also supports a "controller mode" where this service can drive a target app
 * (example: Alexa / Feit) when MainActivity arms it via prefs.
 *
 * IMPORTANT:
 * - Installer mode ONLY acts when MainActivity has marked a "pending install" in prefs.
 * - It only clicks on known installer-related packages and only on safe verbs:
 *   Install / Continue / Next / Allow / Accept / Done / Open / Update.
 */
class InstallerAccessibilityService : AccessibilityService() {

    // Installer prefs (armed by MainActivity)
    private val installPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_INSTALL, Context.MODE_PRIVATE)
    }

    // Controller prefs (armed by MainActivity)
    private val controllerPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_CONTROLLER, Context.MODE_PRIVATE)
    }

    private data class ControllerState(
        val isActive: Boolean,
        val targetPkg: String?,
        val workflow: String?
    )

    private data class PendingInstall(
        val isActive: Boolean,
        val isExpired: Boolean,
        val packageName: String?
    )

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "InstallerAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val activePkg = event.packageName?.toString() ?: return
        val now = SystemClock.elapsedRealtime()

        // ============================================================
        // Controller mode: drive a target app (Alexa / Feit) when armed.
        // Must run BEFORE installer package filter.
        // ============================================================
        val ctrl = getController()
        if (ctrl.isActive && !ctrl.targetPkg.isNullOrBlank() && activePkg == ctrl.targetPkg) {
            if (now - lastActionAtElapsedMs < MIN_ACTION_GAP_MS) return

            val root = rootInActiveWindow
            val acted = when (ctrl.workflow) {
                WF_ALEXA_OPEN_HOME -> handleAlexaOpenHome(root)

                // Feit flows: we tap a likely power control; we don't guarantee on/off state here.
                WF_FEIT_SCENE_ON -> handleFeitPowerTap(root)
                WF_FEIT_SCENE_OFF -> handleFeitPowerTap(root)

                // If you later add Alexa "Devices tab" automation, hook it here.
                // WF_ALEXA_OPEN_DEVICES_TAB -> handleAlexaOpenDevicesTab(root)
                else -> false
            }

            if (acted) {
                lastActionAtElapsedMs = now
                clearController()
            }
            // Never fall through into installer clicking for controller targets.
            return
        }

        // ============================================================
        // Installer mode: only proceed for installer-like packages.
        // ============================================================
        if (!isInstallerLikePackage(activePkg)) return

        val pending = getPendingInstall()
        if (!pending.isActive && !DEBUG_BYPASS_PENDING_INSTALL) {
            Log.d(TAG, "No pending install armed; ignoring. (activePkg=$activePkg)")
            return
        }

        if (pending.isExpired) {
            Log.d(TAG, "Pending expired; clearing. (activePkg=$activePkg)")
            clearPendingInstall()
            return
        }

        if (now - lastActionAtElapsedMs < MIN_ACTION_GAP_MS) return

        // If a pending package name is provided and it's already installed, try "Open" once and clear.
        val pendingPkg = pending.packageName
        if (!pendingPkg.isNullOrBlank() && isPackageInstalled(pendingPkg)) {
            val clickedOpen = clickOpenButton(rootInActiveWindow)
            if (clickedOpen) {
                lastActionAtElapsedMs = now
                clearPendingInstall()
            }
            return
        }

        val root = rootInActiveWindow ?: return

        val verbsInOrder = listOf(
            "Install",
            "Update",
            "Continue",
            "Next",
            "Allow",
            "Accept",
            "I agree",
            "Agree",
            "Done",
            "Open"
        )

        val clicked = clickFirstMatchingVerb(root, verbsInOrder)
        if (clicked) {
            lastActionAtElapsedMs = now
            Log.d(TAG, "Clicked an install-flow button in $activePkg")
        }
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "InstallerAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    // =========================
    // Controller prefs
    // =========================
    private fun getController(): ControllerState {
        val active = controllerPrefs.getBoolean(KEY_CTRL_ACTIVE, false)
        val pkg = controllerPrefs.getString(KEY_CTRL_TARGET_PKG, null)
        val wf = controllerPrefs.getString(KEY_CTRL_WORKFLOW, null)
        return ControllerState(active, pkg, wf)
    }

    private fun clearController() {
        controllerPrefs.edit()
            .remove(KEY_CTRL_ACTIVE)
            .remove(KEY_CTRL_TARGET_PKG)
            .remove(KEY_CTRL_WORKFLOW)
            .apply()
    }

    // =========================
    // Installer pending prefs (must match MainActivity.kt)
    // =========================
    private fun getPendingInstall(): PendingInstall {
        val active = installPrefs.getBoolean(KEY_PENDING_INSTALL, false)
        val pkg = installPrefs.getString(KEY_PENDING_PKG, null)
        val setAt = installPrefs.getLong(KEY_PENDING_TS, 0L)

        val isActive = active || !pkg.isNullOrBlank()
        val isExpired = isActive && setAt > 0L && (System.currentTimeMillis() - setAt) > PENDING_TTL_MS

        return PendingInstall(
            isActive = isActive,
            isExpired = isExpired,
            packageName = pkg
        )
    }

    private fun clearPendingInstall() {
        installPrefs.edit()
            .remove(KEY_PENDING_INSTALL)
            .remove(KEY_PENDING_PKG)
            .remove(KEY_PENDING_LABEL)
            .remove(KEY_PENDING_TS)
            .apply()
    }

    // =========================
    // Installer click helpers
    // =========================
    private fun clickFirstMatchingVerb(root: AccessibilityNodeInfo?, verbs: List<String>): Boolean {
        if (root == null) return false

        // 1) Prefer exact text matches found by framework.
        for (v in verbs) {
            val nodes = try {
                root.findAccessibilityNodeInfosByText(v)
            } catch (_: Throwable) {
                emptyList()
            }

            if (nodes.isEmpty()) continue

            val ordered = nodes
                .filter { n ->
                    val text = n.text?.toString()?.trim().orEmpty()
                    if (!text.equals(v, ignoreCase = true)) return@filter false

                    val cls = n.className?.toString().orEmpty()
                    val okClass = cls.contains("Button", ignoreCase = true) ||
                            cls.contains("TextView", ignoreCase = true)
                    if (!okClass) return@filter false

                    // Avoid Play Store "install on other devices" surfaces
                    val pkg = n.packageName?.toString()
                    if (pkg == "com.android.vending") {
                        val parentText = n.parent?.text?.toString()?.lowercase().orEmpty()
                        if (parentText.contains("other devices")) return@filter false
                    }
                    true
                }
                .sortedWith(
                    compareByDescending<AccessibilityNodeInfo> { n ->
                        val id = n.viewIdResourceName?.lowercase().orEmpty()
                        id.contains("buy") || id.contains("install") || id.contains("cta") || id.contains("button")
                    }.thenByDescending { n ->
                        (n.isClickable && n.isEnabled)
                    }
                )

            for (n in ordered) {
                if (clickClickableAncestor(n)) return true
            }
        }

        // 2) Fallback: traverse and match exact verb text.
        val all = ArrayList<AccessibilityNodeInfo>(256)
        collectNodes(root, all, 0)
        for (v in verbs) {
            val match = all.firstOrNull { n ->
                val t = n.text?.toString()?.trim().orEmpty()
                t.isNotBlank() && t.equals(v, ignoreCase = true)
            }
            if (match != null && clickClickableAncestor(match)) return true
        }

        return false
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40) return
        out.add(node)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectNodes(c, out, depth + 1)
        }
    }

    private fun clickClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 8) {
            if (n.isClickable && n.isEnabled) {
                val clicked = try {
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Throwable) {
                    false
                }
                if (clicked) return true

                // Some UIs ignore ACTION_CLICK; gesture tap can be more reliable.
                if (Build.VERSION.SDK_INT >= 24) {
                    if (tapNodeCenter(n)) return true
                }
                return false
            }
            n = n.parent
            hops++
        }

        if (Build.VERSION.SDK_INT >= 24) {
            return tapNodeCenter(node)
        }
        return false
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() <= 1 || r.height() <= 1) return false

            val x = r.exactCenterX()
            val y = r.exactCenterY()
            tapAt(x, y)
        } catch (_: Throwable) {
            false
        }
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 90L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val latch = CountDownLatch(1)
            var ok = false

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ok = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    ok = false
                    latch.countDown()
                }
            }

            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched) return false

            latch.await(350, TimeUnit.MILLISECONDS)
            ok
        } catch (_: Throwable) {
            false
        }
    }

    private fun clickOpenButton(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        val nodes = try {
            root.findAccessibilityNodeInfosByText("Open")
        } catch (_: Throwable) {
            emptyList()
        }

        for (n in nodes) {
            val text = n.text?.toString()?.trim() ?: continue
            if (!text.equals("Open", ignoreCase = true)) continue

            // Play Store only; avoid random "Open" strings elsewhere
            val pkg = n.packageName?.toString()
            if (pkg != "com.android.vending") continue

            if (clickClickableAncestor(n)) {
                Log.d(TAG, "Clicked Open button")
                return true
            }
        }
        return false
    }

    // =========================
    // Controller handlers
    // =========================
    private fun handleAlexaOpenHome(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        // 1) The obvious: the "Ask Alexa" voice bar
        val ask = findFirstByText(root, "Ask Alexa")
        if (ask != null) return clickClickableAncestor(ask)

        // 2) Fallback: mic-like contentDescription
        val mic = findFirstByContentDescContains(root, "mic")
        if (mic != null) return clickClickableAncestor(mic)

        return false
    }

    /**
     * Conservative Feit tap: click a node whose contentDescription contains "power",
     * otherwise tap near the right edge of a likely device card.
     *
     * NOTE: This does not guarantee ON vs OFF; it's just a reliable "toggle".
     */
    private fun handleFeitPowerTap(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        SystemClock.sleep(250)

        val r = rootInActiveWindow ?: root

        val power = findFirstByContentDescContains(r, "power")
        if (power != null) return clickClickableAncestor(power)

        val card = findFeitDeviceCardContainer(r)
        if (card != null && Build.VERSION.SDK_INT >= 24) {
            val rect = Rect()
            card.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val insetPx = (18f * resources.displayMetrics.density)
                val x = (rect.right - insetPx).coerceAtLeast(rect.left + 1f)
                val y = rect.exactCenterY()
                Log.i(TAG, "Feit: gesture tap at x=$x y=$y within card=$rect")
                return tapAt(x, y)
            }
        }

        return false
    }

    private fun findFeitDeviceCardContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val anchor = findFirstByText(root, "Color Light")
            ?: findFirstByText(root, "Shortcut Panel")
            ?: findFirstByTextContains(root, "Light")
            ?: return null

        var cur: AccessibilityNodeInfo? = anchor
        val rect = Rect()
        var hops = 0
        while (cur != null && hops < 10) {
            cur.getBoundsInScreen(rect)
            if (rect.width() >= 600 && rect.height() >= 140 && rect.top > 120) {
                return cur
            }
            cur = cur.parent
            hops++
        }
        return anchor.parent ?: anchor
    }

    // =========================
    // Find helpers
    // =========================
    private fun findFirstByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        return try {
            root.findAccessibilityNodeInfosByText(text)?.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun findFirstByTextContains(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        fun rec(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val t = n.text?.toString()
            if (!t.isNullOrBlank() && t.contains(needle, ignoreCase = true)) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val hit = rec(c)
                if (hit != null) return hit
            }
            return null
        }
        return try { rec(root) } catch (_: Throwable) { null }
    }

    private fun findFirstByContentDescContains(root: AccessibilityNodeInfo, needleLower: String): AccessibilityNodeInfo? {
        fun rec(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val cd = n.contentDescription?.toString()?.lowercase()
            if (cd != null && cd.contains(needleLower)) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                val hit = rec(c)
                if (hit != null) return hit
            }
            return null
        }
        return try { rec(root) } catch (_: Throwable) { null }
    }

    // =========================
    // Package helpers
    // =========================
    private fun isInstallerLikePackage(pkg: String): Boolean {
        return pkg == "com.android.vending" ||
                pkg == "com.google.android.packageinstaller" ||
                pkg == "com.android.packageinstaller" ||
                pkg == "com.samsung.android.packageinstaller" ||
                pkg == "com.google.android.permissioncontroller" ||
                pkg == "com.android.permissioncontroller"
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "InstallerAS"

        // Flip to true only for debugging (dangerous: will click buttons even when not armed).
        private const val DEBUG_BYPASS_PENDING_INSTALL = false

        // Must match MainActivity.kt
        private const val PREFS_INSTALL = "dla_install_prefs"
        private const val KEY_PENDING_INSTALL = "pending_install"
        private const val KEY_PENDING_PKG = "pending_pkg"
        private const val KEY_PENDING_LABEL = "pending_label"
        private const val KEY_PENDING_TS = "pending_ts"

        // Controller prefs (must match MainActivity.kt)
        private const val PREFS_CONTROLLER = "dla_controller_prefs"
        private const val KEY_CTRL_ACTIVE = "ctrl_active"
        private const val KEY_CTRL_TARGET_PKG = "ctrl_target_pkg"
        private const val KEY_CTRL_WORKFLOW = "ctrl_workflow"

        // Workflows
        private const val WF_ALEXA_OPEN_HOME = "WF_ALEXA_OPEN_HOME"
        private const val WF_FEIT_SCENE_ON = "WF_FEIT_SCENE_ON"
        private const val WF_FEIT_SCENE_OFF = "WF_FEIT_SCENE_OFF"

        private const val MIN_ACTION_GAP_MS = 650L
        private const val PENDING_TTL_MS = 2 * 60 * 1000L // 2 minutes

        @Volatile
        private var lastActionAtElapsedMs: Long = 0L
    }
}
