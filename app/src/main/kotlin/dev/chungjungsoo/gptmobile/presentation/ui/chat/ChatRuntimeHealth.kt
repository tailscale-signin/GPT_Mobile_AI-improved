package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ChatMemorySample(val usedHeapBytes: Long = 0, val maxHeapBytes: Long = 1, val processPssKb: Long = 0, val systemLowMemory: Boolean = false) {
    val level: String get() = when {
        systemLowMemory || usedHeapBytes.toDouble() / maxHeapBytes.coerceAtLeast(1) >= 0.95 -> "critical"
        usedHeapBytes.toDouble() / maxHeapBytes.coerceAtLeast(1) >= 0.80 -> "warning"
        else -> "normal"
    }
}

internal data class RenderHealth(val completed: Long = 0, val failed: Long = 0, val durationsMs: List<Long> = emptyList(), val failures: List<String> = emptyList())

/** Aggregate diagnostics contain no answer text, prompts, credentials or source. */
internal object ChatRuntimeHealth {
    private val mutableMemory = MutableStateFlow(ChatMemorySample())
    val memory = mutableMemory.asStateFlow()
    private val mutableRender = MutableStateFlow(RenderHealth())
    val render = mutableRender.asStateFlow()

    @Synchronized fun memory(sample: ChatMemorySample) {
        mutableMemory.value = sample
    }

    @Synchronized fun renderResult(success: Boolean, elapsedMs: Long, failure: String?) {
        val old = mutableRender.value
        mutableRender.value = old.copy(
            completed = old.completed + if (success) 1 else 0,
            failed = old.failed + if (success) 0 else 1,
            durationsMs = (old.durationsMs + elapsedMs).takeLast(256),
            failures = if (failure == null) old.failures else (old.failures + failure.take(80)).takeLast(100)
        )
    }
}
