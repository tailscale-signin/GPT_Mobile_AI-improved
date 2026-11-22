package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Optional immutable read cache. The original encrypted batches and their checkpoints remain authoritative. */
internal class RunJournalCompaction(private val vault: SecretVault, private val now: () -> Long) {
    data class Segment(val reference: String, val sha256: String)
    data class Index(val sources: List<String>, val segments: List<Segment>)

    suspend fun index(runId: String, references: Set<String>, sources: List<String>): Index? {
        val allowed = sources.toHashSet()
        val prefix = "journalpackindex-${identity(runId)}-"
        for (reference in references.asSequence().filter { it.startsWith(prefix) }.sortedByDescending(::timestamp).take(4)) {
            try {
                val bytes = vault.read(reference) ?: continue
                val value = try {
                    require(bytes.size <= MAX_BYTES)
                    Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: continue
                } finally {
                    bytes.fill(0)
                }
                if (value["schema"] != JsonPrimitive("run-journal-pack.v1") || value["runId"] != JsonPrimitive(runId)) continue
                val original = (value["sources"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: error("Invalid source") } ?: continue
                if (original.size !in MIN_SOURCES..MAX_SOURCES || original.distinct().size != original.size || original.any { it !in allowed }) continue
                val segments = (value["segments"] as? JsonArray)?.map { raw ->
                    val row = raw as? JsonObject ?: error("Invalid segment")
                    val name = (row["reference"] as? JsonPrimitive)?.content ?: error("Missing reference")
                    val digest = (row["sha256"] as? JsonPrimitive)?.content ?: error("Missing digest")
                    require(name.startsWith("journalpacked-${identity(runId)}-") && name in references && digest.matches(Regex("[a-f0-9]{64}")))
                    Segment(name, digest)
                } ?: continue
                if (segments.isEmpty() || segments.size >= original.size || segments.distinctBy { it.reference }.size != segments.size) continue
                return Index(original, segments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A missing/incomplete cache never makes the original capture incomplete.
            }
        }
        return null
    }

    /** The writer supplies an idle/authority fence and a short, guarded atomic vault write. */
    suspend fun compact(
        runId: String,
        references: Set<String>,
        current: () -> Boolean,
        publish: suspend (String, ByteArray) -> Boolean
    ): Boolean {
        val sources = references.filter { it.startsWith("journal-${identity(runId)}-") }.sortedBy(::timestamp).take(MAX_SOURCES)
        if (sources.size < MIN_SOURCES) return true
        if (!current()) return false
        if (index(runId, references, sources)?.sources == sources) return true
        val created = mutableListOf<String>()
        val segments = mutableListOf<Segment>()
        var committed = false
        val encoded = mutableListOf<String>()
        var encodedBytes = 2
        suspend fun flush(): Boolean {
            if (encoded.isEmpty()) return true
            if (!current()) return false
            val bytes = encoded.joinToString(",", "[", "]").encodeToByteArray()
            val reference = "journalpacked-${identity(runId)}-${now()}-${UUID.randomUUID()}"
            created += reference
            return try {
                require(bytes.size <= MAX_BYTES)
                val digest = DiagnosticRecordChunks.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                if (!publish(reference, bytes)) return false
                segments += Segment(reference, digest)
                encoded.clear()
                encodedBytes = 2
                true
            } finally {
                bytes.fill(0)
            }
        }
        try {
            for (source in sources) {
                yield()
                if (!current()) return false
                val bytes = vault.read(source) ?: return false
                val events = try {
                    require(bytes.size <= 64 * 1024)
                    Json.parseToJsonElement(bytes.decodeToString()) as? JsonArray ?: return false
                } finally {
                    bytes.fill(0)
                }
                for (event in events) {
                    val row = event as? JsonObject ?: return false
                    require(row["runId"] == JsonPrimitive(runId) && ((row["sequence"] as? JsonPrimitive)?.longOrNull ?: 0) > 0)
                    val value = event.toString()
                    val size = value.encodeToByteArray().size
                    require(size + 2 <= MAX_BYTES)
                    if (encodedBytes + size + 1 > MAX_BYTES && !flush()) return false
                    encoded += value
                    encodedBytes += size + 1
                }
            }
            if (!flush() || segments.size >= sources.size || !current()) return false
            val manifest = buildJsonObject {
                put("schema", "run-journal-pack.v1")
                put("runId", runId)
                put("sources", JsonArray(sources.map(::JsonPrimitive)))
                put(
                    "segments",
                    JsonArray(
                        segments.map { segment ->
                            buildJsonObject {
                                put("reference", segment.reference)
                                put("sha256", segment.sha256)
                            }
                        }
                    )
                )
            }.toString().encodeToByteArray()
            try {
                require(manifest.size <= MAX_BYTES)
                // Publishing this final immutable index is the only visibility point.
                committed = publish("journalpackindex-${identity(runId)}-${now()}-${UUID.randomUUID()}", manifest)
                return committed
            } finally {
                manifest.fill(0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        } finally {
            if (!committed) {
                // Unindexed unique segments cannot be held by a frozen reader. A crash
                // before cleanup leaves only bounded cache orphans for normal retention.
                for (reference in created) runCatching { vault.delete(reference) }
            }
        }
    }

    suspend fun read(segment: Segment): ByteArray? {
        val bytes = vault.read(segment.reference) ?: return null
        if (bytes.size <= MAX_BYTES && DiagnosticRecordChunks.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)) == segment.sha256) return bytes
        bytes.fill(0)
        return null
    }

    companion object {
        private const val MAX_BYTES = 60 * 1024
        private const val MAX_SOURCES = 256
        private const val MIN_SOURCES = 4
        fun identity(runId: String) = DiagnosticRecordChunks.sha256(runId).take(24)
        fun isCache(reference: String) = reference.startsWith("journalpacked-") || reference.startsWith("journalpackindex-")
        fun isRunCache(reference: String, runId: String) = reference.startsWith("journalpacked-${identity(runId)}-") || reference.startsWith("journalpackindex-${identity(runId)}-")
        fun timestamp(reference: String) = reference.split('-').getOrNull(2)?.toLongOrNull() ?: 0L
    }
}
