package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Withhold provider text that contains unresolved wire-level tool instructions. */
internal fun Flow<ProviderEvent>.withAssistantTextMarkupGuard(): Flow<ProviderEvent> = flow {
    val guard = AssistantTextMarkupGuard()
    var blocked = false
    var successfulWebSearch = false

    suspend fun finishText() {
        if (blocked) return
        val result = guard.finish(successfulWebSearch)
        if (result.text.isNotEmpty()) emit(ProviderEvent.TextDelta(result.text))
        if (result.blocked) {
            blocked = true
            logRejectedMarkup(result.reason.orEmpty())
            emit(ProviderEvent.Failed(result.failureMessage()))
        }
    }

    collect { event ->
        if (event is ProviderEvent.ToolResult &&
            !event.result.isError &&
            event.call.name.contains("web_search", ignoreCase = true)
        ) {
            successfulWebSearch = true
        }
        if (blocked) {
            if (event is ProviderEvent.Usage || event is ProviderEvent.LocalMetrics) emit(event)
            return@collect
        }
        when (event) {
            is ProviderEvent.TextDelta -> {
                val result = guard.append(event.text, successfulWebSearch)
                if (result.text.isNotEmpty()) emit(ProviderEvent.TextDelta(result.text))
                if (result.blocked) {
                    blocked = true
                    logRejectedMarkup(result.reason.orEmpty())
                    emit(ProviderEvent.Failed(result.failureMessage()))
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

private fun logRejectedMarkup(reason: String) = AppLogRecorder.record(
    "Tools",
    "TOOL_CALL_REJECTED · reason=$reason · dispatched=false",
    "W"
)

private class AssistantTextMarkupGuard {
    data class Result(val text: String = "", val blocked: Boolean = false, val reason: String? = null) {
        fun failureMessage(): String = when (reason) {
            "unsupported_search_claim" -> "The model claimed a web search that has no successful web-search tool result. That claim was withheld."
            else -> "The provider returned unparsed tool-call markup. The raw control payload was withheld."
        }
    }

    private val pending = StringBuilder()
    private var blocked = false
    private val wireMarkup = Regex("(?is)</?(?:tool_call|function_call|tool_result|invoke)\\b[^>]*>|<\\|(?:tool_call|function_call)(?:\\|)?>|<\\|im_start\\|>")
    private val assistantToolLine = Regex("(?im)^\\s*assistant\\s+to=[A-Za-z0-9_.-]+\\s+(?:\\[|\\{)")
    private val partialTags = listOf("<tool_call", "<function_call", "<tool_result", "<invoke", "</tool_call", "</function_call", "</tool_result", "</invoke", "<|tool_call|>", "<|tool_call>", "<|function_call|>", "<|function_call>", "<|im_start|>")
    private val searchClaim = Regex("(?i)\\b(?:i|we)\\s+(?:have\\s+)?(?:searched|looked up|checked)\\s+(?:the\\s+)?(?:web|internet|online|latest news|current sources)\\b|\\b(?:my|our)\\s+(?:web|internet)\\s+search\\b")

    fun append(text: String, successfulWebSearch: Boolean): Result {
        if (blocked) return Result(blocked = true)
        pending.append(text)
        val rawStart = listOfNotNull(wireMarkup.find(pending)?.range?.first, assistantToolLine.find(pending)?.range?.first).minOrNull()
        if (rawStart != null) {
            val safe = pending.substring(0, rawStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_or_unparsed_markup")
        }
        if (!successfulWebSearch && searchClaim.find(pending) != null) {
            val claimStart = searchClaim.find(pending)!!.range.first
            val safe = pending.substring(0, claimStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_search_claim")
        }

        val possibleStart = possiblePartialStart(pending)
        val claimTail = if (successfulWebSearch) 0 else SEARCH_CLAIM_TAIL_CHARACTERS
        val claimSafeEnd = (pending.length - claimTail).coerceAtLeast(0)
        val safeEnd = minOf(possibleStart ?: pending.length, claimSafeEnd)
        val safe = pending.substring(0, safeEnd)
        pending.delete(0, safeEnd)
        if (pending.length > MAX_PENDING_CHARACTERS) {
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "oversized_unparsed_markup")
        }
        return Result(safe)
    }

    fun finish(successfulWebSearch: Boolean): Result {
        if (blocked) return Result(blocked = true)
        val rawStart = listOfNotNull(wireMarkup.find(pending)?.range?.first, assistantToolLine.find(pending)?.range?.first).minOrNull()
        if (rawStart != null) {
            val safe = pending.substring(0, rawStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_or_unparsed_markup")
        }
        if (!successfulWebSearch && searchClaim.find(pending) != null) {
            val claimStart = searchClaim.find(pending)!!.range.first
            val safe = pending.substring(0, claimStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_search_claim")
        }
        if (possiblePartialStart(pending) != null) {
            pending.clear()
            blocked = true
            return Result(blocked = true, reason = "unsupported_or_unparsed_markup")
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
        const val SEARCH_CLAIM_TAIL_CHARACTERS = 96
    }
}
