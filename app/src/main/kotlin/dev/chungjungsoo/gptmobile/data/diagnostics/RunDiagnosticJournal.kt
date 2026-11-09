package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class RunDiagnosticKind { INTENT_CONVERSATIONAL, INTENT_SUBSTANTIVE, TOOL_ADMITTED, TOOL_RESULT, CONTINUATION_SELECTED, VERIFICATION_REJECTED, ANSWER_REWRITE, CACHE_OBSERVATION, INPUT_PERSISTED, DISPATCH_ELIGIBLE, PREPARATION_DIRECT, PREPARATION_CONTEXTUAL, PREPARATION_REQUIRED, HTTP_CALL_START, REQUEST_BODY_END, RESPONSE_HEADERS, FIRST_TEXT, FIRST_REASONING, FIRST_TOOL, FIRST_VISIBLE_TEXT, FIRST_PROGRESS, FIRST_VISIBLE_ANSWER, FIRST_SYSTEM_ERROR, FIRST_APPLICATION_REPORT, FIRST_RETAINED_CONTENT, FIRST_UNATTRIBUTED_OUTPUT, PROVIDER_COOLDOWN, PREPARATION_PHASE, REQUEST_SERIALIZED, INVOCATION_STARTED, INVOCATION_FINISHED, ASSIST_DECISION, REDUCER_CHECKPOINT, SEGMENT_COMMITTED, OUTPUT_CHECKPOINT, PROGRESS_STAGE, PROGRESS_DEADLINE, FAILURE_RECEIPT, RUN_TERMINAL, CAPTURE_GAP }

@Serializable
enum class ProgressDeadlineCode { RUN_DEADLINE_EXCEEDED, GENERATION_STALLED, QUEUE_DEADLINE_EXCEEDED, LOAD_DEADLINE_EXCEEDED, MODEL_PROGRESS_TIMEOUT }

@Serializable
enum class RunDiagnosticRole {
    PRIMARY,
    CONTRIBUTOR,
    SYNTHESIS,
    DELEGATE,
    RESEARCH,
    REVIEWER,
    ASSIST,
    ATTRIBUTION,
    TITLE,
    FOLLOWUP,
    REPAIR,
    BENCHMARK,
    UNKNOWN;

    companion object {
        fun from(value: String) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Deliberately has no prompt, URL, header, exception text, model label or arbitrary field map. */
@Serializable
data class RunDiagnosticEvent(
    val schema: Int = 8,
    val eventId: String,
    val processSession: String,
    val runId: String,
    val streamId: String,
    val sequence: Long,
    val capturedAtMillis: Long,
    val monotonicNanos: Long? = null,
    val kind: RunDiagnosticKind,
    val role: RunDiagnosticRole,
    val invocationId: String? = null,
    val physicalAttempt: Long? = null,
    val fence: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val safetyTokens: Long? = null,
    val contextTokens: Long? = null,
    val visibleBytes: Long? = null,
    val reasoningBytes: Long? = null,
    val elapsedMillis: Long? = null,
    val successful: Boolean? = null,
    val measured: Boolean? = null,
    val phase: PreparationPhase? = null,
    val cacheHits: Long? = null,
    val cacheMisses: Long? = null,
    val staleCacheReads: Long? = null,
    val physicalAttemptId: String? = null,
    val progressStage: dev.chungjungsoo.gptmobile.data.agent.ObservedProgressStage? = null,
    val progressDeadline: ProgressDeadlineCode? = null,
    val outputBoundary: OutputTraceBoundary? = null,
    val outputRepresentation: OutputTextRepresentation? = null,
    val outputFingerprint: OutputFingerprint? = null,
    val outputPartId: String? = null,
    val outputOrigin: OutputOrigin = OutputOrigin.UNKNOWN,
    val localAdmissionReceipt: dev.chungjungsoo.gptmobile.data.localruntime.LocalAdmissionReceipt? = null,
    val providerFailure: ProviderFailureReceipt? = null
)

const val RUN_EVENT_SCHEMA = 8

@Serializable
data class RunJournalSnapshot(
    val eventSchema: Int = RUN_EVENT_SCHEMA,
    val captureLevel: String = "metadata",
    val events: List<RunDiagnosticEvent>,
    val highWatermarks: Map<String, Long>,
    val droppedEvents: Long,
    val missingBatches: Int,
    val storageFailed: Boolean,
    val missingSequences: Long = 0,
    val completeProviderWireCapture: Boolean = false,
    val captureEnabled: Boolean? = null,
    val counterScope: String = "process_session",
    val lossReasons: Map<String, Long> = emptyMap(),
    val coverageScope: String = "bounded_recent_window",
    val intentionallyOmittedBatches: Int = 0
)

/** Typed lifecycle metadata is retained for saved runs; verbose capture remains optional. Generation never depends on this journal. */
internal class RunDiagnosticJournal(
    private val vault: SecretVault,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val retentionMillis: Long = 7L * 86_400_000,
    private val maxStoredBytes: Long = 128L * 1024 * 1024,
    private val maxSnapshotBytes: Long = 4L * 1024 * 1024,
    private val maxSnapshotEvents: Int = 4096
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()
    private val pending = ArrayDeque<RunDiagnosticEvent>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var captureEpoch = 0L
    private var inFlight: List<RunDiagnosticEvent> = emptyList()
    private val writes = kotlinx.coroutines.sync.Mutex()
    private val sequences = mutableMapOf<String, Long>()
    private val terminals = mutableSetOf<String>()
    private data class FirstVisibleKey(val runId: String, val fence: Long?, val origin: OutputOrigin, val invocationId: String?)
    private val firstVisible = mutableSetOf<FirstVisibleKey>()
    private val outputLineage = OutputLineageRegistry()
    private var outputEpoch = 0L
    private val permittedRuns = mutableSetOf<String>()
    private val privateRuns = mutableSetOf<String>()
    private val dropped = AtomicLong()
    private val disabledSuppressed = AtomicLong()
    private val privacySuppressed = AtomicLong()
    private val rejected = AtomicLong()
    private val capacityDropped = AtomicLong()
    private val storageFailedEvents = AtomicLong()
    private val retentionPrunedBatches = AtomicLong()

    @Volatile var enabled = false

    @Volatile var privateSession = false
        set(value) = synchronized(lock) {
            if (field != value) {
                outputEpoch++
                outputLineage.clear()
            }
            field = value
        }

    @Volatile private var failed = false
    private var retentionCheckedAt = 0L

    init {
        scope.launch {
            for (signal in wake) {
                val (batch, epoch) = synchronized(lock) {
                    pending.toList().also {
                        pending.clear()
                        inFlight = it
                    } to captureEpoch
                }
                if (batch.isEmpty()) continue
                try {
                    if (synchronized(lock) { epoch != captureEpoch }) continue
                    // Keep each encrypted vault record below the vault's 64 KiB bound.
                    for ((run, events) in batch.groupBy { it.runId }) {
                        if (synchronized(lock) { run !in permittedRuns }) continue
                        // Persist the allocated sequence boundary separately from retained event batches.
                        // A later restart or retention eviction must not turn a missing tail into completeness.
                        val checkpoint = RunJournalCheckpoint(run, "$sessionId:$run", synchronized(lock) { sequences[run] ?: 0L }, now())
                        val checkpointReference = "journalcheckpoint-${identity(run)}-${identity(sessionId)}"
                        val checkpointBytes = json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8)
                        try {
                            writes.withLock {
                                if (synchronized(lock) { epoch != captureEpoch || run !in permittedRuns }) return@withLock
                                vault.put(checkpointReference, checkpointBytes)
                                if (synchronized(lock) { run !in permittedRuns }) vault.delete(checkpointReference)
                            }
                        } finally {
                            checkpointBytes.fill(0)
                        }
                        // Pack by encoded bytes as well as event count: enriched typed receipts can
                        // exceed a 64 KiB vault record even when a fixed batch contains only 32 events.
                        var encoded = mutableListOf<String>()
                        var encodedBytes = 2
                        suspend fun flush() {
                            if (encoded.isEmpty()) return
                            val reference = "journal-${identity(run)}-${now()}-${UUID.randomUUID()}"
                            val bytes = encoded.joinToString(",", "[", "]").toByteArray(Charsets.UTF_8)
                            try {
                                writes.withLock {
                                    if (synchronized(lock) { epoch != captureEpoch || run !in permittedRuns }) return@withLock
                                    vault.put(reference, bytes)
                                    if (synchronized(lock) { run !in permittedRuns }) vault.delete(reference)
                                }
                            } finally {
                                bytes.fill(0)
                            }
                            encoded = mutableListOf()
                            encodedBytes = 2
                        }
                        for (event in events) {
                            val value = json.encodeToString(event)
                            val length = value.toByteArray(Charsets.UTF_8).size
                            if (length + 2 > 60 * 1024) {
                                failed = true
                                dropped.incrementAndGet()
                                storageFailedEvents.incrementAndGet()
                                continue
                            }
                            if (encoded.size >= 32 || encodedBytes + length + 1 > 60 * 1024) flush()
                            encoded += value
                            encodedBytes += length + 1
                        }
                        flush()
                    }
                    // Encoding/packing stays outside the storage lock. Frozen exports
                    // include this immutable in-flight batch between physical writes.
                    writes.withLock {
                        if (synchronized(lock) { epoch == captureEpoch } && (now() - retentionCheckedAt >= 60_000 || retentionCheckedAt == 0L)) {
                            prune()
                            retentionCheckedAt = now()
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    dropped.addAndGet(batch.size.toLong())
                    storageFailedEvents.addAndGet(batch.size.toLong())
                } finally {
                    synchronized(lock) { inFlight = emptyList() }
                }
            }
        }
    }

    /** Unknown privacy scopes cannot persist diagnostics; revocation is sticky for this run. */
    fun setRunPrivacy(runId: String, isTemporary: Boolean) = synchronized(lock) {
        if (isTemporary) {
            privateRuns += runId
            permittedRuns.remove(runId)
            pending.removeAll { it.runId == runId }
            outputLineage.removeRun(runId)
            firstVisible.removeAll { it.runId == runId }
        } else if (runId !in privateRuns && permittedRuns.size < 10_000) {
            permittedRuns += runId
        }
    }

    suspend fun revokeRun(runId: String) {
        setRunPrivacy(runId, isTemporary = true)
        writes.withLock {
            try {
                vault.references().filter { it.startsWith("journal-${identity(runId)}-") || it.startsWith("journalcheckpoint-${identity(runId)}-") }.forEach { vault.delete(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            }
        }
    }

    fun record(
        runId: String,
        kind: RunDiagnosticKind,
        role: RunDiagnosticRole = RunDiagnosticRole.UNKNOWN,
        invocationId: String? = null,
        physicalAttempt: Long? = null,
        fence: Long? = null,
        inputTokens: Long? = null,
        outputTokens: Long? = null,
        safetyTokens: Long? = null,
        contextTokens: Long? = null,
        visibleBytes: Long? = null,
        reasoningBytes: Long? = null,
        elapsedMillis: Long? = null,
        successful: Boolean? = null,
        measured: Boolean? = null,
        phase: PreparationPhase? = null,
        cacheHits: Long? = null,
        cacheMisses: Long? = null,
        staleCacheReads: Long? = null,
        physicalAttemptId: String? = null,
        progressStage: dev.chungjungsoo.gptmobile.data.agent.ObservedProgressStage? = null,
        progressDeadline: ProgressDeadlineCode? = null,
        outputBoundary: OutputTraceBoundary? = null,
        outputRepresentation: OutputTextRepresentation? = null,
        outputFingerprint: OutputFingerprint? = null,
        outputPartId: String? = null,
        localAdmissionReceipt: dev.chungjungsoo.gptmobile.data.localruntime.LocalAdmissionReceipt? = null,
        providerFailure: ProviderFailureReceipt? = null,
        outputOrigin: OutputOrigin = OutputOrigin.UNKNOWN
    ): RunDiagnosticEvent? {
        if (!enabled && kind !in minimalKinds) {
            disabledSuppressed.incrementAndGet()
            return null
        }
        if (privateSession) {
            privacySuppressed.incrementAndGet()
            return null
        }
        if (providerFailure?.valid() == false) {
            dropped.incrementAndGet()
            rejected.incrementAndGet()
            return null
        }
        // Identifiers are internally generated IDs. Reject accidental content at the boundary.
        if (!safeId(runId) || invocationId != null && !safeId(invocationId) || physicalAttemptId != null && !safeId(physicalAttemptId)) {
            dropped.incrementAndGet()
            rejected.incrementAndGet()
            return null
        }
        if (listOfNotNull(physicalAttempt, fence, inputTokens, outputTokens, safetyTokens, contextTokens, visibleBytes, reasoningBytes, elapsedMillis, cacheHits, cacheMisses, staleCacheReads).any { it < 0 }) {
            dropped.incrementAndGet()
            rejected.incrementAndGet()
            return null
        }
        if (outputPartId != null && !outputPartId.matches(Regex("[0-9:]{1,40}|sha256:[a-f0-9]{64}"))) {
            dropped.incrementAndGet()
            rejected.incrementAndGet()
            return null
        }
        if ((outputBoundary == null) != (outputRepresentation == null) || (outputBoundary == null) != (outputFingerprint == null) || outputFingerprint?.let { it.utf8Bytes < 0 || !it.sha256.matches(Regex("[a-f0-9]{64}")) } == true) {
            dropped.incrementAndGet()
            rejected.incrementAndGet()
            return null
        }
        val event = synchronized(lock) {
            if ((!enabled && kind !in minimalKinds) || privateSession || runId !in permittedRuns || runId in terminals && kind in setOf(RunDiagnosticKind.RUN_TERMINAL, RunDiagnosticKind.REDUCER_CHECKPOINT, RunDiagnosticKind.SEGMENT_COMMITTED)) return null
            val explicitOrigin = outputOrigin
            val lineage = if (outputFingerprint != null && outputBoundary != OutputTraceBoundary.RENDER_TRANSFORM) {
                outputLineage.resolve(runId, outputFingerprint, OutputLineage(explicitOrigin, invocationId))
            } else {
                OutputLineage(explicitOrigin, invocationId)
            }
            val visibleKey = FirstVisibleKey(runId, fence, lineage.origin, lineage.invocationId)
            if (kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT && visibleKey in firstVisible) return null
            val prior = sequences[runId] ?: 0L
            if (prior == Long.MAX_VALUE || prior == 0L && sequences.size >= 10_000) {
                dropped.incrementAndGet()
                capacityDropped.incrementAndGet()
                return null
            }
            val sequence = prior + 1L
            sequences[runId] = sequence
            val event = RunDiagnosticEvent(eventId = UUID.randomUUID().toString(), processSession = sessionId, runId = runId, streamId = "$sessionId:$runId", sequence = sequence, capturedAtMillis = now(), monotonicNanos = System.nanoTime(), kind = kind, role = role, invocationId = lineage.invocationId, physicalAttempt = physicalAttempt, fence = fence, inputTokens = inputTokens, outputTokens = outputTokens, safetyTokens = safetyTokens, contextTokens = contextTokens, visibleBytes = visibleBytes, reasoningBytes = reasoningBytes, elapsedMillis = elapsedMillis, successful = successful, measured = measured, phase = phase, cacheHits = cacheHits, cacheMisses = cacheMisses, staleCacheReads = staleCacheReads, physicalAttemptId = physicalAttemptId, progressStage = progressStage, progressDeadline = progressDeadline, outputBoundary = outputBoundary, outputRepresentation = outputRepresentation, outputFingerprint = outputFingerprint, outputPartId = outputPartId, outputOrigin = lineage.origin, localAdmissionReceipt = localAdmissionReceipt, providerFailure = providerFailure)
            val critical = kind in criticalKinds
            if (!critical && pending.size >= 480) {
                dropped.incrementAndGet()
                capacityDropped.incrementAndGet()
                return null
            }
            if (pending.size >= 512) {
                val sample = pending.indexOfFirst { it.kind == RunDiagnosticKind.REDUCER_CHECKPOINT }
                    .takeIf { it >= 0 } ?: pending.indexOfFirst { it.kind !in criticalKinds }
                if (sample >= 0) {
                    pending.removeAt(sample)
                } else {
                    dropped.incrementAndGet()
                    capacityDropped.incrementAndGet()
                    return null
                }
                dropped.incrementAndGet()
                capacityDropped.incrementAndGet()
            }
            if (kind == RunDiagnosticKind.RUN_TERMINAL) terminals += runId
            if (kind == RunDiagnosticKind.FIRST_VISIBLE_TEXT) firstVisible += visibleKey
            pending.addLast(event)
            event
        }
        wake.trySend(Unit)
        return event
    }

    /** Invoke only after this prepared content's drawContent returns. The background renderer
     * supplies provenance for those exact bytes; this callback does not hash or read storage. */
    fun firstVisibleDraw(owner: OutputTraceOwner) {
        record(owner.runId, RunDiagnosticKind.FIRST_VISIBLE_TEXT, invocationId = owner.invocationId, fence = owner.fence, measured = true, outputPartId = outputTracePartId(owner.partId), outputOrigin = owner.origin)
    }

    /** Call from a background worker after the corresponding boundary has accepted the text.
     * Unknown or mixed output stays unlinked unless identical bytes have unambiguous provenance. */
    fun traceOutput(runId: String, boundary: OutputTraceBoundary, text: String, fence: Long? = null, invocationId: String? = null, representation: OutputTextRepresentation = OutputTextRepresentation.VISIBLE_UTF8, partId: String? = null, outputOrigin: OutputOrigin = OutputOrigin.UNKNOWN): OutputLineage {
        if (!enabled || privateSession) return OutputLineage()
        val epoch = synchronized(lock) { if (runId in permittedRuns) captureEpoch to outputEpoch else null } ?: return OutputLineage()
        return runCatching {
            val fingerprint = outputFingerprint(text)
            synchronized(lock) {
                if (epoch != (captureEpoch to outputEpoch) || !enabled || privateSession || runId !in permittedRuns) return@synchronized OutputLineage()
                val event = record(runId, RunDiagnosticKind.OUTPUT_CHECKPOINT, invocationId = invocationId, fence = fence, outputBoundary = boundary, outputRepresentation = representation, outputFingerprint = fingerprint, outputPartId = outputTracePartId(partId), outputOrigin = outputOrigin)
                event?.let { OutputLineage(it.outputOrigin, it.invocationId) } ?: OutputLineage()
            }
        }.getOrElse {
            failed = true
            OutputLineage()
        }
    }

    /** Read on an IO worker. Frozen watermarks exclude later samples; byteLimit bounds retained event JSON as well as decrypted batches. */
    suspend fun snapshot(runIds: Set<String>, byteLimit: Long = maxSnapshotBytes, eventLimit: Int = maxSnapshotEvents): RunJournalSnapshot {
        val boundedBytes = byteLimit.coerceIn(1, maxSnapshotBytes)
        val boundedEvents = eventLimit.coerceIn(1, maxSnapshotEvents)
        val (watermarks, queued, epoch) = synchronized(lock) {
            Triple(runIds.associate { "$sessionId:$it" to (sequences[it] ?: 0L) }, (pending + inFlight).filter { it.runId in runIds }, captureEpoch)
        }
        val events = mutableListOf<RunDiagnosticEvent>()
        var missing = 0
        var intentionallyOmitted = 0
        var snapshotBytes = 0L
        try {
            val prefixes = runIds.map { "journal-${identity(it)}-" }
            for (reference in vault.references().filter { ref -> prefixes.any(ref::startsWith) }.sortedByDescending { it.split('-').getOrNull(2)?.toLongOrNull() ?: 0L }) {
                try {
                    if (snapshotBytes >= boundedBytes || events.size >= boundedEvents) {
                        intentionallyOmitted++
                        missing++
                        continue
                    }
                    if (vault.storedSize(reference)?.let { it > boundedBytes - snapshotBytes } == true) {
                        intentionallyOmitted++
                        missing++
                        continue
                    }
                    val bytes = vault.read(reference)
                    if (bytes == null) {
                        missing++
                    } else {
                        try {
                            snapshotBytes += bytes.size
                            if (snapshotBytes > boundedBytes) {
                                intentionallyOmitted++
                                missing++
                            } else {
                                events += json.decodeFromString<List<RunDiagnosticEvent>>(bytes.decodeToString())
                            }
                        } finally {
                            bytes.fill(0)
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    missing++
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            missing++
        }
        val candidates = (events + queued).distinctBy { it.eventId }.filter { it.runId in runIds && synchronized(lock) { it.runId !in privateRuns } && it.sequence <= (watermarks[it.streamId] ?: Long.MAX_VALUE) }.sortedWith(compareBy<RunDiagnosticEvent> { it.capturedAtMillis }.thenBy { it.streamId }.thenBy { it.sequence })
        // Include queued/in-flight events in both caps and keep the newest evidence.
        // Serialize one bounded event at a time; never materialize the entire event array.
        val retained = ArrayList<RunDiagnosticEvent>(minOf(candidates.size, boundedEvents))
        var retainedBytes = 2L // JSON array brackets.
        for (event in candidates.asReversed()) {
            if (retained.size >= boundedEvents) break
            val eventBytes = json.encodeToString(event).toByteArray(Charsets.UTF_8).size.toLong()
            val required = eventBytes + if (retained.isEmpty()) 0 else 1
            if (retainedBytes + required > boundedBytes) continue
            retained += event
            retainedBytes += required
        }
        retained.reverse()
        // A read can overlap clear or privacy revocation. Publish only an authorized
        // frozen boundary, including its watermarks; stale identifiers are data too.
        return synchronized(lock) {
            val revoked = captureEpoch != epoch || privateSession
            val allowedRuns = if (revoked) emptySet() else runIds - privateRuns
            val authorized = retained.filter { it.runId in allowedRuns }
            val grouped = authorized.groupBy { it.streamId }
            val currentStreams = allowedRuns.mapTo(hashSetOf()) { "$sessionId:$it" }
            val frozen = grouped.mapValues { (_, rows) -> rows.maxOf { it.sequence } } + watermarks.filterKeys { it in currentStreams }
            RunJournalSnapshot(
                events = authorized,
                highWatermarks = frozen,
                droppedEvents = dropped.get(),
                missingBatches = if (revoked) 0 else missing,
                storageFailed = failed,
                missingSequences = frozen.entries.sumOf { (stream, high) -> (high - grouped[stream].orEmpty().size).coerceAtLeast(0) },
                captureEnabled = enabled,
                intentionallyOmittedBatches = if (revoked) 0 else intentionallyOmitted,
                coverageScope = if (revoked) "capture_revoked" else "bounded_recent_window",
                captureLevel = if (enabled) "metadata" else "minimal_lifecycle",
                lossReasons = mapOf("suppressed_disabled" to disabledSuppressed.get(), "suppressed_private" to privacySuppressed.get(), "rejected_validation" to rejected.get(), "capacity_dropped" to capacityDropped.get(), "storage_failed_events" to storageFailedEvents.get(), "retention_pruned_batches" to retentionPrunedBatches.get())
            )
        }
    }

    /** Export every retained batch at a frozen boundary, decoding only one vault record at a time. */
    suspend fun stream(
        runId: String,
        beforeRead: suspend () -> Unit,
        consume: suspend (RunDiagnosticEvent) -> Unit
    ): RunJournalExportCoverage {
        beforeRead()
        val capturedAt = now()
        val runPrefix = "journal-${identity(runId)}-"
        val statePrefix = "journalcheckpoint-${identity(runId)}-"
        val boundaryRequestedNanos = System.nanoTime()
        var boundaryLockWaitNanos = 0L
        var checkpointReadDecodeNanos = 0L
        var batchReadDecodeNanos = 0L
        var decodedBatchCount = 0L
        // Freeze the checkpoint VALUES with reference names. A mutable checkpoint may advance
        // immediately after releasing this lock; reading it later would invent a missing tail.
        val frozen = writes.withLock {
            boundaryLockWaitNanos = (System.nanoTime() - boundaryRequestedNanos).coerceAtLeast(0)
            val references = vault.references()
            val boundaries = linkedMapOf<String, Long>()
            var unreadableCheckpoints = 0
            for (reference in references.filter { it.startsWith(statePrefix) }) {
                beforeRead()
                val readStartedNanos = System.nanoTime()
                try {
                    val bytes = requireNotNull(vault.read(reference))
                    try {
                        require(bytes.size <= 4096)
                        val checkpoint = json.decodeFromString<RunJournalCheckpoint>(bytes.decodeToString())
                        require(checkpoint.runId == runId && checkpoint.highWatermark >= 0)
                        boundaries[checkpoint.streamId] = maxOf(boundaries[checkpoint.streamId] ?: 0L, checkpoint.highWatermark)
                    } finally {
                        bytes.fill(0)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unreadableCheckpoints++
                } finally {
                    checkpointReadDecodeNanos += (System.nanoTime() - readStartedNanos).coerceAtLeast(0)
                }
            }
            synchronized(lock) {
                FrozenJournalBoundary(references, boundaries, unreadableCheckpoints, sequences[runId] ?: 0L, (pending + inFlight).filter { it.runId == runId }, runId in privateRuns || privateSession, captureEpoch)
            }
        }
        if (frozen.privacySuppressed) return RunJournalExportCoverage(capturedAtMillis = capturedAt, privacySuppressed = true)
        val boundaries = frozen.highWatermarks.toMutableMap()
        val checkpointStreams = boundaries.keys.toMutableSet()
        val coverage = RunJournalSequenceCoverage()
        var unreadable = 0
        var records = 0L
        fun ensureCurrent() {
            check(synchronized(lock) { captureEpoch == frozen.epoch && runId !in privateRuns && !privateSession }) { "JOURNAL_CAPTURE_REVOKED" }
        }
        val currentStream = "$sessionId:$runId"
        boundaries[currentStream] = frozen.currentHighWatermark
        checkpointStreams += currentStream
        suspend fun emit(event: RunDiagnosticEvent) {
            beforeRead()
            ensureCurrent()
            if (event.runId != runId || event.sequence <= 0) return
            val boundary = boundaries[event.streamId]
            if (event.streamId in checkpointStreams && boundary != null && event.sequence > boundary) return
            if (!coverage.add(event.streamId, event.sequence)) return
            if (boundary == null) boundaries[event.streamId] = event.sequence
            // Legacy batches have no durable boundary: extend their observed maximum without claiming tail coverage.
            if (event.streamId !in checkpointStreams) boundaries[event.streamId] = maxOf(boundaries[event.streamId] ?: 0L, event.sequence)
            consume(event)
            records++
        }
        for (reference in frozen.references.filter { it.startsWith(runPrefix) }.sortedBy { it.split('-').getOrNull(2)?.toLongOrNull() ?: 0L }) {
            beforeRead()
            val readStartedNanos = System.nanoTime()
            val batch = try {
                val bytes = requireNotNull(vault.read(reference))
                try {
                    require(bytes.size <= 64 * 1024)
                    json.decodeFromString<List<RunDiagnosticEvent>>(bytes.decodeToString())
                } finally {
                    bytes.fill(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unreadable++
                continue
            } finally {
                batchReadDecodeNanos += (System.nanoTime() - readStartedNanos).coerceAtLeast(0)
            }
            decodedBatchCount++
            for (event in batch) emit(event)
        }
        for (event in frozen.queued) emit(event)
        beforeRead()
        ensureCurrent()
        val missing = boundaries.entries.sumOf { (stream, high) -> (high - coverage.count(stream)).coerceAtLeast(0) }
        val gaps = coverage.gaps(boundaries, 128)
        return RunJournalExportCoverage(
            capturedAtMillis = capturedAt,
            eventCount = records,
            highWatermarks = boundaries,
            missingSequences = missing,
            missingBatches = unreadable,
            unreadableCheckpoints = frozen.unreadableCheckpoints,
            unknownTailStreams = boundaries.keys.count { it !in checkpointStreams },
            storageFailed = failed,
            captureEnabled = enabled,
            missingRanges = gaps.first,
            missingRangesTruncated = gaps.second,
            retentionPrunedBatches = retentionPrunedBatches.get(),
            boundaryLockWaitNanos = boundaryLockWaitNanos,
            checkpointReadDecodeNanos = checkpointReadDecodeNanos,
            batchReadDecodeNanos = batchReadDecodeNanos,
            decodedBatchCount = decodedBatchCount
        )
    }

    private data class FrozenJournalBoundary(
        val references: Set<String>,
        val highWatermarks: Map<String, Long>,
        val unreadableCheckpoints: Int,
        val currentHighWatermark: Long,
        val queued: List<RunDiagnosticEvent>,
        val privacySuppressed: Boolean,
        val epoch: Long
    )

    suspend fun clear() = writes.withLock {
        synchronized(lock) {
            captureEpoch++
            pending.clear()
            inFlight = emptyList()
            sequences.clear()
            terminals.clear()
            firstVisible.clear()
            outputLineage.clear()
            outputEpoch++
            dropped.set(0)
            listOf(disabledSuppressed, privacySuppressed, rejected, capacityDropped, storageFailedEvents, retentionPrunedBatches).forEach { it.set(0) }
        }
        try {
            vault.references().filter { it.startsWith("journal-") || it.startsWith("journalcheckpoint-") }.forEach { vault.delete(it) }
            failed = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    private suspend fun prune() {
        val references = vault.references()
        val checkpoints = mutableListOf<Pair<String, Long>>()
        for (reference in references.filter { it.startsWith("journalcheckpoint-") }) {
            val captured = try {
                val bytes = requireNotNull(vault.read(reference))
                try {
                    require(bytes.size <= 4096)
                    json.decodeFromString<RunJournalCheckpoint>(bytes.decodeToString()).capturedAtMillis
                } finally {
                    bytes.fill(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep unreadable boundaries so exports report uncertainty instead of erasing it.
                Long.MAX_VALUE
            }
            checkpoints += reference to captured
        }
        checkpoints.sortedByDescending { it.second }.forEachIndexed { index, (reference, captured) ->
            if (captured < now() - retentionMillis || index >= 4096) vault.delete(reference)
        }
        var total = 0L
        var batches = 0
        for (reference in references.filter { it.startsWith("journal-") }.sortedByDescending { it.split('-').getOrNull(2)?.toLongOrNull() ?: 0L }) {
            val captured = reference.split('-').getOrNull(2)?.toLongOrNull() ?: 0L
            // Unknown stores are charged one maximum-size batch, never decrypted for sizing.
            total += vault.storedSize(reference) ?: (64L * 1024)
            batches++
            if (captured < now() - retentionMillis || total > maxStoredBytes || batches > 4096) {
                vault.delete(reference)
                retentionPrunedBatches.incrementAndGet()
            }
        }
    }

    private val minimalKinds = setOf(RunDiagnosticKind.FAILURE_RECEIPT, RunDiagnosticKind.INVOCATION_STARTED, RunDiagnosticKind.INVOCATION_FINISHED, RunDiagnosticKind.FIRST_TEXT, RunDiagnosticKind.FIRST_REASONING, RunDiagnosticKind.FIRST_TOOL, RunDiagnosticKind.FIRST_PROGRESS, RunDiagnosticKind.FIRST_VISIBLE_ANSWER, RunDiagnosticKind.FIRST_SYSTEM_ERROR, RunDiagnosticKind.FIRST_APPLICATION_REPORT, RunDiagnosticKind.FIRST_RETAINED_CONTENT, RunDiagnosticKind.FIRST_UNATTRIBUTED_OUTPUT, RunDiagnosticKind.INTENT_CONVERSATIONAL, RunDiagnosticKind.INTENT_SUBSTANTIVE, RunDiagnosticKind.TOOL_ADMITTED, RunDiagnosticKind.TOOL_RESULT, RunDiagnosticKind.CONTINUATION_SELECTED, RunDiagnosticKind.VERIFICATION_REJECTED, RunDiagnosticKind.ANSWER_REWRITE, RunDiagnosticKind.RUN_TERMINAL)
    private val criticalKinds = setOf(RunDiagnosticKind.FAILURE_RECEIPT, RunDiagnosticKind.INTENT_CONVERSATIONAL, RunDiagnosticKind.INTENT_SUBSTANTIVE, RunDiagnosticKind.VERIFICATION_REJECTED, RunDiagnosticKind.ANSWER_REWRITE) + setOf(RunDiagnosticKind.RUN_TERMINAL, RunDiagnosticKind.INVOCATION_FINISHED, RunDiagnosticKind.SEGMENT_COMMITTED, RunDiagnosticKind.PROGRESS_DEADLINE, RunDiagnosticKind.CAPTURE_GAP)

    private fun identity(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun safeId(value: String) = value.length in 1..192 && value.all { it.isLetterOrDigit() || it in "_.:-" }
}

internal object RunDiagnostics {
    @Volatile var journal: RunDiagnosticJournal? = null
}
