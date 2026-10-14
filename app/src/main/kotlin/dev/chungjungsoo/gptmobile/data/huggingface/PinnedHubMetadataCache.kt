package dev.chungjungsoo.gptmobile.data.huggingface

import dev.chungjungsoo.gptmobile.data.research.researchHash
import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Small process cache of public metadata at immutable revisions; credentials never become keys or logs. */
internal class PinnedHubMetadataCache(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maximumBytes: Int = 4 * 1024 * 1024,
    private val maximumEntries: Int = 32
) {
    private data class Entry(val value: JsonObject, val bytes: Int, val at: Long)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0
    private fun key(repo: String, revision: String, token: String?) = "$repo|$revision|${researchHash(token.orEmpty())}"

    @Synchronized fun get(repo: String, revision: String, token: String?): JsonObject? {
        val key = key(repo, revision, token)
        val entry = entries[key] ?: return null
        if (nowMs() - entry.at >= 5 * 60_000L) {
            entries.remove(key)
            bytes -= entry.bytes
            return null
        }
        return entry.value
    }

    @Synchronized fun put(repo: String, revision: String, token: String?, value: JsonObject) {
        if (!revision.matches(Regex("[a-fA-F0-9]{40}")) || value["private"]?.jsonPrimitive?.booleanOrNull != false) return
        val size = value.toString().toByteArray().size
        if (size > minOf(256 * 1024, maximumBytes) || maximumEntries <= 0) return
        val key = key(repo, revision, token)
        entries.remove(key)?.let { bytes -= it.bytes }
        while (entries.isNotEmpty() && (bytes + size > maximumBytes || entries.size >= maximumEntries)) {
            val eldest = entries.entries.iterator()
            bytes -= eldest.next().value.bytes
            eldest.remove()
        }
        entries[key] = Entry(value, size, nowMs())
        bytes += size
    }
}
