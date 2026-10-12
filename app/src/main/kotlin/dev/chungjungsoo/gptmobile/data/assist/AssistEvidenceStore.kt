package dev.chungjungsoo.gptmobile.data.assist

import java.security.MessageDigest
import java.util.UUID

/** Response-owned original evidence. Handles are never paths, URLs or cross-chat grants. */
internal class AssistEvidenceStore(
    private val scope: String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lifetimeMs: Long = 120_000,
    private val maxCharacters: Int = 256_000
) {
    data class Source(val handle: String, val revision: String, val text: String, val expiresAt: Long)
    private val sources = linkedMapOf<String, Source>()

    @Synchronized
    fun retain(text: String): Source? {
        sources.entries.removeAll { it.value.expiresAt <= now() }
        if (text.length > maxCharacters) return null
        while (sources.values.sumOf { it.text.length } + text.length > maxCharacters) sources.remove(sources.keys.first())
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val source = Source(UUID.randomUUID().toString(), hash, text, now() + lifetimeMs)
        sources[source.handle] = source
        return source
    }

    @Synchronized
    fun read(requestScope: String, handle: String, revision: String, start: Int, end: Int): String {
        require(requestScope == scope) { "Evidence scope mismatch" }
        val source = requireNotNull(sources[handle]) { "Evidence is unavailable" }
        require(source.revision == revision && source.expiresAt > now()) { "Evidence has changed or expired" }
        require(start >= 0 && end > start && end <= source.text.length && end - start <= 4_000) { "Invalid evidence character range" }
        require(start == 0 || !source.text[start].isLowSurrogate()) { "Range splits a character" }
        require(end == source.text.length || !source.text[end].isLowSurrogate()) { "Range splits a character" }
        return source.text.substring(start, end)
    }

    @Synchronized
    fun clear() = sources.clear()
}
