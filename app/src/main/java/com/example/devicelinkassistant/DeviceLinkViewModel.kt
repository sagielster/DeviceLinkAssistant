package com.example.devicelinkassistant

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LinkStep {
    CAPTURE,
    PAIRING_INSTRUCTION,
    REASONING,
    ACTING,
    OUTCOME
}

data class UiState(
    val step: LinkStep = LinkStep.CAPTURE,
    val photo1: Uri? = null,
    val photo2: Uri? = null,
    val busy: Boolean = false,

    // reasoning outputs
    val plan: LinkPlan? = null,

    // progress/status
    val statusLine: String = "",

    // outcome
    val outcomeTitle: String = "",
    val outcomeBody: String = "",

    // dev visibility
    val debugLine: String = ""
)

data class LinkPlan(
    val deviceA: String,
    val deviceB: String,
    val primary: Transport,
    val fallback: Transport,
    val explanation: String
)

enum class Transport {
    BLUETOOTH_AUDIO,
    ROKU_NETWORK,
    HDMI_ARC_OR_OPTICAL,
    AUX_CABLE,
    IMPOSSIBLE
}

class DeviceLinkViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    // Option B: phone stays the Bluetooth hub. Alexa is only used as a voice remote/controller.

    fun setPhoto(which: Int, uri: Uri?) {
        _ui.update {
            when (which) {
                0 -> it.copy(photo1 = uri, debugLine = "photo1=$uri")
                1 -> it.copy(photo2 = uri, debugLine = "photo2=$uri")
                else -> it
            }
        }
    }

    fun continueFromCapture() {
        val s = _ui.value
        if (s.photo1 == null || s.photo2 == null) return
        _ui.update { it.copy(step = LinkStep.PAIRING_INSTRUCTION) }
    }

    fun userConfirmedPairingMode() {
        // Next: reasoning step, call model endpoint that looks at both photos.
        _ui.update { it.copy(step = LinkStep.REASONING, busy = true, debugLine = "reasoning...") }

        viewModelScope.launch {
            try {
                // MVP: stub reasoning.
                // Replace this with a real call: LlmClient.identifyAndPlan(photo1, photo2)
                delay(800)

                val plan = LinkPlan(
                    deviceA = "ONN karaoke machine",
                    deviceB = "Roku/TV",
                    primary = Transport.BLUETOOTH_AUDIO,
                    fallback = Transport.AUX_CABLE,
                    explanation = "Most karaoke machines output audio. Many TVs do not accept Bluetooth audio input. Try Bluetooth first; if TV cannot pair as a speaker, use a cable method."
                )

                _ui.update {
                    it.copy(
                        busy = false,
                        plan = plan,
                        debugLine = "plan=$plan"
                    )
                }
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        busy = false,
                        step = LinkStep.OUTCOME,
                        outcomeTitle = "Hard limit",
                        outcomeBody = "I could not analyze the photos. ${e.message ?: ""}",
                        debugLine = "reasoning error: ${e.stackTraceToString()}"
                    )
                }
            }
        }
    }

    /**
     * Option B “Phone Hub” karaoke mode:
     * - Keep ONN paired/connected to the PHONE
     * - Open Alexa so the user can run a routine or issue a voice command that targets the phone
     * - No Echo↔ONN pairing flow is required
     */
    fun startKaraokeModePhoneHub() {
        _ui.update {
            it.copy(
                step = LinkStep.OUTCOME,
                busy = false,
                outcomeTitle = "Phone Hub karaoke mode",
                outcomeBody =
                    "1) Keep the ONN karaoke machine connected to THIS phone over Bluetooth.\n" +
                    "2) I will open the Alexa app.\n" +
                    "3) In Alexa, run your Karaoke routine or say a command that targets this phone (example: “Alexa, play karaoke hits on this phone”).\n" +
                    "4) Audio will route phone → ONN. The Echo is just the voice remote.",
                debugLine = "mode=phone_hub_alexa_remote"
            )
        }

        // Open Alexa as the voice remote/controller.
        HubIntegrations.openAlexaApp(getApplication())
    }

    /**
     * Optional helper you can wire to a UI button:
     * if audio isn’t coming out of ONN, jump straight to Bluetooth settings.
     */
    fun openBluetoothSettings() {
        HubIntegrations.openBluetoothSettings(getApplication())
    }

    fun beginActing() {
        val plan = _ui.value.plan ?: run {
            _ui.update { it.copy(step = LinkStep.OUTCOME, outcomeTitle = "Hard limit", outcomeBody = "No plan was generated.") }
            return
        }

        _ui.update { it.copy(step = LinkStep.ACTING, busy = true, statusLine = "Attempting ${plan.primary}...") }

        viewModelScope.launch {
            // MVP: stub acting.
            // Replace with real transport attempts:
            // - Bluetooth: discovery + pair + connect A2DP where possible
            // - Roku: discover on LAN + send commands
            // - Cable: instruct user
            delay(900)

            val result = when (plan.primary) {
                Transport.BLUETOOTH_AUDIO -> {
                    // Reality: TVs often do not accept BT audio input.
                    // Your acting layer should detect whether TV exposes BT sink.
                    // MVP: assume it fails, then fall back.
                    _ui.update { it.copy(statusLine = "TV did not accept Bluetooth audio. Switching to cable method...") }
                    delay(900)
                    "fallback"
                }
                else -> "fallback"
            }

            if (result == "fallback") {
                _ui.update {
                    it.copy(
                        busy = false,
                        outcomeTitle = "Alternate path",
                        outcomeBody = "This TV doesn’t accept Bluetooth audio. Use the simplest cable method: connect karaoke audio-out to the TV audio-in (or to a soundbar) with the appropriate adapter.",
                        debugLine = "primary failed, fallback=${plan.fallback}"
                    )
                }
            } else {
                _ui.update {
                    it.copy(
                        busy = false,
                        outcomeTitle = "Success",
                        outcomeBody = "They’re connected. I’ll remember this setup.",
                        debugLine = "success via ${plan.primary}"
                    )
                }
            }
        }
    }

    fun finish() {
        // Move to outcome screen using whatever outcome fields are already set.
        _ui.update { it.copy(step = LinkStep.OUTCOME) }
    }

    fun reset() {
        _ui.value = UiState()
    }
}
