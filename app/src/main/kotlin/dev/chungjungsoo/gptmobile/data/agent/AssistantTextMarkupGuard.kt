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
    private var previousCharacter: Char? = null
    private var atLineStart = true
    private val wireMarkup = Regex("(?is)</?(?:tool_call|function_call|tool_result|invoke)\\b[^>]*>|<\\|(?:tool_call|function_call)(?:\\|)?>|<\\|im_start\\|>")
    private val assistantToolLine = Regex("(?im)^\\s*assistant\\s+to=[A-Za-z0-9_.-]+\\s+(?:\\[|\\{)")
    private val assistantToolPrefix = Regex("(?im)^\\s*assistant\\s+to=")
    private val partialTags = listOf("<tool_call", "<function_call", "<tool_result", "<invoke", "</tool_call", "</function_call", "</tool_result", "</invoke", "<|tool_call|>", "<|tool_call>", "<|function_call|>", "<|function_call>", "<|im_start|>")
    private val searchClaim = Regex("(?i)\\b(?:i|we)\\s+(?:have\\s+)?(?:searched|looked up|checked)\\s+(?:the\\s+)?(?:web|internet|online|latest news|current sources)\\b|\\b(?:my|our)\\s+(?:web|internet)\\s+search\\b")

    fun append(text: String, successfulWebSearch: Boolean): Result {
        if (blocked) return Result(blocked = true)
        pending.append(text)
        val rawStart = listOfNotNull(wireMarkup.find(pending)?.range?.first, patternStart(assistantToolLine, linePrefix())).minOrNull()
        if (rawStart != null) {
            val safe = pending.substring(0, rawStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_or_unparsed_markup")
        }
        val claimStart = if (successfulWebSearch) null else patternStart(searchClaim, wordPrefix())
        if (claimStart != null) {
            val safe = pending.substring(0, claimStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_search_claim")
        }

        val possibleStart = possiblePartialStart(pending)
        val possibleClaim = if (successfulWebSearch) null else patternStart(searchClaim, wordPrefix(), partial = true)
        val safeEnd = minOf(possibleStart ?: pending.length, possibleClaim ?: pending.length)
        val safe = drain(safeEnd)
        if (pending.length > MAX_PENDING_CHARACTERS) {
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "oversized_unparsed_markup")
        }
        return Result(safe)
    }

    fun finish(successfulWebSearch: Boolean): Result {
        if (blocked) return Result(blocked = true)
        val rawStart = listOfNotNull(wireMarkup.find(pending)?.range?.first, patternStart(assistantToolLine, linePrefix())).minOrNull()
        if (rawStart != null) {
            val safe = pending.substring(0, rawStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_or_unparsed_markup")
        }
        val claimStart = if (successfulWebSearch) null else patternStart(searchClaim, wordPrefix())
        if (claimStart != null) {
            val safe = pending.substring(0, claimStart)
            pending.clear()
            blocked = true
            return Result(safe, blocked = true, reason = "unsupported_search_claim")
        }
        if (possiblePartialStart(pending, finishing = true) != null) {
            pending.clear()
            blocked = true
            return Result(blocked = true, reason = "unsupported_or_unparsed_markup")
        }
        return Result(drain(pending.length))
    }

    // Keep only a suffix that can still become a prohibited pattern. A fixed
    // trailing buffer hides short replies until completion and loses them on
    // transport failure; it also prevents consumers from cancelling on text.
    private fun patternStart(pattern: Regex, prefix: String, partial: Boolean = false): Int? {
        val value = prefix + pending
        val matcher = pattern.toPattern().matcher(value).useTransparentBounds(true).useAnchoringBounds(false)
        if (!partial) {
            matcher.region(prefix.length, value.length)
            return if (matcher.find()) matcher.start() - prefix.length else null
        }
        // Whitespace alone can stream; drain preserves whether the line is empty.
        val lastContent = value.indexOfLast { !it.isWhitespace() }
        for (start in prefix.length..lastContent) {
            matcher.region(start, value.length)
            if (!matcher.lookingAt() && matcher.hitEnd()) return start - prefix.length
        }
        return null
    }

    private fun wordPrefix(): String = previousCharacter?.toString().orEmpty()

    private fun linePrefix(): String = if (atLineStart) "" else "x"

    private fun drain(count: Int): String {
        val text = pending.substring(0, count)
        pending.delete(0, count)
        if (text.isNotEmpty()) {
            previousCharacter = text.last()
            val newline = text.lastIndexOf('\n')
            atLineStart = if (newline >= 0) text.substring(newline + 1).isBlank() else atLineStart && text.isBlank()
        }
        return text
    }

    private fun possiblePartialStart(value: CharSequence, finishing: Boolean = false): Int? {
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
        val assistantStart = if (finishing) {
            patternStart(assistantToolPrefix, linePrefix())
        } else {
            patternStart(assistantToolLine, linePrefix(), partial = true)
        }
        if (assistantStart != null) {
            candidate = minOf(candidate ?: assistantStart, assistantStart)
        }
        return candidate
    }

    private companion object {
        const val MAX_PENDING_CHARACTERS = 512
    }
}
