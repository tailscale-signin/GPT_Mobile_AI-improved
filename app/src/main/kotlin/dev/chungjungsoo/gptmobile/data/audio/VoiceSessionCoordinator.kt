package dev.chungjungsoo.gptmobile.data.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State coordinator for low-latency hands-free Voice Conversation mode.
 * Manages full-duplex session states: listening (VAD active), processing (STT/LLM),
 * and speaking (TTS playing) with immediate user-interruption handling.
 */
class VoiceSessionCoordinator {

    enum class VoiceState {
        IDLE,
        LISTENING,
        TRANSCRIBING,
        THINKING,
        SPEAKING,
        ERROR
    }

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _audioEnergyLevel = MutableStateFlow(0f)
    val audioEnergyLevel: StateFlow<Float> = _audioEnergyLevel.asStateFlow()

    fun startListening() {
        _voiceState.value = VoiceState.LISTENING
    }

    fun onSpeechDetected(energy: Float) {
        _audioEnergyLevel.value = energy.coerceIn(0f, 1f)
        // If system was speaking and user begins talking, interrupt immediately
        if (_voiceState.value == VoiceState.SPEAKING) {
            interrupt()
        }
    }

    fun onSilenceDetected() {
        if (_voiceState.value == VoiceState.LISTENING) {
            _voiceState.value = VoiceState.TRANSCRIBING
        }
    }

    fun onTranscriptionComplete() {
        _voiceState.value = VoiceState.THINKING
    }

    fun onPlaybackStart() {
        _voiceState.value = VoiceState.SPEAKING
    }

    fun onPlaybackComplete() {
        _voiceState.value = VoiceState.LISTENING
    }

    fun interrupt() {
        _voiceState.value = VoiceState.LISTENING
    }

    fun stopSession() {
        _voiceState.value = VoiceState.IDLE
        _audioEnergyLevel.value = 0f
    }

    fun onError(message: String) {
        _voiceState.value = VoiceState.ERROR
    }
}
