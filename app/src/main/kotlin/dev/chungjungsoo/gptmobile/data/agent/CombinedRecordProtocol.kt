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
    val failure: String? = null,
    val recoveryAttempts: Int = 0,
    val malformedRecoveryAttempts: Int = 0,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val lastAttemptOutcome: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val appendReady: Boolean = false,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val retryAtMillis: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val noProgressAttempts: Int = 0
) {
    val lastSequence: Int get() = records.lastOrNull()?.sequence ?: 0
    val ended: Boolean get() = records.lastOrNull()?.id == "END"
}

/** Live delivery and saved remote results must interpret the same frozen END contract. */
internal fun CombinedInputSnapshot.recordContinuationAnswerPolicy(): CombinedAnswerPolicy? =
    (answerContract ?: CombinedAnswerPolicy.from(userMessage.content))
        .takeIf { combined?.requested?.appendContinuations == true && (writerRequestPlan?.version ?: 1) >= 2 }

/** Strict, replay-safe logical JSON records. No character overlap or paragraph similarity is authority. */
internal class CombinedRecordProtocol(
    initial: CombinedRecordCheckpoint,
    private val claims: Set<String>,
    private val contributors: Set<String>,
    private val evidence: Set<String>,
    private val maxCharacters: Int,
    private val citationLabels: Map<String, String> = emptyMap(),
    private val continuationAnswerPolicy: CombinedAnswerPolicy? = null
) {
    private val records = mutableListOf<CombinedCommittedRecord>()
    private val pending = StringBuilder()
    private val fragments = initial.recoveredFragments.toMutableList()
    private val byId = mutableMapOf<String, CombinedCommittedRecord>()
    private var failure: String? = null
    private var recoveryAttempts = initial.recoveryAttempts
    private var malformedRecoveryAttempts = initial.malformedRecoveryAttempts
    private var lastAttemptOutcome = initial.lastAttemptOutcome
    private var appendReady = initial.appendReady
    private var retryAtMillis = initial.retryAtMillis
    private var noProgressAttempts = initial.noProgressAttempts
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
        require(recoveryAttempts >= 0 && malformedRecoveryAttempts in 0..recoveryAttempts && fragments.all { it.sequence in 1..(records.size + 1) })
        require(noProgressAttempts >= 0 && lastAttemptOutcome in setOf(null, "IN_FLIGHT", "COMPLETED", "OUTPUT_LIMIT", "KNOWN_PROVIDER_REJECTION", "UNKNOWN")) { "COMBINED_CHECKPOINT_ATTEMPT_INVALID" }
        require(!appendReady || lastAttemptOutcome in setOf("COMPLETED", "OUTPUT_LIMIT", "KNOWN_PROVIDER_REJECTION")) { "COMBINED_CHECKPOINT_ATTEMPT_INVALID" }
        require(retryAtMillis == null || lastAttemptOutcome == "KNOWN_PROVIDER_REJECTION") { "COMBINED_CHECKPOINT_ATTEMPT_INVALID" }
    }

    val failureReason: String? get() = failure
    val canRecoverMalformed: Boolean get() = failure?.let { it.startsWith("INVALID_DELIVERY_RECORD") || it in setOf("INVALID_DECISION_RECORD", "INVALID_CLAIM_DECISION", "MALFORMED_JSON_RECORD") } == true
    val canContinueUnderlength: Boolean get() = failure == PREMATURE_END && continuationMinimumWords() != null

    private fun continuationMinimumWords(): Int? {
        val policy = continuationAnswerPolicy ?: return null
        val targetMinimum = policy.targetWords?.let { target ->
            if (policy.exactWords) target else (target - maxOf(2, target * policy.approximateTolerancePercent / 100)).coerceAtLeast(0)
        }
        return listOfNotNull(policy.minimumWords, targetMinimum).maxOrNull()
    }

    private fun continuationMaximumWords(): Int? {
        val policy = continuationAnswerPolicy ?: return null
        val targetMaximum = policy.targetWords?.let { target ->
            if (policy.exactWords) target else target + maxOf(2, target * policy.approximateTolerancePercent / 100)
        }
        return listOfNotNull(policy.maximumWords, targetMaximum).minOrNull()
    }

    private fun committedVisibleWords(): Int = CombinedWordCounter.count(combinedRecordVisibleText(checkpoint()))

    fun checkpoint(): CombinedRecordCheckpoint = binding.copy(records = records.toList(), pending = pending.toString(), recoveredFragments = fragments.toList(), failure = failure, recoveryAttempts = recoveryAttempts, malformedRecoveryAttempts = malformedRecoveryAttempts, lastAttemptOutcome = lastAttemptOutcome, appendReady = appendReady, retryAtMillis = retryAtMillis, noProgressAttempts = noProgressAttempts)

    /** Persist this before dispatch: a process death cannot invent a known response boundary. */
    fun markAttemptStarted() {
        lastAttemptOutcome = "IN_FLIGHT"
        appendReady = false
        retryAtMillis = null
    }

    fun markAttemptFinished(outcome: String, retryAt: Long? = null) {
        require(outcome in setOf("COMPLETED", "OUTPUT_LIMIT", "KNOWN_PROVIDER_REJECTION", "UNKNOWN"))
        require(retryAt == null || outcome == "KNOWN_PROVIDER_REJECTION")
        lastAttemptOutcome = outcome
        appendReady = outcome != "UNKNOWN"
        retryAtMillis = retryAt
    }

    fun recordProgress(progressed: Boolean) {
        noProgressAttempts = if (progressed) 0 else Math.addExact(noProgressAttempts, 1)
    }

    /** Only a known physical response boundary disambiguates a split opening fence. */
    fun finishPacket() {
        if (failure == null && pending.toString().trim() == "```") {
            pending.setLength(0)
            resetScan()
        }
    }

    /** Preserve remaining bytes until a malformed response reaches a known transport boundary. */
    fun retainRejected(delta: String) {
        check(failure != null)
        require(pending.length.toLong() + delta.length <= 65_536L) { "COMBINED_FRAGMENT_LIMIT" }
        pending.append(delta)
    }

    fun accept(delta: String): List<CombinedCommittedRecord> = dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace.measure(
        "protocol_decode",
        dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner(revisionId = binding.revisionId, digest = binding.snapshotDigest)
    ) { decode(delta) }

    private fun decode(delta: String): List<CombinedCommittedRecord> {
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
                        fence == "```" ||
                            records.lastOrNull()?.id != "END" &&
                            fence in setOf("```json", "```jsonl", "```ndjson")
                    ) { "INVALID_DELIVERY_RECORD" }
                    // A physical continuation can open/close its own wrapper before
                    // the logical END. Wrappers never advance sequence or completion.
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
                        row["final_quote"]?.takeUnless { it == kotlinx.serialization.json.JsonNull }?.let { text("final_quote") }
                    }
                    "end" -> {
                        require(id == "END" && row.keys == setOf("type", "seq", "id", "lastSeq") && number("lastSeq") == sequence - 1) { "INVALID_END_RECORD" }
                        continuationMinimumWords()?.let { minimum ->
                            val count = committedVisibleWords()
                            val maximum = continuationMaximumWords()
                            // A maximum is never an implicit minimum. Conflicting accepted
                            // limits remain a final assessment issue; do not append past a ceiling.
                            require(count >= minimum || maximum?.let { count >= it } == true) { PREMATURE_END }
                        }
                    }
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
            failure = if (error is kotlinx.serialization.SerializationException) "MALFORMED_JSON_RECORD" else error.message ?: "INVALID_DELIVERY_RECORD"
        }
        return added
    }

    /** Regenerate the entire unfinished record. The original fragment remains part of the durable journal. */
    fun continuationContext(repairMalformed: Boolean = false, continueUnderlength: Boolean = false): String {
        val lengthContinuation = continueUnderlength && canContinueUnderlength
        check((failure == null || repairMalformed && canRecoverMalformed || lengthContinuation) && records.lastOrNull()?.id != "END") { failure ?: "DELIVERY_ALREADY_ENDED" }
        check((!repairMalformed || malformedRecoveryAttempts < 8) && fragments.sumOf { it.raw.length.toLong() } + pending.length <= 262_144L) { "COMBINED_RECORD_RECOVERY_LIMIT" }
        val rejected = failure
        val partial = pending.toString()
        recoveryAttempts++
        if (rejected != null && !lengthContinuation) malformedRecoveryAttempts++
        failure = null
        if (partial.isNotBlank()) fragments += CombinedUncommittedFragment(records.size + 1, partial)
        pending.setLength(0)
        resetScan()
        return JsonObject(
            mapOf(
                "protocol" to JsonPrimitive("PASSAGES_V3"),
                "next_sequence" to JsonPrimitive(records.size + 1),
                "rejected_record_reason" to JsonPrimitive(rejected.orEmpty()),
                "committed_ids" to JsonArray(records.map { JsonPrimitive(it.id) }),
                "committed_digest" to JsonPrimitive(combinedRecordDigest(checkpoint())),
                "snapshot_digest" to JsonPrimitive(binding.snapshotDigest),
                "revision_id" to JsonPrimitive(binding.revisionId),
                "committed_visible_words" to JsonPrimitive(committedVisibleWords()),
                "minimum_visible_words" to (continuationMinimumWords()?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                "maximum_visible_words" to (continuationMaximumWords()?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                "unfinished_record_untrusted" to JsonPrimitive(partial),
                "instruction" to JsonPrimitive(
                    if (lengthContinuation) {
                        "The end record was withheld because the saved answer has only ${committedVisibleWords()} visible words. " +
                            "Append substantive missing material from the frozen sources to reach at least ${continuationMinimumWords()} visible words in the complete answer" +
                            (continuationMaximumWords()?.let { "; never exceed $it words" } ?: "") + ". " +
                            "Use next_sequence for the next new passage, not another premature end record. Preserve every committed passage and its source references. " +
                            "Do not repeat or pad text, invent claims, rewrite earlier passages or execute tools. Emit a new end record only after the requested scope and length are satisfied."
                    } else {
                        (
                            if (partial.isBlank()) {
                                "There is no unfinished record. Start the next NEW passage at next_sequence and continue the remaining answer. "
                            } else {
                                "Regenerate the entire unfinished record at next_sequence, preserving its intended topic and claims, then continue with NEW passages. Never continue inside a JSON string. "
                            }
                            ) + "Use this packet's remaining allowance for the unfinished requested scope; completing one record does not complete the answer. Use the original frozen inventory, user scope and record IDs. Emit the end record only after all required records. Do not execute tools."
                    }
                )
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
        private const val PREMATURE_END = "COMBINED_LENGTH_REQUIREMENT_UNMET"
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
