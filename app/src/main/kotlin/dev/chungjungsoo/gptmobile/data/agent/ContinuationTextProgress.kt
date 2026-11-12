package dev.chungjungsoo.gptmobile.data.agent

import java.util.Locale

/**
 * Releases a continuation once its possible literal overlap is resolved. Only the
 * ambiguous prefix is buffered; a provider cannot spend an unbounded request replaying it.
 */
internal class ContinuationTextProgress(previous: String = "", requestedRepetitionCharacters: Int = 0) {
    private val overlap = ContinuationOverlap(previous)
    private val prose = RepetitiveProseGuard(previous, requestedRepetitionCharacters)
    val repetitive: Boolean get() = prose.repetitive
    val repeatedPrefix: Boolean get() = overlap.repeatedPrefix
    val stopped: Boolean get() = repetitive || repeatedPrefix

    fun append(text: String): String = if (stopped) "" else prose.append(overlap.append(text))

    fun finish(): String = prose.append(overlap.finish()) + prose.finish()
}

private class ContinuationOverlap(private val previous: String) {
    private val pending = StringBuilder()
    private var resolved = previous.isEmpty()
    private var nextProbe = MINIMUM_OVERLAP
    var repeatedPrefix = false
        private set

    fun append(text: String): String {
        if (repeatedPrefix) return ""
        if (resolved) return text
        var offset = 0
        while (offset < text.length && !resolved && !repeatedPrefix) {
            val end = minOf(text.length, offset + (nextProbe - pending.length).coerceAtLeast(1))
            pending.append(text, offset, end)
            offset = end
            if (pending.length < nextProbe) continue
            if (!previous.contains(pending)) {
                resolved = true
            } else if (pending.length >= MAXIMUM_AMBIGUOUS_PREFIX) {
                repeatedPrefix = true
                pending.clear()
            } else {
                nextProbe = minOf(MAXIMUM_AMBIGUOUS_PREFIX, pending.length + 64)
            }
        }
        // A provider may pause after any delta. The coarse probes bound work within
        // a large chunk; they must not withhold already distinguishable new text
        // merely because this chunk ended a few characters before the next probe.
        if (!resolved && !repeatedPrefix && pending.length >= MINIMUM_OVERLAP && !previous.contains(pending)) resolved = true
        if (!resolved) return ""
        val result = continuationSuffix(previous, pending.toString()) + text.substring(offset)
        pending.clear()
        return result
    }

    fun finish(): String {
        if (resolved || repeatedPrefix) return ""
        resolved = true
        return continuationSuffix(previous, pending.toString()).also { pending.clear() }
    }

    companion object {
        private const val MINIMUM_OVERLAP = 20
        private const val MAXIMUM_AMBIGUOUS_PREFIX = 16_384
    }
}

/**
 * A conservative literal prose-loop guard, not a semantic similarity filter. A possible
 * second occurrence is held until it diverges or proves a sustained loop. The first
 * occurrence is already useful text; duplicate tokens never have to be retracted from UI.
 * Markdown code, tables and quoted passages are intentionally outside this heuristic.
 */
internal class RepetitiveProseGuard(previous: String = "", private val requestedRepetitionCharacters: Int = 0) {
    private data class Token(val raw: String, val key: String)

    private val history = ArrayList<Token>()
    private val pending = ArrayList<Token>()
    private val token = StringBuilder()
    private var tokenEndsWithSpace = false
    private var pendingCharacters = 0
    private var lineStart = true
    private var indentation = 0
    private var lineProtected = false
    private var fence: String? = null
    private var inlineCodeTicks = 0
    private var quoted = false
    private var inspectedCharacters = 0
    var repetitive = false
        private set

    init {
        // Carry prose history and an open code fence across packets without publishing
        // the prefix again. Old repetitive checkpoints seed only their useful prefix.
        for (part in previous.chunked(1024)) {
            append(part)
            if (repetitive) break
        }
        finish()
        repetitive = false
    }

    fun append(text: String): String {
        if (repetitive || text.isEmpty()) return ""
        val output = StringBuilder()
        for (character in text) {
            if (!character.isWhitespace() && tokenEndsWithSpace) {
                acceptToken(output)
                if (repetitive) break
            }
            token.append(character)
            tokenEndsWithSpace = character.isWhitespace()
            // An opaque URL, identifier or non-space-delimited passage is not evidence
            // of a prose loop. Keep token buffering bounded without guessing its meaning.
            if (token.length >= 1024) {
                flushPending(output)
                output.append(token)
                advanceFormatting(token.toString())
                inspectedCharacters += token.length
                token.clear()
                tokenEndsWithSpace = false
                history.clear()
            }
        }
        return output.toString()
    }

    fun finish(): String {
        if (repetitive) return ""
        val output = StringBuilder()
        acceptToken(output)
        if (!repetitive) flushPending(output)
        return output.toString()
    }

    private fun acceptToken(output: StringBuilder) {
        if (token.isEmpty()) return
        val raw = token.toString()
        token.clear()
        tokenEndsWithSpace = false
        val protected = advanceFormatting(raw)
        val key = raw.trim().trim { !it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        inspectedCharacters += raw.length
        if (inspectedCharacters <= requestedRepetitionCharacters && key.isNotEmpty()) {
            flushPending(output)
            output.append(raw)
            history += Token(raw, key)
            if (history.size > MAXIMUM_PERIOD_WORDS) history.removeAt(0)
            return
        }
        if (protected || key.isEmpty()) {
            flushPending(output)
            output.append(raw)
            history.clear()
            return
        }
        pending += Token(raw, key)
        pendingCharacters += raw.length
        while (pending.isNotEmpty()) {
            val periods = (1..minOf(MAXIMUM_PERIOD_WORDS, history.size)).filter { period ->
                pending.indices.all { index -> pending[index].key == history[history.size - period + index % period].key }
            }
            if (periods.isNotEmpty()) {
                if (pendingCharacters >= MINIMUM_REPEATED_CHARACTERS &&
                    periods.any { period ->
                        pending.size >= period * 3 && pending.size + period >= MINIMUM_REPEATED_WORDS
                    }
                ) {
                    repetitive = true
                    pending.clear()
                    pendingCharacters = 0
                }
                return
            }
            val accepted = pending.removeAt(0)
            pendingCharacters -= accepted.raw.length
            output.append(accepted.raw)
            history += accepted
            if (history.size > MAXIMUM_PERIOD_WORDS) history.removeAt(0)
        }
    }

    private fun flushPending(output: StringBuilder) {
        pending.forEach {
            output.append(it.raw)
            history += it
            if (history.size > MAXIMUM_PERIOD_WORDS) history.removeAt(0)
        }
        pending.clear()
        pendingCharacters = 0
    }

    private fun advanceFormatting(raw: String): Boolean {
        val content = raw.trim()
        val marker = if (lineStart) listOf("```", "~~~").firstOrNull(content::startsWith) else null
        val existingFence = fence
        var protected = fence != null || lineProtected || inlineCodeTicks > 0 || quoted || indentation >= 4
        if (marker != null) {
            protected = true
            fence = if (fence == marker) null else fence ?: marker
        }
        if (lineStart && (content.startsWith('>') || content.startsWith('|'))) lineProtected = true
        if ('|' in content) lineProtected = true
        protected = protected || lineProtected
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            if (character == '`' && marker == null && existingFence == null) {
                var end = index + 1
                while (end < raw.length && raw[end] == '`') end++
                val ticks = end - index
                inlineCodeTicks = when (inlineCodeTicks) {
                    0 -> ticks
                    ticks -> 0
                    else -> inlineCodeTicks
                }
                protected = true
                index = end - 1
            }
            if (character in "\"“”" && fence == null && inlineCodeTicks == 0) {
                quoted = when (character) {
                    '“' -> true
                    '”' -> false
                    else -> !quoted
                }
                protected = true
            }
            if (character == '\n') {
                lineStart = true
                indentation = 0
                lineProtected = false
                quoted = false
            } else if (lineStart && character.isWhitespace()) {
                indentation += if (character == '\t') 4 else 1
            } else if (!character.isWhitespace()) {
                lineStart = false
            }
            index++
        }
        return protected
    }

    companion object {
        private const val MAXIMUM_PERIOD_WORDS = 32
        private const val MINIMUM_REPEATED_WORDS = 24
        private const val MINIMUM_REPEATED_CHARACTERS = 160

        /** Only the replay is shortened; the original saved answer is never overwritten. */
        fun safeContinuationPrefix(draft: String, requestedRepetitionCharacters: Int = 0): String {
            val guard = RepetitiveProseGuard(requestedRepetitionCharacters = requestedRepetitionCharacters)
            return guard.append(draft) + guard.finish()
        }
    }
}
