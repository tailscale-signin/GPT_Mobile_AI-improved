package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Only an unfinished final paragraph can be repaired without reconsidering the whole answer. */
internal data class AnswerTailRepair(val preservedPrefix: String, val unfinishedTail: String) {
    val precedingContext: String get() = preservedPrefix.takeLast(512).let { if (it.firstOrNull()?.isLowSurrogate() == true) it.drop(1) else it }
    fun session(delegate: AgentProviderSession): AgentProviderSession = object : AgentProviderSession by delegate {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            var prefixPublished = false
            delegate.streamRound(tools, exchanges).collect { event ->
                if (event is ProviderEvent.TextDelta && !prefixPublished) {
                    prefixPublished = true
                    emit(ProviderEvent.TextDelta(preservedPrefix + event.text))
                } else {
                    emit(event)
                }
            }
        }
    }

    companion object {
        private val tailDefects = setOf("UNFINISHED_PROSE", "OUTPUT_TRUNCATED")
        private val structuralLine = Regex("^(?:[|>#]|[-+*]\\s|[0-9]+[.)]\\s)")

        fun plan(draft: String, failures: List<String>): AnswerTailRepair? {
            if (draft.length <= 2048 || "UNFINISHED_PROSE" !in failures || failures.any { it !in tailDefects }) return null
            val boundary = draft.lastIndexOf("\n\n")
            if (boundary < 1024 || draft.length - boundary > 4096) return null
            val prefix = draft.substring(0, boundary + 2)
            val tail = draft.substring(boundary + 2)
            // Fenced code, tables and lists can carry syntax across paragraph boundaries.
            // Leave those to the existing complete-answer recovery protocol.
            if (tail.isBlank() || "```" in draft || "~~~" in draft || tail.lineSequence().any { structuralLine.containsMatchIn(it.trimStart()) }) return null
            if (prefix.trimEnd().lastOrNull() !in setOf('.', '!', '?', '”', '"', ')')) return null
            return AnswerTailRepair(prefix, tail)
        }
    }
}
