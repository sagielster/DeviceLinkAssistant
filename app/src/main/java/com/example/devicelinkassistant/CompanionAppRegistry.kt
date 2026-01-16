package com.example.devicelinkassistant

import com.example.devicelinkassistant.ble.DeviceProfiles

/**
 * Maps your IoT "device type" (what you show in the dropdown) to the companion app
 * you want to launch/install.
 *
 * You MUST fill these in with the real Play Store package names for your supported devices.
 */
data class CompanionAppSpec(
    // This MUST match what your UI / device profiles use as the "device type" label.
    val iotDisplayName: String,

    // What you show to the user as the "companion app" name.
    val displayName: String,

    // Android package name if you know it (lets you open the installed app directly).
    val packageName: String? = null,

    // If packageName is unknown or not installed, this is what you search in Play Store.
    val playStoreQuery: String? = null,

    // Some devices may not need a companion app.
    val noCompanionApp: Boolean = false,

    // Optional instructions for the user if there's no app or special steps.
    val instructions: String? = null
)

object CompanionAppRegistry {

    // TODO: Replace placeholders with real package names / queries.
    private val ALL: List<CompanionAppSpec> = listOf(
        CompanionAppSpec(
            iotDisplayName = DeviceProfiles.ECHO_SHOW_DISPLAY_NAME,
            displayName = "Amazon Alexa",
            packageName = "com.amazon.dee.app",
            playStoreQuery = "Amazon Alexa"
        ),
        CompanionAppSpec(
            iotDisplayName = "ONN Portable Karaoke",
            displayName = "ONN Karaoke companion",
            // packageName = "com.example.real.onn.karaoke", // <-- put the real one here if it exists
            playStoreQuery = "onn karaoke"
        ),
        CompanionAppSpec(
            iotDisplayName = "Beats Studio Pro",
            displayName = "Beats",
            // Example only — verify the real package name you want to open/install.
            playStoreQuery = "beats app"
        )
    )

    fun findByIotDisplayName(iotDisplayName: String): CompanionAppSpec? {
        return ALL.firstOrNull {
            it.iotDisplayName.equals(iotDisplayName, ignoreCase = true)
        }
    }

    fun all(): List<CompanionAppSpec> = ALL
}
