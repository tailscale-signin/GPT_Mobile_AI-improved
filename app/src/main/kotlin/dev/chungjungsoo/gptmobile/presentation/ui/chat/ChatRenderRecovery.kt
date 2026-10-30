package dev.chungjungsoo.gptmobile.presentation.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal data class RenderBudget(val timeoutMs: Long, val attempts: Int)

internal fun renderBudget(characters: Int): RenderBudget = when {
    characters < 2_000 -> RenderBudget(10_000, 1)
    characters < 4_000 -> RenderBudget(20_000, 2)
    characters < 8_000 -> RenderBudget(30_000, 3)
    characters < 20_000 -> RenderBudget(45_000, 3)
    else -> RenderBudget(60_000, 3)
}

/** Retrying formatting is local CPU work. It must never re-send a model/tool request. */
internal suspend fun <T : Any> recoverChatRender(
    characters: Int,
    streaming: Boolean,
    budget: RenderBudget = renderBudget(characters),
    stillCurrent: () -> Boolean = { true },
    progress: (String) -> Unit = {},
    prepare: suspend () -> T
): T? {
    val attempts = if (streaming) 1 else budget.attempts
    for (attempt in 1..attempts) {
        if (!stillCurrent()) return null
        progress(if (attempt == 1) "Formatting response" else "Retrying formatting ($attempt/$attempts)")
        val started = System.nanoTime()
        try {
            val value = withTimeoutOrNull(budget.timeoutMs) { prepare() }
            ChatRuntimeHealth.renderResult(value != null, (System.nanoTime() - started) / 1_000_000, if (value == null) "TIMEOUT" else null)
            if (value != null) return value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Deterministic parser failures use readable text immediately.
            ChatRuntimeHealth.renderResult(false, (System.nanoTime() - started) / 1_000_000, failure.javaClass.simpleName)
            return null
        }
        if (attempt < attempts && stillCurrent()) delay((250L shl (attempt - 1)).coerceAtMost(2_000L))
    }
    return null
}
