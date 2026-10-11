package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Withhold provider text that contains unresolved wire-level tool instructions. */
internal fun Flow<ProviderEvent>.withAssistantTextMarkupGuard(): Flow<ProviderEvent> = flow {
    val guard = AssistantTextMarkupGuard()
    var blocked = false

    suspend fun finishText() {
        if (blocked) return
        val result = guard.finish()
        if (result.text.isNotEmpty()) emit(ProviderEvent.TextDelta(result.text))
        if (result.blocked) {
            blocked = true
            emit(ProviderEvent.Failed("The provider returned unparsed tool-call markup. The raw control payload was withheld."))
        }
    }

    collect { event ->
        if (blocked) {
            if (event is ProviderEvent.Usage || event is ProviderEvent.LocalMetrics) emit(event)
            return@collect
        }
        when (event) {
            is ProviderEvent.TextDelta -> {
                val result = guard.append(event.text)
                if (result.text.isNotEmpty()) emit(ProviderEvent.TextDelta(result.text))
                if (result.blocked) {
                    blocked = true
                    emit(ProviderEvent.Failed("The provider returned unparsed tool-call markup. The raw control payload was withheld."))
                }
            }
            ProviderEvent.Completed -> {
                finishText()
                if (!blocked) emit(event)
            }
            else -> emit(event)
        }
    }
    finishText()
}

private class AssistantTextMarkupGuard {
    data class Result(val text: String = "", val blocked: Boolean = false)

    private val pending = StringBuilder()
    private var blocked = false
    private val wireMarkup = Regex("(?is)</?(?:tool_call|function_call|tool_result|invoke)\\b[^>]*>|<\\|(?:tool_call|function_call|im_start)\\|>")
    private val assistantToolLine = Regex("(?im)^\\s*assistant\\s+to=[A-Za-z0-9_.-]+\\s+(?:\\[|\\{)")
    private val partialTags = listOf("<tool_call", "<function_call", "<tool_result", "<invoke", "</tool_call", "</function_call", "</tool_result", "</invoke", "<|tool_call|>", "<|function_call|>", "<|im_start|>")

    fun append(text: String): Result {
        if (blocked) return Result(blocked = true)
        pending.append(text)
        val rawStart = listOfNotNull(wireMarkup.find(pending)?.range?.first, assistantToolLine.find(pending)?.range?.first).minOrNull()
        if (rawStart != null) {
            val safe = pending.substring(0, rawStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true)
        }

        val possibleStart = possiblePartialStart(pending)
        val safeEnd = possibleStart ?: pending.length
        val safe = pending.substring(0, safeEnd)
        pending.delete(0, safeEnd)
        if (pending.length > MAX_PENDING_CHARACTERS) {
            pending.clear()
            blocked = true
            return Result(safe, blocked = true)
        }
        return Result(safe)
    }

    fun finish(): Result {
        if (blocked) return Result(blocked = true)
        val rawStart = listOfNotNull(wireMarkup.find(pending)?.range?.first, assistantToolLine.find(pending)?.range?.first).minOrNull()
        if (rawStart != null) {
            val safe = pending.substring(0, rawStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true)
        }
        if (possiblePartialStart(pending) != null) {
            pending.clear()
            blocked = true
            return Result(blocked = true)
        }
        val safe = pending.toString()
        pending.clear()
        return Result(safe)
    }

    private fun possiblePartialStart(value: CharSequence): Int? {
        val text = value.toString()
        var candidate: Int? = null
        var start = text.lastIndexOf('<')
        while (start >= 0) {
            val suffix = text.substring(start)
            if (partialTags.any { token -> token.startsWith(suffix, ignoreCase = true) || (suffix.startsWith(token, ignoreCase = true) && '>' !in suffix) }) {
                candidate = start
            }
            start = text.lastIndexOf('<', start - 1)
        }
        val lineStart = text.lastIndexOf('\n') + 1
        val lineTail = text.substring(lineStart)
        if (lineTail.trimStart().startsWith("assistant to=", ignoreCase = true) && assistantToolLine.find(lineTail) == null) {
            candidate = minOf(candidate ?: lineStart, lineStart)
        }
        return candidate
    }

    private companion object {
        const val MAX_PENDING_CHARACTERS = 512
    }
}
