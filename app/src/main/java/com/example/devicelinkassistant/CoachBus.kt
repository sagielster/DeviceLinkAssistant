package com.example.devicelinkassistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CoachBus {
    private val _state = MutableStateFlow<CoachState>(CoachState.Idle)
    val state: StateFlow<CoachState> = _state

    fun update(s: CoachState) {
        _state.value = s
    }
}
