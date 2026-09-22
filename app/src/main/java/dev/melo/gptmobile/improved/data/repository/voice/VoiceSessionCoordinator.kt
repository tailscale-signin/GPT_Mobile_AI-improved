package dev.melo.gptmobile.improved.data.repository.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine managing full-duplex voice conversation lifecycle.
 * States: IDLE → LISTENING → TRANSCRIBING → THINKING → SPEAKING → IDLE
 */
class VoiceSessionCoordinator @Inject constructor() {
    sealed class VoiceState {
        object IDLE : VoiceState()
        object LISTENING : VoiceState()
        object TRANSCRIBING : VoiceState()
        object THINKING : VoiceState()
        object SPEAKING : VoiceState()
        data class ERROR(val message: String) : VoiceState()
    }

    private val _state = MutableStateFlow<VoiceState>(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var isSessionActive = false

    /**
     * Start listening for voice input.
     */
    suspend fun startListening(): VoiceState {
        return with(_state) {
            if (value != VoiceState.IDLE) return@with value
            _state.value = VoiceState.LISTENING
            VoiceState.LISTENING
        }
    }

    /**
     * Transition to transcribing state after speech recognition completes.
     */
    suspend fun startTranscribing(): VoiceState {
        return with(_state) {
            if (value != VoiceState.LISTENING) return@with value
            _state.value = VoiceState.TRANSCRIBING
            VoiceState.TRANSCRIBING
        }
    }

    /**
     * Transition to thinking state after transcription is complete.
     */
    suspend fun startThinking(): VoiceState {
        return with(_state) {
            if (value != VoiceState.TRANSCRIBING) return@with value
            _state.value = VoiceState.THINKING
            VoiceState.THINKING
        }
    }

    /**
     * Transition to speaking state after generation completes.
     */
    suspend fun startSpeaking(): VoiceState {
        return with(_state) {
            if (value != VoiceState.THINKING) return@with value
            _state.value = VoiceState.SPEAKING
            VoiceState.SPEAKING
        }
    }

    /**
     * Return to idle state.
     */
    suspend fun stopSession(): VoiceState {
        return with(_state) {
            isSessionActive = false
            _state.value = VoiceState.IDLE
            VoiceState.IDLE
        }
    }

    /**
     * Handle user interruption during any state.
     */
    suspend fun handleUserInterruption(): VoiceState {
        return with(_state) {
            when (value) {
                VoiceState.LISTENING, VoiceState.TRANSCRIBING -> {
                    _state.value = VoiceState.IDLE
                    VoiceState.IDLE
                }
                VoiceState.THINKING, VoiceState.SPEAKING -> {
                    _state.value = VoiceState.LISTENING
                    VoiceState.LISTENING
                }
                else -> value
            }
        }
    }

    /**
     * Check if session is active.
     */
    fun isActive(): Boolean = isSessionActive

    /**
     * Get current state name for display.
     */
    fun getStateDisplayName(): String {
        return when (val state = _state.value) {
            VoiceState.IDLE -> "Idle"
            VoiceState.LISTENING -> "Listening..."
            VoiceState.TRANSCRIBING -> "Transcribing..."
            VoiceState.THINKING -> "Thinking..."
            VoiceState.SPEAKING -> "Speaking..."
            is VoiceState.ERROR -> "Error: ${state.message}"
        }
    }

    /**
     * Can transition to listening state?
     */
    fun canStartListening(): Boolean = _state.value == VoiceState.IDLE

    /**
     * Can interrupt current session?
     */
    fun canInterrupt(): Boolean = _state.value in listOf(
        VoiceState.LISTENING,
        VoiceState.TRANSCRIBING,
        VoiceState.THINKING,
        VoiceState.SPEAKING
    )

    override fun close() {
        stopSession()
    }
}