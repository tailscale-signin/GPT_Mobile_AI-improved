package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.voice.VoicePhase
import dev.chungjungsoo.gptmobile.data.voice.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/** The first capture starts discovery once. Later turns reuse it even while the microphone is idle. */
internal suspend fun prepareWhileListening(
    states: StateFlow<VoiceState>,
    warmCatalogues: suspend () -> Unit,
    warmMemory: suspend () -> Unit,
    searchMemory: suspend (String) -> Unit
) {
    states.first { it.phase == VoicePhase.STARTING || it.phase == VoicePhase.LISTENING }
    supervisorScope {
        launch(Dispatchers.IO) { optionalVoicePreparation(warmCatalogues) }
        launch(Dispatchers.IO) { optionalVoicePreparation(warmMemory) }
        states.map { state ->
            val finalText = state.phase == VoicePhase.FINALIZING ||
                state.phase == VoicePhase.REVIEWING ||
                state.phase == VoicePhase.THINKING &&
                (state.awaitingReply || state.submittedDraftRequestId != null)
            if (state.phase == VoicePhase.LISTENING || finalText) state.transcript.trim() to finalText else null
        }.distinctUntilChanged().collectLatest { input ->
            if (input == null || input.first.isBlank()) return@collectLatest
            val (text, finalText) = input
            if (!finalText) delay(150)
            optionalVoicePreparation { searchMemory(text) }
        }
    }
}

private suspend fun optionalVoicePreparation(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The submitted turn still has its normal discovery and recall paths.
    } catch (_: LinkageError) {
        // Native search is optional on devices without a compatible runtime.
    }
}
