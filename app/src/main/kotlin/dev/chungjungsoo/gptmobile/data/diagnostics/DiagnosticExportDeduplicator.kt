package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Compact exact recaptures only. Different text or stack sequence always keeps its full evidence. */
internal class DiagnosticExportDeduplicator(private val limit: Int = 8192) {
    private data class Original(val digest: String, val source: String, val index: Int)
    data class Decision(val duplicate: Boolean, val batch: String? = null)
    private val originals = linkedMapOf<String, Original>()
    private val references = mutableListOf<JsonObject>()

    init {
        require(limit > 0)
    }

    fun accept(line: String, identity: String, source: String, index: Int): Decision {
        val digest = DiagnosticRecordChunks.sha256(line)
        val previous = originals[identity]
        if (previous?.digest != digest) {
            if (previous == null) {
                originals[identity] = Original(digest, source, index)
                while (originals.size > limit) originals.remove(originals.keys.first())
            }
            return Decision(false)
        }
        references += buildJsonObject {
            put("eventId", identity)
            put("captureSource", source)
            put("sourceLine", index + 1)
            put("canonicalSource", previous.source)
            put("canonicalLine", previous.index + 1)
            put("payloadSha256", digest)
        }
        return Decision(true, if (references.size == 32) flush() else null)
    }

    fun flush(): String? {
        if (references.isEmpty()) return null
        val batch = buildJsonObject {
            put("schema", 7)
            put("kind", "duplicate_reference_batch")
            put("duplicate", true)
            put("countAsIssue", false)
            put("referenceCount", references.size)
            put("references", JsonArray(references.toList()))
        }.toString()
        references.clear()
        return batch
    }
}
