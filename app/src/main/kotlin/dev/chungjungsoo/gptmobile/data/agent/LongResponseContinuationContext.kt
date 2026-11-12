package dev.chungjungsoo.gptmobile.data.agent

/**
 * Keeps a rolling view within the request allowance without pretending that the model has
 * a larger native context. Only generated answer text is shortened; callers retain the
 * original request, instructions and evidence and admit the entire request separately.
 */
internal object LongResponseContinuationContext {
    fun build(draft: String, maxTokens: Int, requestedRepetitionCharacters: Int = 0): String {
        require(maxTokens >= 0) { "Continuation context allowance cannot be negative." }
        if (draft.isEmpty() || maxTokens == 0) return ""
        // Old checkpoints can predate the streaming guard. Never teach the repair
        // request to imitate their repetitive tail; the saved original stays intact.
        val safeDraft = RepetitiveProseGuard.safeContinuationPrefix(draft, requestedRepetitionCharacters)
        val maxBytes = (maxTokens.toLong() * 3).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val repair = if (safeDraft != draft) "A repetitive generated tail was excluded. Continue with new substantive material, not the repeated list or phrase.\n" else ""
        if (utf8Size(repair + safeDraft) <= maxBytes) return repair + safeDraft
        val header = repair + "Earlier text is saved outside this request (${LongResponsePolicy.countWords(safeDraft)} words). Do not restart.\n"
        val tailLabel = "Exact stopping point:\n"
        val fixedBytes = utf8Size(header + tailLabel)
        if (fixedBytes >= maxBytes) return suffixWithinBytes(safeDraft, maxBytes)
        val remaining = maxBytes - fixedBytes
        val headingsBudget = remaining / 4
        val headings = StringBuilder()
        var headingBytes = 0
        safeDraft.lineSequence().filter { it.trimStart().startsWith('#') }.forEach { heading ->
            val next = heading.trim() + "\n"
            val bytes = utf8Size(next)
            if (headingBytes + bytes <= headingsBudget) {
                headings.append(next)
                headingBytes += bytes
            }
        }
        return header + headings + tailLabel + suffixWithinBytes(safeDraft, remaining - headingBytes)
    }

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** Walk code points so a compact request never introduces a broken surrogate or UTF-8 character. */
    private fun suffixWithinBytes(text: String, maximum: Int): String {
        var index = text.length
        var bytes = 0
        while (index > 0) {
            val codePoint = Character.codePointBefore(text, index)
            val size = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + size > maximum) break
            bytes += size
            index -= Character.charCount(codePoint)
        }
        return text.substring(index)
    }
}
