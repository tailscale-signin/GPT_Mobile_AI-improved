package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote

/** Preserve original context verbatim; only identical prose blocks can be discarded. */
internal fun mergeCombinedResponses(sources: List<CombinedModelResponse>): String {
    val seen = mutableSetOf<String>()
    val merged = mutableListOf<String>()
    val seenSentences = mutableSetOf<String>()
    sources.forEach { source ->
        val text = dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.withoutMetadata(stripAssistantErrorNote(source.content)).trim()
        val blocks = mutableListOf<String>()
        val block = StringBuilder()
        var fence: String? = null
        fun flush() {
            if (block.isNotBlank()) blocks += block.toString().trimEnd()
            block.clear()
        }
        text.lineSequence().forEach { line ->
            val marker = line.trimStart().take(3).takeIf { it == "```" || it == "~~~" }
            if (marker != null) fence = if (fence == marker) null else fence ?: marker
            if (line.isBlank() && fence == null) flush() else block.appendLine(line)
        }
        flush()
        blocks.forEach { candidate ->
            // Code, lists and tables retain whitespace, structure and duplicate rows.
            // A repeated heading can belong to a different unique section.
            val structural = candidate.lineSequence().any {
                it.trimStart().let { line -> line.startsWith("#") || line.startsWith("```") || line.startsWith("~~~") || line.startsWith("|") || Regex("^(?:[-*+] |\\d+[.)] )").containsMatchIn(line) }
            }
            val identity = if (structural) candidate else candidate.replace(Regex("\\s+"), " ").trim()
            val headingOnly = candidate.lineSequence().all { it.isBlank() || it.trimStart().startsWith("#") }
            if (headingOnly) {
                merged += candidate
            } else if (seen.add(identity)) {
                if (structural) {
                    merged += candidate
                } else {
                    // Exact repeated sentences can share a paragraph with new details.
                    val unique = candidate.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9])"))
                        .filter { seenSentences.add(it.replace(Regex("\\s+"), " ").trim()) }
                    if (unique.isNotEmpty()) merged += unique.joinToString(" ")
                }
            }
        }
    }
    return merged.joinToString("\n\n")
}
