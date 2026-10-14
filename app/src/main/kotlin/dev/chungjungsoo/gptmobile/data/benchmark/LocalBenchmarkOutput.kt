package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceMetrics
import dev.chungjungsoo.gptmobile.data.localruntime.LocalRuntimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

internal data class LocalBenchmarkOutput(val completed: Boolean, val outputCharacters: Int, val failure: String?)

/** A native ordering fence must be acknowledged even when benchmarking without a chat adapter. */
internal suspend fun collectLocalBenchmarkOutput(
    events: Flow<LocalRuntimeEvent>,
    onOutputCharacters: (Int) -> Unit = { },
    onMetrics: (LocalInferenceMetrics) -> Unit
): LocalBenchmarkOutput {
    var done = false
    var characters = 0
    var visibleOutput = false
    var failure: String? = null
    events.collect { event ->
        when (event) {
            is LocalRuntimeEvent.Drain -> event.acknowledged.complete(Unit)
            is LocalRuntimeEvent.TextDelta -> {
                characters = (characters.toLong() + event.text.length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                onOutputCharacters(characters)
                visibleOutput = visibleOutput || event.text.isNotBlank()
            }
            is LocalRuntimeEvent.Metrics -> onMetrics(event.metrics)
            is LocalRuntimeEvent.Error -> if (failure == null) {
                failure = event.message.ifBlank { "LOCAL_RUNTIME_ERROR: the native engine reported an error without details." }
            }
            LocalRuntimeEvent.Done -> done = true
            else -> Unit
        }
    }
    if (failure == null) {
        failure = when {
            !done -> "LOCAL_STREAM_INCOMPLETE: inference ended without a completion event."
            !visibleOutput -> "LOCAL_EMPTY_OUTPUT: inference completed without visible answer text."
            else -> null
        }
    }
    return LocalBenchmarkOutput(done && failure == null, characters, failure)
}
