package dev.chungjungsoo.gptmobile.data.voice

/** Hide recognized standalone waiting lines only in automatic playback of ordinary questions. */
internal object VoiceSpokenLead {
    private val question = Regex("""^(?:what|when|where|which|who|why|how|is|are|can|could|do|does|did|will|would)\b""", RegexOption.IGNORE_CASE)
    private val wording = Regex("""\b(?:say|quote|repeat|translate|write|spell|read|pronounce|phrase|mean|verbatim|exactly|wording)\b""", RegexOption.IGNORE_CASE)
    private val acknowledgements = setOf("sure", "of course", "absolutely")
    private val preambles = acknowledgements + listOf(
        "let me get that info for you",
        "let me get that information for you",
        "let me look that up for you",
        "let me check that for you",
        "great question",
        "here’s the answer",
        "here's the answer"
    )

    fun forQuestion(prompt: String): Boolean = question.containsMatchIn(prompt.trimStart()) &&
        !wording.containsMatchIn(prompt) &&
        preambles.none { prompt.contains(it, ignoreCase = true) }

    fun trim(text: String, final: Boolean): String {
        var rest = text
        while (rest.isNotBlank()) {
            val lead = rest.trimStart()
            val pending = lead.trimEnd()
            if (!final && preambles.any { it.startsWith(pending, ignoreCase = true) }) return ""
            if (final && preambles.any { it !in acknowledgements && it.equals(pending, ignoreCase = true) }) return ""
            val preamble = preambles.firstOrNull { lead.startsWith(it, ignoreCase = true) && lead.getOrNull(it.length) in punctuation } ?: return rest
            val remaining = lead.drop(preamble.length).dropWhile { it in punctuation || it.isWhitespace() }
            if (final && remaining.isEmpty() && preamble in acknowledgements) return lead
            rest = remaining
        }
        return rest
    }

    private val punctuation = setOf('.', '!', ',', ':', '…', '—')
}
