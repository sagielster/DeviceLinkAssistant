package com.example.devicelinkassistant

sealed class CoachState(val message: String) {
    object Idle : CoachState("Coach mode is idle.")
    object RequestingPermission : CoachState("Requesting screen capture permission…")
    object StartingCapture : CoachState("Starting screen capture…")
    object Scanning : CoachState("Scanning the screen for the next setup step…")
    data class TargetCandidate(val label: String) : CoachState("Found \"$label\". Verifying…")
    data class TargetLocked(val label: String) : CoachState("Tap \"$label\" to continue.")
    object LostTarget : CoachState("Lost the target. Rescanning…")
    data class Error(val detail: String) : CoachState("Coach error: $detail")
}
