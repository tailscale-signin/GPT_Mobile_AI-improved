package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
data class CombinedCommittedRecord(val sequence: Int, val id: String, val wire: String, val digest: String)

@Serializable
data class CombinedUncommittedFragment(val sequence: Int, val raw: String)

/** Mutable delivery state is separate from the immutable synthesis snapshot. Digests use UTF-8. */
@Serializable
data class CombinedRecordCheckpoint(
    val version: Int = 1,
    val protocol: String = "PASSAGES_V3",
    val snapshotDigest: String,
    val revisionId: String,
    val offsetMapping: String = "UTF16_V1",
    val records: List<CombinedCommittedRecord> = emptyList(),
    val pending: String = "",
    val recoveredFragments: List<CombinedUncommittedFragment> = emptyList(),
    val failure: String? = null
) {
    val lastSequence: Int get() = records.lastOrNull()?.sequence ?: 0
    val ended: Boolean get() = records.lastOrNull()?.id == "END"
}

/** Strict, replay-safe logical JSON records. No character overlap or paragraph similarity is authority. */
internal class CombinedRecordProtocol(
    initial: CombinedRecordCheckpoint,
    private val claims: Set<String>,
    private val contributors: Set<String>,
    private val evidence: Set<String>,
    private val maxCharacters: Int,
    private val citationLabels: Map<String, String> = emptyMap()
) {
    private val records = mutableListOf<CombinedCommittedRecord>()
    private val pending = StringBuilder()
    private val fragments = initial.recoveredFragments.toMutableList()
    private val byId = mutableMapOf<String, CombinedCommittedRecord>()
    private var failure: String? = null
    private var recordCharacters = 0L
    private var visibleCharacters = 0L
    private var scanned = 0
    private var scanDepth = 0
    private var scanQuoted = false
    private var scanEscaped = false
    private val binding = initial.copy(records = emptyList(), pending = "", recoveredFragments = emptyList(), failure = null)

    init {
        require(initial.version == 1 && initial.protocol == "PASSAGES_V3" && initial.offsetMapping == "UTF16_V1")
        require(initial.snapshotDigest.isNotBlank() && initial.revisionId.isNotBlank() && maxCharacters > 0)
        initial.records.forEach { stored ->
            val restored = accept(stored.wire + "\n").singleOrNull()
            require(restored == stored) { "COMBINED_CHECKPOINT_INTEGRITY" }
        }
        pending.append(initial.pending)
        failure = initial.failure
        require(fragments.all { it.sequence in 1..(records.size + 1) })
    }

    val failureReason: String? get() = failure

    fun checkpoint(): CombinedRecordCheckpoint = binding.copy(records = records.toList(), pending = pending.toString(), recoveredFragments = fragments.toList(), failure = failure)

    fun accept(delta: String): List<CombinedCommittedRecord> {
        check(failure == null) { requireNotNull(failure) }
        pending.append(delta)
        val added = mutableListOf<CombinedCommittedRecord>()
        try {
            while (true) {
                val first = pending.indexOfFirst { !it.isWhitespace() }
                if (first < 0) {
                    pending.setLength(0)
                    break
                }
                if (first > 0) pending.delete(0, first)
                // Providers sometimes wrap otherwise valid JSONL in a Markdown fence.
                // Accept only the wrapper; record validation and commit authority stay strict.
                if (pending[0] == '`') {
                    val newline = pending.indexOf("\n")
                    if (newline < 0) {
                        if (records.lastOrNull()?.id == "END" && pending.toString().trim() == "```") {
                            pending.setLength(0)
                        }
                        break
                    }
                    val fence = pending.substring(0, newline).trim()
                    require(
                        records.isEmpty() &&
                            fence in setOf("```", "```json", "```jsonl", "```ndjson") ||
                            records.lastOrNull()?.id == "END" &&
                            fence == "```"
                    ) { "INVALID_DELIVERY_RECORD" }
                    pending.delete(0, newline + 1)
                    continue
                }
                require(pending[0] == '{') { "INVALID_DELIVERY_RECORD" }
                val end = objectEnd(0) ?: break
                val raw = pending.substring(0, end)
                resetScan()
                val row = Json.parseToJsonElement(raw) as? JsonObject ?: error("INVALID_DELIVERY_RECORD")
                val wire = canonical(row).toString()
                fun text(key: String): String = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("INVALID_DELIVERY_RECORD:$key")
                fun number(key: String): Int = (row[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("INVALID_DELIVERY_RECORD:$key")
                fun ids(key: String, allowed: Set<String>): Set<String> {
                    val values = (row[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("INVALID_REFERENCE:$key") } ?: error("INVALID_REFERENCE:$key")
                    require(values.distinct().size == values.size && values.all { it in allowed }) { "UNKNOWN_REFERENCE:$key" }
                    return values.toSet()
                }
                val sequence = number("seq")
                val id = text("id")
                require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "INVALID_RECORD_ID" }
                val record = CombinedCommittedRecord(sequence, id, wire, researchHash(wire))
                val replay = byId[id] ?: records.getOrNull(sequence - 1)
                if (replay != null) {
                    require(replay == record) { "CONFLICTING_RECORD_REPLAY" }
                    pending.delete(0, end)
                    continue
                }
                require(records.lastOrNull()?.id != "END") { "RECORD_AFTER_END" }
                require(sequence == records.size + 1) { "DELIVERY_SEQUENCE_GAP" }
                var nextVisibleCharacters = visibleCharacters
                when (text("type")) {
                    "passage" -> {
                        require(id != "END" && row.keys == setOf("type", "seq", "id", "text", "claims", "contributors", "evidence")) { "INVALID_PASSAGE_RECORD" }
                        val body = text("text")
                        require(body.isNotBlank() && !body.contains(COMBINED_RECOVERY_MARKER)) { "INVALID_PASSAGE_BODY" }
                        require(validUtf16(body)) { "INVALID_UNICODE_RECORD" }
                        ids("claims", claims)
                        ids("contributors", contributors)
                        val cited = ids("evidence", evidence)
                        val citations = cited.sorted().mapNotNull { citationLabels[it] }.joinToString(" ")
                        nextVisibleCharacters += body.length + (if (visibleCharacters == 0L) 0 else 2) + (if (citations.isEmpty()) 0 else citations.length + 2)
                        require(nextVisibleCharacters <= maxCharacters) { "COMBINED_DELIVERY_LIMIT" }
                    }
                    "decision" -> {
                        require(id != "END" && row.keys.all { it in setOf("type", "seq", "id", "claim", "disposition", "reason", "final_quote") }) { "INVALID_DECISION_RECORD" }
                        require(text("claim") in claims && text("disposition") in DISPOSITIONS && text("reason").isNotBlank()) { "INVALID_CLAIM_DECISION" }
                        row["final_quote"]?.let { text("final_quote") }
                    }
                    "end" -> require(id == "END" && row.keys == setOf("type", "seq", "id", "lastSeq") && number("lastSeq") == sequence - 1) { "INVALID_END_RECORD" }
                    else -> error("INVALID_DELIVERY_RECORD")
                }
                require(recordCharacters + wire.length <= maxCharacters.toLong() * 4 + 65_536) { "COMBINED_RECORD_LIMIT" }
                // A rejected or interrupted record stays in pending. Only a fully validated commit consumes bytes.
                records += record
                byId[id] = record
                recordCharacters += wire.length
                visibleCharacters = nextVisibleCharacters
                pending.delete(0, end)
                added += record
            }
            require(pending.length <= 65_536) { "COMBINED_FRAGMENT_LIMIT" }
        } catch (error: Exception) {
            failure = error.message ?: "INVALID_DELIVERY_RECORD"
        }
        return added
    }

    /** Regenerate the entire unfinished record. The original fragment remains part of the durable journal. */
    fun continuationContext(): String {
        check(failure == null && records.lastOrNull()?.id != "END") { failure ?: "DELIVERY_ALREADY_ENDED" }
        val partial = pending.toString()
        if (partial.isNotBlank()) fragments += CombinedUncommittedFragment(records.size + 1, partial)
        pending.setLength(0)
        resetScan()
        return JsonObject(
            mapOf(
                "protocol" to JsonPrimitive("PASSAGES_V3"),
                "next_sequence" to JsonPrimitive(records.size + 1),
                "committed_ids" to JsonArray(records.map { JsonPrimitive(it.id) }),
                "unfinished_record_untrusted" to JsonPrimitive(partial),
                "instruction" to JsonPrimitive("Regenerate the entire unfinished record at next_sequence, preserving its intended topic and claims, then finish the remaining records. Never continue inside a JSON string. Use the original frozen inventory, user scope and record IDs. Emit the end record after all required records. Do not execute tools.")
            )
        ).toString()
    }

    private fun objectEnd(start: Int): Int? {
        // Scan only newly appended characters. Rescanning the growing fragment on
        // every streamed token made long structured passages quadratic work.
        if (scanned < start) scanned = start
        while (scanned < pending.length) {
            val char = pending[scanned++]
            if (scanQuoted) {
                when {
                    scanEscaped -> scanEscaped = false
                    char == '\\' -> scanEscaped = true
                    char == '"' -> scanQuoted = false
                }
            } else {
                when (char) {
                    '"' -> scanQuoted = true
                    '{', '[' -> scanDepth++
                    '}', ']' -> {
                        scanDepth--
                        if (scanDepth == 0) return scanned
                    }
                }
            }
        }
        return null
    }

    private fun resetScan() {
        scanned = 0
        scanDepth = 0
        scanQuoted = false
        scanEscaped = false
    }

    companion object {
        private val DISPOSITIONS = setOf("INCLUDED", "MERGED", "QUALIFIED", "DISPUTED", "CORRECTED", "OMITTED_SCOPE", "OMITTED_DUPLICATE", "OMITTED_UNSUPPORTED", "UNRESOLVED")
        private fun canonical(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(value.map(::canonical))
            else -> value
        }
        private fun validUtf16(text: String): Boolean {
            var index = 0
            while (index < text.length) {
                val char = text[index++]
                if (Character.isHighSurrogate(char)) {
                    if (index >= text.length || !Character.isLowSurrogate(text[index++])) return false
                } else if (Character.isLowSurrogate(char)) {
                    return false
                }
            }
            return true
        }
    }
}
