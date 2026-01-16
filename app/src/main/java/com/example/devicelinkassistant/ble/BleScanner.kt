package com.example.devicelinkassistant.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * NOTE: Despite the name, this now handles CLASSIC Bluetooth discovery + connected-profile checks
 * (A2DP/HEADSET). Kept name/signature to avoid refactors.
 */
class BleScanner(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private val seenAddresses = LinkedHashSet<String>()
    private var lastDevice: FoundBleDevice? = null

    private var stopRunnable: Runnable? = null

    private var receiverRegistered = false
    private var receiver: BroadcastReceiver? = null

    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null

    fun start(
        profile: DeviceProfiles.DeviceProfile,
        onStatus: (String) -> Unit,
        onDeviceUpdate: (uniqueSeen: Int, last: FoundBleDevice?) -> Unit,
        onCandidateFound: (FoundBleDevice) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRunning) {
            onStatus("Scan already running.")
            return
        }

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        if (adapter == null) {
            onError("Bluetooth not available on this device.")
            return
        }
        if (!adapter.isEnabled) {
            onError("Bluetooth is off. Turn it on and try again.")
            return
        }

        // Classic discovery (and connected-device queries) require these on API 31+.
        if (!hasConnectPermission()) {
            onError("Missing BLUETOOTH_CONNECT permission.")
            return
        }
        if (!hasScanPermission()) {
            onError("Missing BLUETOOTH_SCAN permission.")
            return
        }

        isRunning = true
        seenAddresses.clear()
        lastDevice = null
        onDeviceUpdate(0, null)

        onStatus("Checking existing classic BT connections (A2DP/HEADSET)…")

        // First: check already-connected via A2DP + HEADSET before starting discovery.
        checkAlreadyConnectedThenDiscover(
            adapter = adapter,
            profile = profile,
            onStatus = onStatus,
            onDeviceUpdate = onDeviceUpdate,
            onCandidateFound = onCandidateFound,
            onError = onError
        )
    }

    fun stop() {
        if (!isRunning) return
        stopInternal()
    }

    private fun checkAlreadyConnectedThenDiscover(
        adapter: BluetoothAdapter,
        profile: DeviceProfiles.DeviceProfile,
        onStatus: (String) -> Unit,
        onDeviceUpdate: (uniqueSeen: Int, last: FoundBleDevice?) -> Unit,
        onCandidateFound: (FoundBleDevice) -> Unit,
        onError: (String) -> Unit
    ) {
        // We check A2DP first, then HEADSET, then start discovery if no hit.
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager == null) {
            stopInternal()
            onError("BluetoothManager not available.")
            return
        }

        val serviceListenerA2dp = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                a2dpProxy = proxy
                val hit = findMatchInConnected(proxy.connectedDevices, profile)
                if (hit != null) {
                    onStatus("Already connected (A2DP): ${hit.nameOrFallback} (${hit.address}).")
                    onDeviceUpdate(seenAddresses.size, hit)
                    onCandidateFound(hit)
                    stopInternal()
                    closeProfileProxy(adapter, BluetoothProfile.A2DP, proxy)
                    return
                }

                closeProfileProxy(adapter, BluetoothProfile.A2DP, proxy)

                // Now check HEADSET
                val serviceListenerHeadset = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profileId: Int, proxy2: BluetoothProfile) {
                        headsetProxy = proxy2
                        val hit2 = findMatchInConnected(proxy2.connectedDevices, profile)
                        if (hit2 != null) {
                            onStatus("Already connected (HEADSET): ${hit2.nameOrFallback} (${hit2.address}).")
                            onDeviceUpdate(seenAddresses.size, hit2)
                            onCandidateFound(hit2)
                            stopInternal()
                            closeProfileProxy(adapter, BluetoothProfile.HEADSET, proxy2)
                            return
                        }

                        closeProfileProxy(adapter, BluetoothProfile.HEADSET, proxy2)

                        // No connected match -> start discovery
                        startClassicDiscovery(adapter, profile, onStatus, onDeviceUpdate, onCandidateFound, onError)
                    }

                    override fun onServiceDisconnected(profileId: Int) {
                        // ignore
                    }
                }

                val okHeadset = adapter.getProfileProxy(context, serviceListenerHeadset, BluetoothProfile.HEADSET)
                if (!okHeadset) {
                    // If HEADSET proxy can't be obtained, still proceed to discovery.
                    startClassicDiscovery(adapter, profile, onStatus, onDeviceUpdate, onCandidateFound, onError)
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                // ignore
            }
        }

        val okA2dp = adapter.getProfileProxy(context, serviceListenerA2dp, BluetoothProfile.A2DP)
        if (!okA2dp) {
            // If A2DP proxy can't be obtained, still proceed to discovery.
            startClassicDiscovery(adapter, profile, onStatus, onDeviceUpdate, onCandidateFound, onError)
        }
    }

    private fun startClassicDiscovery(
        adapter: BluetoothAdapter,
        profile: DeviceProfiles.DeviceProfile,
        onStatus: (String) -> Unit,
        onDeviceUpdate: (uniqueSeen: Int, last: FoundBleDevice?) -> Unit,
        onCandidateFound: (FoundBleDevice) -> Unit,
        onError: (String) -> Unit
    ) {
        onStatus("Starting classic Bluetooth discovery for: ${profile.displayName}")

        registerReceiverIfNeeded(
            adapter = adapter,
            profile = profile,
            onStatus = onStatus,
            onDeviceUpdate = onDeviceUpdate,
            onCandidateFound = onCandidateFound
        )

        // Cancel any existing discovery to restart cleanly.
        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (_: Throwable) {
            // ignore
        }

        val started = try {
            adapter.startDiscovery()
        } catch (t: Throwable) {
            false
        }

        if (!started) {
            stopInternal()
            onError("Failed to start classic Bluetooth discovery.")
            return
        }

        // Stop after scan window
        stopRunnable = Runnable {
            if (!isRunning) return@Runnable
            stopInternal()
            onStatus("Discovery window ended. Found ${seenAddresses.size} unique device(s).")
        }
        handler.postDelayed(stopRunnable!!, profile.scanWindowMs)
    }

    private fun registerReceiverIfNeeded(
        adapter: BluetoothAdapter,
        profile: DeviceProfiles.DeviceProfile,
        onStatus: (String) -> Unit,
        onDeviceUpdate: (uniqueSeen: Int, last: FoundBleDevice?) -> Unit,
        onCandidateFound: (FoundBleDevice) -> Unit
    ) {
        if (receiverRegistered) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (!isRunning) return

                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (device == null) return

                        val rssiShort = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        val rssi = if (rssiShort == Short.MIN_VALUE) -127 else rssiShort.toInt()

                        val name = safeDeviceName(device)
                        val address = device.address ?: return

                        if (seenAddresses.add(address)) {
                            // new unique device
                        }

                        val found = FoundBleDevice(
                            name = name,
                            address = address,
                            rssi = rssi,
                            bondState = device.bondState
                        )
                        lastDevice = found
                        onDeviceUpdate(seenAddresses.size, found)

                        if (!matches(profile, found)) return

                        onStatus("Match: ${found.nameOrFallback} (RSSI $rssi).")

                        onCandidateFound(found)

                        if (profile.stopOnMatch) {
                            // Stop discovery ASAP if configured
                            stopInternal()
                        }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // If window expires, stopRunnable handles status. We can still stop here.
                        if (isRunning) {
                            stopInternal()
                            onStatus("Discovery finished. Found ${seenAddresses.size} unique device(s).")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }

        context.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun stopInternal() {
        isRunning = false

        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null

        // Stop discovery
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter
        try {
            if (adapter != null && adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
        } catch (_: Throwable) {
            // ignore
        }

        // Unregister receiver
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {
                // ignore
            } finally {
                receiverRegistered = false
                receiver = null
            }
        }
    }

    private fun findMatchInConnected(connected: List<BluetoothDevice>, profile: DeviceProfiles.DeviceProfile): FoundBleDevice? {
        for (dev in connected) {
            val address = dev.address ?: continue
            val name = safeDeviceName(dev)

            val candidate = FoundBleDevice(
                name = name,
                address = address,
                rssi = 0, // unknown for already-connected devices
                bondState = dev.bondState
            )

            // For connected devices, ignore RSSI threshold (RSSI is not meaningful here).
            val nameOk =
                if (profile.matchSpec.nameWildcards.isEmpty()) true
                else {
                    val n = (candidate.name ?: "").lowercase(Locale.US)
                    if (n.isBlank()) false else profile.matchSpec.nameWildcards.any {
                        wildcardMatch(n, it.lowercase(Locale.US))
                    }
                }

            if (nameOk) return candidate
        }
        return null
    }

    private fun safeDeviceName(device: BluetoothDevice): String? {
        return try {
            // Requires BLUETOOTH_CONNECT on API 31+
            device.name
        } catch (_: SecurityException) {
            null
        }
    }

    private fun matches(profile: DeviceProfiles.DeviceProfile, d: FoundBleDevice): Boolean {
        // RSSI threshold (only meaningful during discovery; connected check ignores it)
        if (d.rssi < profile.matchSpec.minRssi) return false

        val name = (d.name ?: "").lowercase(Locale.US)
        val patterns = profile.matchSpec.nameWildcards.map { it.lowercase(Locale.US) }

        if (patterns.isEmpty()) return true
        if (name.isBlank()) return false

        return patterns.any { wildcardMatch(name, it) }
    }

    private fun wildcardMatch(text: String, pattern: String): Boolean {
        // Supports '*' only.
        val p = pattern
        if (p == "*") return true
        val parts = p.split("*").filter { it.isNotEmpty() }
        if (!p.contains("*")) return text == p

        var idx = 0
        var first = true

        for (part in parts) {
            val foundAt = text.indexOf(part, startIndex = idx)
            if (foundAt < 0) return false
            if (first && !p.startsWith("*") && foundAt != 0) return false
            idx = foundAt + part.length
            first = false
        }

        if (!p.endsWith("*")) {
            val lastPart = parts.lastOrNull()
            if (lastPart != null && !text.endsWith(lastPart)) return false
        }

        return true
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-31: classic discovery generally did not require location,
            // but many apps still request it. We keep scan permissive here.
            true
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun closeProfileProxy(adapter: BluetoothAdapter, profile: Int, proxy: BluetoothProfile) {
        try {
            adapter.closeProfileProxy(profile, proxy)
        } catch (_: Throwable) {
            // ignore
        }
    }

    private val FoundBleDevice.nameOrFallback: String
        get() = (this.name?.takeIf { it.isNotBlank() } ?: "(no name)")
}
