package com.example.devicelinkassistant.ble

object DeviceProfiles {

    // Display name used across UI + companion app registry
    const val ECHO_SHOW_DISPLAY_NAME = "Amazon Echo Show"

    data class MatchSpec(
        // Wildcards matched against the *device name* (case-insensitive).
        // Examples: "onn*", "*karaoke*", "*beats*"
        val nameWildcards: List<String> = emptyList(),

        // If true, devices with null/blank name are allowed to pass name filtering.
        // This is critical for devices that show up as "(no name)" in classic discovery.
        val allowUnnamed: Boolean = true,

        // Ignore very weak signals if you want (e.g., -90)
        val minRssi: Int = -127,

        // If more than one candidate matches, only auto-pair when the strongest signal
        // is ahead of the runner-up by at least this many dB.
        // Helps avoid pairing to random neighbors.
        val rssiDominanceDb: Int = 6
    )

    data class DeviceProfile(
        val displayName: String,
        val matchSpec: MatchSpec,
        val scanWindowMs: Long = 12_000L,

        // If true, will stop scanning as soon as it commits to pairing.
        val stopOnMatch: Boolean = true
    )

    val ALL: List<DeviceProfile> = listOf(
        // Echo Show is usually provisioned over Wi-Fi with the Alexa app. We include it
        // here so the user can choose it as a device type and we can open the right companion app.
        // If the user is pairing Echo Show as a Bluetooth speaker, the name wildcards below may help discovery.
        DeviceProfile(
            displayName = ECHO_SHOW_DISPLAY_NAME,
            matchSpec = MatchSpec(
                nameWildcards = listOf(
                    "*echo show*",
                    "*echoshow*",
                    "*amazon echo*",
                    "*echo-*",
                    "*echo *"
                )
            ),
            scanWindowMs = 20_000L,
            stopOnMatch = false
        ),
        DeviceProfile(
            displayName = "ONN Portable Karaoke",
            matchSpec = MatchSpec(
                nameWildcards = listOf("onn*", "*karaoke*", "*onn*"),
                allowUnnamed = true,
                minRssi = -90,
                rssiDominanceDb = 6
            ),
            scanWindowMs = 20_000L,
            stopOnMatch = true
        ),
        DeviceProfile(
            displayName = "Beats Studio Pro",
            matchSpec = MatchSpec(
                nameWildcards = listOf("*beats*", "*studio*", "*studio pro*"),
                allowUnnamed = true,
                minRssi = -90,
                rssiDominanceDb = 6
            ),
            scanWindowMs = 20_000L,
            stopOnMatch = true
        )
    )

    fun byDisplayName(displayName: String): DeviceProfile {
        return ALL.firstOrNull { it.displayName == displayName } ?: ALL.first()
    }
}
