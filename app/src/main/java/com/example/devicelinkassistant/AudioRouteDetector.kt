package com.example.devicelinkassistant

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log

object AudioRouteDetector {

    /**
     * Returns "ONN", "ECHO", or "UNKNOWN" based on the current MEDIA output route.
     * This is best-effort and inspects current output devices.
     */
    fun getCurrentMediaRoute(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Normalize product names once
        val a2dpNames = devices
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            .mapNotNull { it.productName?.toString()?.lowercase() }

        // Distinguish routes using the exact A2DP product names we observed in RouteDump:
        // - ONN:  "onn Portable Karaoke"
        // - Echo: "Echo Show 5-HCF" (and similar "echo show" names)
        if (a2dpNames.any { it.contains("onn portable karaoke") }) {
            return "ONN"
        }
        if (a2dpNames.any { it.contains("echo show") }) {
            return "ECHO"
        }

        // Otherwise assume "ECHO" for common local outputs (phone speaker / wired)
        val hasLocalOut = devices.any { d ->
            d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }
        if (hasLocalOut) return "ECHO"

        return "UNKNOWN"
    }

    /**
     * DEBUG helper: dump all current output devices so we can see
     * exactly what Android calls your ONN / Echo routes.
     */
    fun debugDumpOutputs(context: Context, tag: String = "RouteDump") {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        Log.i(tag, "---- OUTPUT DEVICES (${devices.size}) ----")
        devices.forEach { d ->
            val typeName = when (d.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
                AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
                else -> "TYPE_${d.type}"
            }

            val name = d.productName?.toString() ?: "(null)"
            Log.i(tag, "type=$typeName name=$name id=${d.id}")
        }

        Log.i(tag, "------------------------------")
    }
}
