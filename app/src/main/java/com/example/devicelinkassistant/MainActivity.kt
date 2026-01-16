package com.example.devicelinkassistant

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.devicelinkassistant.BuildConfig
import com.example.devicelinkassistant.ble.BleScanner
import com.example.devicelinkassistant.ble.DeviceProfiles
import com.example.devicelinkassistant.ble.FoundBleDevice
import com.example.devicelinkassistant.ui.theme.DeviceLinkAssistantTheme
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs
import kotlin.text.Charsets

private enum class AppStep {
    SELECT_DEVICES,
    SCAN_AND_PICK,
    SETUP_APP
}

// “Audio targets” (paired BT audio devices) by name keyword.
private enum class AudioTarget(val label: String, val nameKeywords: List<String>) {
    BEATS("Beats", listOf("beats")),
    ONN("ONN", listOf("onn")),
    ECHO("Echo", listOf("echo"))
}

// Wi-Fi devices that require companion app onboarding
private const val IOT_WIZ_BULB = "WiZ Smart Bulb"
private const val IOT_FEIT_BULB = "Feit Smart Bulb"

// Feit automation scene names
private const val FEIT_SCENE_ON = "DL FEIT ON"
private const val FEIT_SCENE_OFF = "DL FEIT OFF"

// Feit app package
private const val PKG_FEIT = "com.feit.smart"

// Wi-Fi devices that truly require a companion app for onboarding
private fun isWifiCompanionOnly(iotDisplayName: String): Boolean {
    return iotDisplayName.equals(IOT_WIZ_BULB, ignoreCase = true) ||
        iotDisplayName.equals(IOT_FEIT_BULB, ignoreCase = true)
}

class MainActivity : ComponentActivity() {

    private lateinit var btScanner: BleScanner
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val projectionManager by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }

    // BT profile proxies
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null

    // Route watcher
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var lastAppliedRoute: String? = null

    // Rainbow job for WiZ while music is active
    private var wizRainbowJob: Job? = null

    // Media resume helpers
    private val mediaSessionManager by lazy { getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    companion object {
        private const val PREFS_INSTALL = "dla_install_prefs"
        private const val PREFS_CONTROLLER = "dla_controller_prefs"
        private const val PREFS_COACH = "dla_coach_prefs"

        const val KEY_PENDING_INSTALL = "pending_install"
        const val KEY_PENDING_PKG = "pending_pkg"
        const val KEY_PENDING_LABEL = "pending_label"
        const val KEY_PENDING_TS = "pending_ts"

        const val KEY_CTRL_ACTIVE = "ctrl_active"
        const val KEY_CTRL_TARGET_PKG = "ctrl_target_pkg"
        const val KEY_CTRL_WORKFLOW = "ctrl_workflow"

        const val KEY_COACH_ACTIVE = "coach_active"
        const val KEY_COACH_WORKFLOW = "coach_workflow"

        // Coach context persisted so ScreenCoachService can be generic (derived from dropdown selection)
        // These are stored in PREFS_COACH (dla_coach_prefs).
        const val KEY_COACH_SELECTED_DEVICE = "coach_selected_device"
        const val KEY_COACH_EXPECTED_APP_NAME = "coach_expected_app_name"
        const val KEY_COACH_EXPECTED_APP_QUERY = "coach_expected_app_query"

        // Internal: used to make "Start Coach" a single flow (we deep-link to overlay permission, then resume)
        private const val KEY_COACH_PENDING_OVERLAY = "coach_pending_overlay"

        const val PKG_ALEXA = "com.amazon.dee.app"
        const val PKG_FEIT = "com.feit.smart"

        // MediaProjection payload for ScreenCoachService (read in ScreenCoachService.onStartCommand).
        const val EXTRA_PROJECTION_RESULT_CODE = "dla_extra_projection_result_code"
        const val EXTRA_PROJECTION_DATA_INTENT = "dla_extra_projection_data_intent"

        const val WF_ALEXA_OPEN_DEVICES_TAB = "WF_ALEXA_OPEN_DEVICES_TAB"
        const val WF_ALEXA_OPEN_HOME = "WF_ALEXA_OPEN_HOME"
        const val WF_FEIT_SCENE_ON = "WF_FEIT_SCENE_ON"
        const val WF_FEIT_SCENE_OFF = "WF_FEIT_SCENE_OFF"
    }

    private fun installPrefs(): SharedPreferences = getSharedPreferences(PREFS_INSTALL, MODE_PRIVATE)
    private fun controllerPrefs(): SharedPreferences = getSharedPreferences(PREFS_CONTROLLER, MODE_PRIVATE)
    private fun coachPrefs(): SharedPreferences = getSharedPreferences(PREFS_COACH, MODE_PRIVATE)

    private fun markPendingInstall(packageName: String?, displayName: String) {
        installPrefs().edit()
            .putBoolean(KEY_PENDING_INSTALL, true)
            .putString(KEY_PENDING_PKG, packageName)
            .putString(KEY_PENDING_LABEL, displayName)
            .putLong(KEY_PENDING_TS, System.currentTimeMillis())
            .apply()
    }

    private fun armController(targetPkg: String, workflow: String) {
        controllerPrefs().edit()
            .putBoolean(KEY_CTRL_ACTIVE, true)
            .putString(KEY_CTRL_TARGET_PKG, targetPkg)
            .putString(KEY_CTRL_WORKFLOW, workflow)
            .apply()
    }

    private fun armCoach(workflow: String) {
        coachPrefs().edit()
            .putBoolean(KEY_COACH_ACTIVE, true)
            .putString(KEY_COACH_WORKFLOW, workflow)
            .apply()
    }

    private fun clearCoach() {
        coachPrefs().edit()
            .remove(KEY_COACH_ACTIVE)
            .remove(KEY_COACH_WORKFLOW)
            .apply()
    }

    private fun clearController() {
        controllerPrefs().edit()
            .remove(KEY_CTRL_ACTIVE)
            .remove(KEY_CTRL_TARGET_PKG)
            .remove(KEY_CTRL_WORKFLOW)
            .apply()
    }

    private var uiStatusText by mutableStateOf("Choose a device to connect, then Continue.")
    private var isScanning by mutableStateOf(false)
    private var uniqueSeenCount by mutableIntStateOf(0)
    private var lastFound by mutableStateOf<FoundBleDevice?>(null)
    private var foundList by mutableStateOf<List<FoundBleDevice>>(emptyList())
    private var appStep by mutableStateOf(AppStep.SELECT_DEVICES)

    private var pairingTargetAddress: String? by mutableStateOf(null)
    private var bondReceiverRegistered by mutableStateOf(false)
    private var setupAttemptedFor: String? by mutableStateOf(null)
    private var selectedIotDisplayName by mutableStateOf(DeviceProfiles.ALL.first().displayName)

    private var accessibilityOk by mutableStateOf(false) // keep for now; no longer required for Coach mode
    private var waitingForAccessibilityEnable: Boolean = false
    private var accessibilityObserver: ContentObserver? = null

    private var isCoachRunning by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val allGranted = perms.values.all { it }
            if (allGranted) {
                startClassicDiscovery()
            } else {
                Log.e("BT", "Permissions not granted: $perms")
                uiStatusText = "Permissions denied. Enable Bluetooth permissions and try again."
                isScanning = false
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res: ActivityResult ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                CoachBus.update(CoachState.StartingCapture)
                startScreenCoachService(res.resultCode, data)
                isCoachRunning = true
                uiStatusText = "Coach mode ON. Switch to the target screen and follow the hint."
            } else {
                uiStatusText = "Coach mode not started (screen share cancelled)."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Store OpenAI key in coach prefs so ScreenCoachService can call OpenAiVisionCoach.
        // Key is read from BuildConfig (which reads from local.properties or environment variable).
        coachPrefs().edit()
            .putString(
                "openai_api_key",
                BuildConfig.OPENAI_API_KEY
            )
            .apply()

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            Log.i("MainActivity", "FCM token (len=${token.length})")
        }

        btScanner = BleScanner(this)
        initBtAudioProxies()
        registerAudioRouteWatcher()

        setContent {
            DeviceLinkAssistantTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val coachHint by ScreenCoachState.hint.collectAsState()
                    val coachState by CoachBus.state.collectAsState()
                    DeviceLinkFlow(
                        statusText = uiStatusText,
                        isScanning = isScanning,
                        uniqueSeenCount = uniqueSeenCount,
                        lastFound = lastFound,
                        foundList = foundList,
                        step = appStep,
                        onStepChange = { appStep = it },
                        initialSelectedIot = selectedIotDisplayName,
                        onSelectedIotChanged = { selectedIotDisplayName = it },
                        coachHint = coachHint,
                        coachStatus = coachState.message,
                        isCoachRunning = isCoachRunning,
                        onStartCoach = { startCoach("WF_GENERIC") },
                        onStopCoach = { stopCoach() },
                        onScan = { handleScanPressed() },
                        onStopScan = { stopScan() },
                        onTapDevice = { device -> handleTapDevice(device) },
                        onAutoSetup = { selected, force -> maybeAutoSetupCompanion(selected, force) },
                        onDriveAlexa = { driveAlexaToDevicesTab() },
                        onSwitchAudio = { target -> switchAudioTo(target) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityOk = isAccessibilityEnabled()
        if (!accessibilityOk) maybeRegisterAccessibilityObserver() else unregisterAccessibilityObserver()
        if (!ScreenCoachState.isRunning.value) isCoachRunning = false

        // If user just granted overlay permission, automatically continue the Coach start flow.
        maybeContinueCoachStartAfterOverlayGrant()
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun maybeContinueCoachStartAfterOverlayGrant() {
        val pending = coachPrefs().getBoolean(KEY_COACH_PENDING_OVERLAY, false)
        if (!pending) return
        if (!hasOverlayPermission()) return

        coachPrefs().edit().putBoolean(KEY_COACH_PENDING_OVERLAY, false).apply()

        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    override fun onDestroy() {
        stopScan()
        unregisterBondReceiverIfNeeded()
        unregisterAudioRouteWatcher()
        closeBtAudioProxies()
        wizRainbowJob?.cancel()
        wizRainbowJob = null
        stopCoach()
        super.onDestroy()
    }

    private fun startScreenCoachService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCoachService::class.java).apply {
            putExtra(EXTRA_PROJECTION_RESULT_CODE, resultCode)
            putExtra(EXTRA_PROJECTION_DATA_INTENT, data)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                @Suppress("DEPRECATION")
                startService(intent)
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Failed to start ScreenCoachService", t)
            uiStatusText = "Couldn’t start Coach service: ${t.message ?: "unknown error"}"
        }
    }

    private fun startCoach(workflow: String) {
        // Persist the user's current selection so the coach can be generic.
        val selected = selectedIotDisplayName
        val spec: CompanionAppSpec? = CompanionAppRegistry.findByIotDisplayName(selected)
            ?: when {
                selected.equals(IOT_WIZ_BULB, ignoreCase = true) ->
                    CompanionAppSpec(iotDisplayName = IOT_WIZ_BULB, displayName = "WiZ v2", packageName = null, playStoreQuery = "WiZ v2")
                selected.equals(IOT_FEIT_BULB, ignoreCase = true) ->
                    CompanionAppSpec(iotDisplayName = IOT_FEIT_BULB, displayName = "Feit Electric", packageName = null, playStoreQuery = "Feit Electric")
                else -> null
            }
        coachPrefs().edit()
            .putString(KEY_COACH_SELECTED_DEVICE, selected)
            .putString(KEY_COACH_EXPECTED_APP_NAME, spec?.displayName ?: "")
            .putString(KEY_COACH_EXPECTED_APP_QUERY, spec?.playStoreQuery ?: spec?.displayName ?: "")
            .apply()

        armCoach(workflow)
        CoachBus.update(CoachState.RequestingPermission)

        // One-click flow:
        // If overlay permission is missing, deep-link to it and auto-resume in onResume().
        if (!hasOverlayPermission()) {
            coachPrefs().edit().putBoolean(KEY_COACH_PENDING_OVERLAY, true).apply()
            uiStatusText = "Enable 'Display over other apps' once. Coach will continue automatically."
            requestOverlayPermission()
            return
        }

        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun stopCoach() {
        coachPrefs().edit().putBoolean(KEY_COACH_PENDING_OVERLAY, false).apply()
        clearCoach()
        try {
            stopService(Intent(this, ScreenCoachService::class.java))
        } catch (_: Throwable) {
            // ignore
        }
        ScreenCoachState.clear()
        isCoachRunning = false
        CoachBus.update(CoachState.Idle)
    }

    // =========================
    // ROUTE WATCHER
    // =========================
    private fun registerAudioRouteWatcher() {
        if (audioDeviceCallback != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<android.media.AudioDeviceInfo>) {
                handleRouteMaybeChanged()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<android.media.AudioDeviceInfo>) {
                handleRouteMaybeChanged()
            }
        }
        try {
            am.registerAudioDeviceCallback(audioDeviceCallback, null)
            handleRouteMaybeChanged()
        } catch (t: Throwable) {
            Log.e("Route", "Failed to registerAudioDeviceCallback", t)
        }
    }

    private fun unregisterAudioRouteWatcher() {
        val cb = audioDeviceCallback ?: return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            am.unregisterAudioDeviceCallback(cb)
        } catch (_: Throwable) {
            // ignore
        } finally {
            audioDeviceCallback = null
        }
    }

    private fun handleRouteMaybeChanged() {
        val route = AudioRouteDetector.getCurrentMediaRoute(this)
        Log.i("Route", "routeChanged=$route")
        if (route == lastAppliedRoute) return
        lastAppliedRoute = route
        applyLightPolicyForRoute(route)
    }

    private fun applyLightPolicyForRoute(route: String) {
        val r = route.lowercase()
        val isOnn = r.contains("onn")
        val isEcho = r.contains("echo")

        stopWizRainbow()

        appScope.launch(Dispatchers.IO) {
            when {
                isOnn -> {
                    WizLan.setAll(true)
                    WizLan.setAllRgb(0, 255, 0, 70)
                }
                isEcho -> {
                    WizLan.setAll(true)
                    WizLan.setAllRgb(255, 0, 0, 70)
                }
                else -> {
                    WizLan.setAll(false)
                }
            }
        }

        uiStatusText = when {
            isOnn -> "Audio: $route → WiZ GREEN"
            isEcho -> "Audio: $route → WiZ RED"
            else -> "Audio: $route → WiZ OFF"
        }
    }

    private fun isMusicPlaying(): Boolean = audioManager.isMusicActive

    private fun stopWizRainbow() {
        wizRainbowJob?.cancel()
        wizRainbowJob = null
    }

    // =========================
    // SCAN / PAIR
    // =========================
    private fun handleScanPressed() {
        uniqueSeenCount = 0
        lastFound = null
        foundList = emptyList()

        uiStatusText = "Preparing discovery..."
        isScanning = true

        appStep = AppStep.SCAN_AND_PICK
        if (!hasPermissionsNeeded()) {
            permissionLauncher.launch(requiredPermissions())
            return
        }
        startClassicDiscovery()
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }

    private fun hasPermissionsNeeded(): Boolean {
        return requiredPermissions().all { p ->
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun upsertFoundDevice(list: List<FoundBleDevice>, device: FoundBleDevice): List<FoundBleDevice> {
        val idx = list.indexOfFirst { it.address.equals(device.address, ignoreCase = true) }
        return if (idx < 0) {
            list + device
        } else {
            val mutable = list.toMutableList()
            mutable[idx] = device
            mutable.toList()
        }
    }

    private fun startClassicDiscovery() {
        val profile = DeviceProfiles.byDisplayName(selectedIotDisplayName)

        uiStatusText = "Starting classic discovery for ${profile.displayName}..."
        isScanning = true

        btScanner.start(
            profile = profile,
            onStatus = { status -> uiStatusText = status },
            onDeviceUpdate = { uniqueSeen, last ->
                uniqueSeenCount = uniqueSeen
                lastFound = last
                if (last != null) foundList = upsertFoundDevice(foundList, last)
            },
            onCandidateFound = { candidate ->
                uiStatusText = "Match: ${candidate.name ?: "(no name)"}  ${candidate.address}  RSSI=${candidate.rssi}"
                isScanning = false
            },
            onError = { err ->
                Log.e("BT", err)
                uiStatusText = err
                isScanning = false
            }
        )
    }

    private fun handleTapDevice(device: FoundBleDevice) {
        appStep = AppStep.SCAN_AND_PICK
        uiStatusText = "Pairing with ${device.name ?: "(no name)"}..."
        pairingTargetAddress = device.address

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            // Already paired: treat as a BT audio device and try to connect A2DP.
            isScanning = false

            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter
            if (adapter == null || !adapter.isEnabled) {
                uiStatusText = "Bluetooth is off. Turn Bluetooth on and try again."
                appStep = AppStep.SELECT_DEVICES
                return
            }

            val a2dp = a2dpProxy
            if (a2dp == null) {
                uiStatusText = "Audio switcher is warming up. Try again in a moment."
                appStep = AppStep.SELECT_DEVICES
                return
            }

            uiStatusText = "Already paired. Connecting audio…"

            val dev: BluetoothDevice = try {
                adapter.getRemoteDevice(device.address)
            } catch (_: Throwable) {
                uiStatusText = "Couldn’t resolve device address for audio connect."
                appStep = AppStep.SELECT_DEVICES
                return
            }

            // Best-effort: disconnect others, then connect this one.
            val disconnectedOk = disconnectAllA2dp(a2dp)
            val connectedOk = connectA2dp(a2dp, dev)

            uiStatusText = when {
                connectedOk -> "Connected audio to ${device.name ?: "device"}."
                disconnectedOk -> "Couldn’t auto-connect audio. Opening Bluetooth settings…"
                else -> "Couldn’t connect audio. Opening Bluetooth settings…"
            }

            if (connectedOk) {
                appScope.launch {
                    delay(800)
                    resumeMediaIfPaused()
                }
                appStep = AppStep.SELECT_DEVICES
            } else {
                openBluetoothSettings()
                appStep = AppStep.SELECT_DEVICES
            }

            return
        }

        registerBondReceiverIfNeeded()
        createBondForAddress(device.address)
        isScanning = false
        uiStatusText = "Pairing requested. Confirm the prompt if it appears."
    }

    private fun stopScan() {
        btScanner.stop()
        isScanning = false
    }

    private fun registerBondReceiverIfNeeded() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        registerReceiver(bondReceiver, filter)
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiverIfNeeded() {
        if (!bondReceiverRegistered) return
        try {
            unregisterReceiver(bondReceiver)
        } catch (_: Throwable) {
            // ignore
        } finally {
            bondReceiverRegistered = false
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val dev = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val address = dev?.address ?: return

            val target = pairingTargetAddress
            if (target != null && !address.equals(target, ignoreCase = true)) return

            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

            when (state) {
                BluetoothDevice.BOND_BONDED -> {
                    // After pairing, treat as BT audio: best-effort connect and return home.
                    uiStatusText = "Paired successfully. Connecting audio…"
                    appStep = AppStep.SELECT_DEVICES
                    pairingTargetAddress = null
                    unregisterBondReceiverIfNeeded()

                    val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter = btManager.adapter
                    val a2dp = a2dpProxy
                    if (adapter != null && adapter.isEnabled && dev != null && a2dp != null) {
                        val disconnectedOk = disconnectAllA2dp(a2dp)
                        val connectedOk = connectA2dp(a2dp, dev)
                        uiStatusText = when {
                            connectedOk -> "Paired + connected audio to ${dev.name ?: "device"}."
                            disconnectedOk -> "Paired. Couldn’t auto-connect audio. Open Bluetooth settings to connect."
                            else -> "Paired. Couldn’t connect audio. Open Bluetooth settings to connect."
                        }
                        if (connectedOk) {
                            appScope.launch {
                                delay(800)
                                resumeMediaIfPaused()
                            }
                        }
                        if (!connectedOk) openBluetoothSettings()
                    } else {
                        uiStatusText = "Paired. If audio doesn’t route, connect it in Bluetooth settings."
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    if (prev == BluetoothDevice.BOND_BONDING) {
                        uiStatusText = "Pairing failed or was cancelled."
                        pairingTargetAddress = null
                        unregisterBondReceiverIfNeeded()
                    }
                }
            }
        }
    }

    private fun createBondForAddress(address: String) {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: return
            val dev = adapter.getRemoteDevice(address)
            dev.createBond()
        } catch (_: SecurityException) {
            uiStatusText = "Missing BLUETOOTH_CONNECT permission."
        } catch (_: IllegalArgumentException) {
            uiStatusText = "Bad device address."
        }
    }

    // =========================
    // BT AUDIO SWITCHING (+ Spotify resume)
    // =========================
    private fun initBtAudioProxies() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: return

            adapter.getProfileProxy(
                this,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) a2dpProxy = proxy as? BluetoothA2dp
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                    }
                },
                BluetoothProfile.A2DP
            )

            adapter.getProfileProxy(
                this,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HEADSET) headsetProxy = proxy as? BluetoothHeadset
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.HEADSET) headsetProxy = null
                    }
                },
                BluetoothProfile.HEADSET
            )
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun closeBtAudioProxies() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: return
            a2dpProxy?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (_: Throwable) {
            // ignore
        } finally {
            a2dpProxy = null
            headsetProxy = null
        }
    }

    private fun switchAudioTo(targetLabel: String) {
        val target = AudioTarget.values().firstOrNull { it.label.equals(targetLabel, ignoreCase = true) }
        if (target == null) {
            uiStatusText = "Unknown audio target: $targetLabel"
            return
        }
        switchAudioTo(target)
    }

    private fun switchAudioTo(target: AudioTarget) {
        if (!hasPermissionsNeeded()) {
            uiStatusText = "Bluetooth permissions missing. Tap Scan once to grant permissions, then try again."
            return
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            uiStatusText = "Bluetooth is off. Turn Bluetooth on and try again."
            return
        }

        val bonded = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            uiStatusText = "Missing BLUETOOTH_CONNECT permission."
            return
        }

        val dev = bonded.firstOrNull { d ->
            val name = (d.name ?: "").lowercase()
            target.nameKeywords.any { kw -> name.contains(kw.lowercase()) }
        }

        if (dev == null) {
            uiStatusText = "Couldn’t find a paired ${target.label} device. Pair it in Android Bluetooth settings first."
            return
        }

        val a2dp = a2dpProxy
        if (a2dp == null) {
            uiStatusText = "Audio switcher is warming up. Try again in a moment."
            return
        }

        uiStatusText = "Switching audio to ${target.label}…"

        val disconnectedOk = disconnectAllA2dp(a2dp)
        val connectedOk = connectA2dp(a2dp, dev)

        uiStatusText = when {
            connectedOk -> "Audio switched to ${target.label}."
            disconnectedOk -> "Couldn’t auto-connect to ${target.label}. Opening Bluetooth settings…"
            else -> "Couldn’t switch automatically. Opening Bluetooth settings…"
        }

        if (connectedOk) {
            appScope.launch {
                delay(800)
                resumeMediaIfPaused()
            }
        } else {
            openBluetoothSettings()
        }
    }

    private fun resumeMediaIfPaused() {
        try {
            val cn = ComponentName(this, SpotifyNotificationListener::class.java)
            val sessions: List<MediaController> = mediaSessionManager.getActiveSessions(cn)
            val playing = sessions.firstOrNull {
                it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            }
            if (playing != null) return

            val candidate = sessions.firstOrNull()
            if (candidate != null) {
                candidate.transportControls.play()
                Log.i("Media", "Resumed via MediaController.play()")
                return
            }
        } catch (t: Throwable) {
            Log.w("Media", "MediaSession resume failed: ${t.message}")
        }

        try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            Log.i("Media", "Resumed via KEYCODE_MEDIA_PLAY fallback")
        } catch (t: Throwable) {
            Log.w("Media", "Media key fallback failed: ${t.message}")
        }
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            // ignore
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAllA2dp(a2dp: BluetoothA2dp): Boolean {
        return try {
            val connected = a2dp.connectedDevices?.toList().orEmpty()
            if (connected.isEmpty()) return true
            var ok = true
            for (d in connected) ok = ok && disconnectA2dp(a2dp, d)
            ok
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectA2dp(a2dp: BluetoothA2dp, device: BluetoothDevice): Boolean {
        return try {
            val m = a2dp.javaClass.getMethod("connect", BluetoothDevice::class.java)
            (m.invoke(a2dp, device) as? Boolean) ?: true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectA2dp(a2dp: BluetoothA2dp, device: BluetoothDevice): Boolean {
        return try {
            val m = a2dp.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            (m.invoke(a2dp, device) as? Boolean) ?: true
        } catch (_: Throwable) {
            false
        }
    }

    // =========================
    // COMPANION APP SETUP
    // =========================
    private fun maybeAutoSetupCompanion(selectedIot: String, force: Boolean = false) {
        if (!force && setupAttemptedFor == selectedIot) return
        setupAttemptedFor = selectedIot

        val spec: CompanionAppSpec? =
            CompanionAppRegistry.findByIotDisplayName(selectedIot)
                ?: when {
                    selectedIot.equals(IOT_WIZ_BULB, ignoreCase = true) -> {
                        CompanionAppSpec(
                            iotDisplayName = IOT_WIZ_BULB,
                            displayName = "WiZ v2",
                            packageName = null,
                            playStoreQuery = "WiZ v2"
                        )
                    }
                    selectedIot.equals(IOT_FEIT_BULB, ignoreCase = true) -> {
                        CompanionAppSpec(
                            iotDisplayName = IOT_FEIT_BULB,
                            displayName = "Feit Electric",
                            packageName = null,
                            playStoreQuery = "Feit Electric"
                        )
                    }
                    else -> null
                }

        if (spec == null) {
            uiStatusText = "Companion app mapping not set for \"$selectedIot\". Add it in CompanionAppRegistry."
            return
        }

        if (spec.noCompanionApp) {
            uiStatusText = spec.instructions ?: "No companion app is required for \"$selectedIot\"."
            return
        }

        val pkg = spec.packageName
        if (!pkg.isNullOrBlank()) {
            val installed = isPackageInstalled(pkg)
            if (installed) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    uiStatusText = "Opening ${spec.displayName}..."
                    try {
                        startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (t: Throwable) {
                        Log.e("SETUP", "Failed to launch $pkg", t)
                        uiStatusText = "Couldn't open ${spec.displayName}. Opening Play Store..."
                    }
                } else {
                    uiStatusText = "${spec.displayName} is installed. Opening Play Store..."
                }
            } else {
                uiStatusText = "Opening Play Store for ${spec.displayName}..."
            }
            openPlayStoreForSpec(spec)
            return
        }

        uiStatusText = "Opening Play Store for ${spec.displayName}..."
        openPlayStoreForSpec(spec)
    }

    private fun driveAlexaToDevicesTab() {
        armController(PKG_ALEXA, WF_ALEXA_OPEN_DEVICES_TAB)
        // NEW: Coach mode (screen OCR) guides user in Alexa; no Accessibility automation needed.
        startCoach(WF_ALEXA_OPEN_DEVICES_TAB)

        val launch = packageManager.getLaunchIntentForPackage(PKG_ALEXA)
        if (launch != null) {
            uiStatusText = "Opening Alexa. Coach mode will guide you to Devices."
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (t: Throwable) {
                Log.e("SETUP", "Failed to launch Alexa", t)
            }
        }

        clearController()
        uiStatusText = "Alexa is not installed. Opening Play Store."
        maybeAutoSetupCompanion(DeviceProfiles.ECHO_SHOW_DISPLAY_NAME, force = true)
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = try {
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1
        } catch (_: Throwable) {
            false
        }
        if (!enabled) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains("$packageName/", ignoreCase = true)
    }

    private fun openAccessibilitySettings() {
        waitingForAccessibilityEnable = true
        maybeRegisterAccessibilityObserver()
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            Log.e("ACCESS", "Failed to open accessibility settings", t)
        }
    }

    private fun maybeRegisterAccessibilityObserver() {
        if (accessibilityObserver != null) return
        val handler = Handler(Looper.getMainLooper())

        accessibilityObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val nowEnabled = isAccessibilityEnabled()
                accessibilityOk = nowEnabled

                if (waitingForAccessibilityEnable && nowEnabled) {
                    waitingForAccessibilityEnable = false
                    unregisterAccessibilityObserver()
                    bringAppToFrontAfterAccessibilityEnabled()
                }
            }
        }

        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                accessibilityObserver!!
            )
        } catch (t: Throwable) {
            Log.e("ACCESS", "Failed to register accessibility observer", t)
        }
    }

    private fun unregisterAccessibilityObserver() {
        val obs = accessibilityObserver ?: return
        try {
            contentResolver.unregisterContentObserver(obs)
        } catch (_: Throwable) {
            // ignore
        } finally {
            accessibilityObserver = null
        }
    }

    private fun bringAppToFrontAfterAccessibilityEnabled() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Log.e("ACCESS", "Failed to bring app to front", t)
        }
    }

    private fun openPlayStoreForSpec(spec: CompanionAppSpec) {
        markPendingInstall(spec.packageName, spec.displayName)

        val marketUri: Uri? = when {
            !spec.packageName.isNullOrBlank() ->
                Uri.parse("market://details?id=${spec.packageName}")
            !spec.playStoreQuery.isNullOrBlank() ->
                Uri.parse("market://search?q=${Uri.encode(spec.playStoreQuery)}")
            else -> null
        }

        val finalMarketUri = marketUri ?: Uri.parse("market://search?q=${Uri.encode(spec.displayName)}")
        val marketIntent = Intent(Intent.ACTION_VIEW, finalMarketUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(marketIntent)
            return
        } catch (_: ActivityNotFoundException) {
            // fall through
        } catch (t: Throwable) {
            Log.e("SETUP", "Failed to open market uri: $finalMarketUri", t)
        }

        val httpsUri: Uri = when {
            !spec.packageName.isNullOrBlank() ->
                Uri.parse("https://play.google.com/store/apps/details?id=${spec.packageName}")
            !spec.playStoreQuery.isNullOrBlank() ->
                Uri.parse("https://play.google.com/store/search?q=${Uri.encode(spec.playStoreQuery)}&c=apps")
            else ->
                Uri.parse("https://play.google.com/store/search?q=${Uri.encode(spec.displayName)}&c=apps")
        }

        try {
            startActivity(Intent(Intent.ACTION_VIEW, httpsUri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (t: Throwable) {
            Log.e("SETUP", "Failed to open https Play Store fallback", t)
            uiStatusText = "Couldn’t open Play Store on this device."
        }
    }
}

/**
 * WiZ LAN control (UDP broadcast)
 * Manifest must include android.permission.INTERNET.
 */
private object WizLan {
    private const val TAG = "WizLan"
    private const val WIZ_PORT = 38899
    private const val BROADCAST_ADDR = "255.255.255.255"

    fun setAll(on: Boolean) {
        val payload = """{"method":"setState","params":{"state":${if (on) "true" else "false"}}}"""
        sendBroadcast(payload)
    }

    fun setAllRgb(r: Int, g: Int, b: Int, brightness: Int) {
        val dim = brightness.coerceIn(1, 100)
        val payload =
            """{"method":"setPilot","params":{"r":${r.coerceIn(0, 255)},"g":${g.coerceIn(0, 255)},"b":${b.coerceIn(0, 255)},"dimming":$dim}}"""
        sendBroadcast(payload)
    }

    private fun sendBroadcast(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        var socket: DatagramSocket? = null
        try {
            val addr = InetAddress.getByName(BROADCAST_ADDR)
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 800
            }
            val packet = DatagramPacket(bytes, bytes.size, addr, WIZ_PORT)
            socket.send(packet)
            Log.i(TAG, "Sent broadcast to $BROADCAST_ADDR:$WIZ_PORT payload=$json")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to send WiZ UDP broadcast", t)
        } finally {
            try { socket?.close() } catch (_: Throwable) {}
        }
    }
}

@Composable
private fun DeviceLinkFlow(
    statusText: String,
    isScanning: Boolean,
    uniqueSeenCount: Int,
    lastFound: FoundBleDevice?,
    foundList: List<FoundBleDevice>,
    step: AppStep,
    onStepChange: (AppStep) -> Unit,
    initialSelectedIot: String,
    onSelectedIotChanged: (String) -> Unit,
    coachHint: String,
    coachStatus: String,
    isCoachRunning: Boolean,
    onStartCoach: () -> Unit,
    onStopCoach: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onTapDevice: (FoundBleDevice) -> Unit,
    onAutoSetup: (String, Boolean) -> Unit,
    onDriveAlexa: () -> Unit,
    onSwitchAudio: (String) -> Unit
) {
    val iotOptions = (DeviceProfiles.ALL.map { it.displayName } + listOf(IOT_WIZ_BULB, IOT_FEIT_BULB)).distinct()
    var selectedIot by remember { mutableStateOf(initialSelectedIot) }

    when (step) {
        AppStep.SELECT_DEVICES -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("DeviceLinkAssistant", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(18.dp))

                if (statusText.isNotBlank()) {
                    Text(text = statusText, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = if (coachStatus.isBlank()) "Coach status: (starting…)" else "Coach status: $coachStatus",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (coachHint.isBlank()) "Coach hint: (tap Start Coach to get screen OCR guidance)" else "Coach hint: $coachHint",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (!isCoachRunning) {
                    Button(onClick = onStartCoach, modifier = Modifier.fillMaxWidth()) {
                        Text("Start Coach mode (screen OCR)")
                    }
                } else {
                    Button(onClick = onStopCoach, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop Coach mode")
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))

                Text("Connect device", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))

                DropdownField(
                    label = "Connect device",
                    options = iotOptions,
                    selected = selectedIot,
                    onSelect = {
                        selectedIot = it
                        onSelectedIotChanged(it)
                    }
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (isWifiCompanionOnly(selectedIot)) {
                            onStepChange(AppStep.SETUP_APP)
                        } else {
                            onStepChange(AppStep.SCAN_AND_PICK)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Continue")
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text("Switch Audio Output", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { onSwitchAudio(AudioTarget.BEATS.label) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch to Beats")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { onSwitchAudio(AudioTarget.ONN.label) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch to ONN")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = { onSwitchAudio(AudioTarget.ECHO.label) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch to Echo")
                }
            }
        }

        AppStep.SCAN_AND_PICK -> {
            ScanAndPickScreen(
                selectedIot = selectedIot,
                statusText = statusText,
                isScanning = isScanning,
                uniqueSeenCount = uniqueSeenCount,
                lastFound = lastFound,
                foundList = foundList,
                onScan = onScan,
                onTapDevice = onTapDevice,
                onBack = {
                    onStopScan()
                    onStepChange(AppStep.SELECT_DEVICES)
                }
            )
        }

        AppStep.SETUP_APP -> {
            SetupAppScreen(
                selectedIot = selectedIot,
                statusText = statusText,
                onAutoSetup = { force -> onAutoSetup(selectedIot, force) },
                onDriveAlexa = onDriveAlexa,
                onBack = {
                    onStopScan()
                    onStepChange(AppStep.SCAN_AND_PICK)
                }
            )
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) }
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true }
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SetupAppScreen(
    selectedIot: String,
    statusText: String,
    onAutoSetup: (force: Boolean) -> Unit,
    onDriveAlexa: () -> Unit,
    onBack: () -> Unit
) {
    // IMPORTANT: no auto-trigger here (this was causing Play Store to open unexpectedly)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("DeviceLinkAssistant", style = MaterialTheme.typography.headlineSmall)

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Companion app for $selectedIot",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (statusText.isNotBlank()) {
            Text(text = statusText, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                text = "Tap a button below to open the companion app or Play Store.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (selectedIot.equals(DeviceProfiles.ECHO_SHOW_DISPLAY_NAME, ignoreCase = true)) {
            Button(onClick = onDriveAlexa, modifier = Modifier.fillMaxWidth()) {
                Text("Open Alexa (Coach will guide)")
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Coach mode uses screen OCR (no Accessibility) to tell you what to tap in Alexa.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        Button(onClick = { onAutoSetup(true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open companion / Play Store")
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
private fun ScanAndPickScreen(
    selectedIot: String,
    statusText: String,
    isScanning: Boolean,
    uniqueSeenCount: Int,
    lastFound: FoundBleDevice?,
    foundList: List<FoundBleDevice>,
    onScan: () -> Unit,
    onTapDevice: (FoundBleDevice) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("DeviceLinkAssistant", style = MaterialTheme.typography.headlineSmall)

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Put your $selectedIot in pairing mode, then tap Scan.",
            style = MaterialTheme.typography.bodyMedium
        )

        if (isWifiCompanionOnly(selectedIot)) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Note: Echo Show is typically set up via Alexa over Wi-Fi. You can go Back and pick Echo Show to open Alexa directly.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(text = statusText, style = MaterialTheme.typography.bodySmall)

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Found: ${foundList.size} device(s) • Unique: $uniqueSeenCount",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isScanning
        ) {
            Text(if (isScanning) "Scanning..." else "Scan")
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(foundList) { d ->
                val title = d.name ?: "(no name)"
                val signal = if (d.rssi == 0) "Connected" else "RSSI=${d.rssi}"
                val subtitle = "${d.address}  $signal"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTapDevice(d) }
                        .padding(vertical = 10.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
