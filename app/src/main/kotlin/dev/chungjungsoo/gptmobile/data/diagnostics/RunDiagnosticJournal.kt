package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class RunDiagnosticKind { PREPARATION_PHASE, REQUEST_SERIALIZED, INVOCATION_STARTED, INVOCATION_FINISHED, ASSIST_DECISION, REDUCER_CHECKPOINT, SEGMENT_COMMITTED, RUN_TERMINAL, CAPTURE_GAP }

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
    val schema: Int = 5,
    val eventId: String,
    val processSession: String,
    val runId: String,
    val streamId: String,
    val sequence: Long,
    val capturedAtMillis: Long,
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
    val physicalAttemptId: String? = null
)

@Serializable
data class RunJournalSnapshot(
    val eventSchema: Int = 5,
    val captureLevel: String = "metadata",
    val events: List<RunDiagnosticEvent>,
    val highWatermarks: Map<String, Long>,
    val droppedEvents: Long,
    val missingBatches: Int,
    val storageFailed: Boolean,
    val missingSequences: Long = 0,
    val completeProviderWireCapture: Boolean = false
)

/** Optional capture only. Generation state and accounting must not depend on this journal. */
internal class RunDiagnosticJournal(
    private val vault: SecretVault,
    scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val retentionMillis: Long = 7L * 86_400_000,
    private val maxStoredBytes: Long = 128L * 1024 * 1024
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
    private val permittedRuns = mutableSetOf<String>()
    private val privateRuns = mutableSetOf<String>()
    private val dropped = AtomicLong()

    @Volatile var enabled = false

    @Volatile var privateSession = false

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
                    writes.withLock {
                        if (synchronized(lock) { epoch != captureEpoch }) return@withLock
                        // Keep each encrypted vault record below the vault's 64 KiB bound.
                        for ((run, events) in batch.groupBy { it.runId }) {
                            if (synchronized(lock) { run !in permittedRuns }) continue
                            events.chunked(32).forEach { chunk ->
                                val reference = "journal-${identity(run)}-${now()}-${UUID.randomUUID()}"
                                val bytes = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)
                                try {
                                    vault.put(reference, bytes)
                                    if (synchronized(lock) { run !in permittedRuns }) vault.delete(reference)
                                } finally {
                                    bytes.fill(0)
                                }
                            }
                        }
                        if (now() - retentionCheckedAt >= 60_000 || retentionCheckedAt == 0L) {
                            prune()
                            retentionCheckedAt = now()
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    dropped.addAndGet(batch.size.toLong())
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
        } else if (runId !in privateRuns && permittedRuns.size < 10_000) {
            permittedRuns += runId
        }
    }

    suspend fun revokeRun(runId: String) {
        setRunPrivacy(runId, isTemporary = true)
        writes.withLock {
            try {
                vault.references().filter { it.startsWith("journal-${identity(runId)}-") }.forEach { vault.delete(it) }
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
        physicalAttemptId: String? = null
    ) {
        if (!enabled || privateSession) return
        // Identifiers are internally generated IDs. Reject accidental content at the boundary.
        if (!safeId(runId) || invocationId != null && !safeId(invocationId) || physicalAttemptId != null && !safeId(physicalAttemptId)) {
            dropped.incrementAndGet()
            return
        }
        if (listOfNotNull(physicalAttempt, fence, inputTokens, outputTokens, safetyTokens, contextTokens, visibleBytes, reasoningBytes, elapsedMillis).any { it < 0 }) {
            dropped.incrementAndGet()
            return
        }
        synchronized(lock) {
            if (!enabled || privateSession || runId !in permittedRuns || runId in terminals && kind in setOf(RunDiagnosticKind.RUN_TERMINAL, RunDiagnosticKind.REDUCER_CHECKPOINT, RunDiagnosticKind.SEGMENT_COMMITTED)) return
            val prior = sequences[runId] ?: 0L
            if (prior == Long.MAX_VALUE || prior == 0L && sequences.size >= 10_000) {
                dropped.incrementAndGet()
                return
            }
            val sequence = prior + 1L
            sequences[runId] = sequence
            if (kind == RunDiagnosticKind.RUN_TERMINAL) terminals += runId
            val event = RunDiagnosticEvent(eventId = UUID.randomUUID().toString(), processSession = sessionId, runId = runId, streamId = "$sessionId:$runId", sequence = sequence, capturedAtMillis = now(), kind = kind, role = role, invocationId = invocationId, physicalAttempt = physicalAttempt, fence = fence, inputTokens = inputTokens, outputTokens = outputTokens, safetyTokens = safetyTokens, contextTokens = contextTokens, visibleBytes = visibleBytes, reasoningBytes = reasoningBytes, elapsedMillis = elapsedMillis, successful = successful, measured = measured, phase = phase, physicalAttemptId = physicalAttemptId)
            if (pending.size >= 512) {
                val sample = pending.indexOfFirst { it.kind == RunDiagnosticKind.REDUCER_CHECKPOINT }
                if (sample >= 0) {
                    pending.removeAt(sample)
                } else {
                    dropped.incrementAndGet()
                    return
                }
                dropped.incrementAndGet()
            }
            pending.addLast(event)
        }
        wake.trySend(Unit)
    }

    /** Read on an IO worker. Frozen stream watermarks exclude subsequent samples. */
    suspend fun snapshot(runIds: Set<String>): RunJournalSnapshot {
        val (watermarks, queued) = synchronized(lock) {
            runIds.associate { "$sessionId:$it" to (sequences[it] ?: 0L) } to (pending + inFlight).filter { it.runId in runIds }
        }
        val events = mutableListOf<RunDiagnosticEvent>()
        var missing = 0
        try {
            val prefixes = runIds.map { "journal-${identity(it)}-" }
            for (reference in vault.references().filter { ref -> prefixes.any(ref::startsWith) }.sorted()) {
                try {
                    val bytes = vault.read(reference)
                    if (bytes == null) {
                        missing++
                    } else {
                        try {
                            events += json.decodeFromString<List<RunDiagnosticEvent>>(bytes.decodeToString())
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
        val retained = (events + queued).distinctBy { it.eventId }.filter { it.runId in runIds && synchronized(lock) { it.runId !in privateRuns } && it.sequence <= (watermarks[it.streamId] ?: Long.MAX_VALUE) }.sortedWith(compareBy<RunDiagnosticEvent> { it.capturedAtMillis }.thenBy { it.streamId }.thenBy { it.sequence })
        val frozen = retained.groupBy { it.streamId }.mapValues { (_, rows) -> rows.maxOf { it.sequence } } + watermarks
        return RunJournalSnapshot(events = retained, highWatermarks = frozen, droppedEvents = dropped.get(), missingBatches = missing, storageFailed = failed, missingSequences = frozen.entries.sumOf { (stream, high) -> (high - retained.count { it.streamId == stream }).coerceAtLeast(0) })
    }

    suspend fun clear() = writes.withLock {
        synchronized(lock) {
            captureEpoch++
            pending.clear()
            inFlight = emptyList()
            sequences.clear()
            terminals.clear()
            dropped.set(0)
        }
        try {
            vault.references().filter { it.startsWith("journal-") }.forEach { vault.delete(it) }
            failed = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    private suspend fun prune() {
        var total = 0L
        for (reference in vault.references().filter { it.startsWith("journal-") }.sortedByDescending { it.split('-').getOrNull(2)?.toLongOrNull() ?: 0L }) {
            val captured = reference.split('-').getOrNull(2)?.toLongOrNull() ?: 0L
            val bytes = vault.read(reference)
            total += bytes?.size ?: 0
            bytes?.fill(0)
            if (captured < now() - retentionMillis || total > maxStoredBytes) vault.delete(reference)
        }
    }

    private fun identity(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun safeId(value: String) = value.length in 1..192 && value.all { it.isLetterOrDigit() || it in "_.:-" }
}

internal object RunDiagnostics {
    @Volatile var journal: RunDiagnosticJournal? = null
}
