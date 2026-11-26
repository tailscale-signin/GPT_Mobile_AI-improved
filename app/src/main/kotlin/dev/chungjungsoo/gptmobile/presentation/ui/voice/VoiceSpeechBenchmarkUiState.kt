package dev.chungjungsoo.gptmobile.presentation.ui.voice

import dev.chungjungsoo.gptmobile.data.voice.VoiceBenchManifest
import dev.chungjungsoo.gptmobile.data.voice.VoiceSpeechBenchmarkReport

internal data class VoiceSpeechBenchmarkUiState(
    val loaded: Boolean = false,
    val running: Boolean = false,
    val cancelling: Boolean = false,
    val report: VoiceSpeechBenchmarkReport? = null,
    val history: List<VoiceSpeechBenchmarkReport> = emptyList(),
    val saved: Boolean = true,
    val notice: String? = null,
    val nativeCleanupPending: Boolean = false,
    val suite: VoiceBenchManifest? = null
) {
    fun withQuarantine(reason: VoiceSpeechBenchmarkOwnership.QuarantineReason?): VoiceSpeechBenchmarkUiState =
        if (reason == null) this else copy(nativeCleanupPending = true, notice = reason.notice)
}
