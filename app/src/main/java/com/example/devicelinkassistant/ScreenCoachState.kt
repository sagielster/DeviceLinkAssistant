package com.example.devicelinkassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process state bus for Coach mode.
 * The foreground service updates this; Compose UI observes it.
 */
object ScreenCoachState {
    private val _hint = MutableStateFlow("")
    val hint: StateFlow<String> = _hint

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun setHint(text: String) {
        _hint.value = text
    }

    fun clear() {
        _hint.value = ""
        _isRunning.value = false
    }
}
