package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.ollama.OllamaModelEntry

sealed interface OllamaServerUiState {
    object Idle : OllamaServerUiState
    object Checking : OllamaServerUiState
    data class Connected(
        val version: String?,
        val latencyMs: Long,
        val models: List<OllamaModelEntry>
    ) : OllamaServerUiState
    data class Error(val message: String) : OllamaServerUiState
}
