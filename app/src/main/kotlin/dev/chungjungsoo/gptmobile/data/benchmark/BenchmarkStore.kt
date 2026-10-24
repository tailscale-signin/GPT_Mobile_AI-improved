package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.localruntime.QualificationStatus
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimePlanDecisionRecord
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeQualificationResult
import dev.chungjungsoo.gptmobile.data.localruntime.RuntimeTrial
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class BenchmarkBackupPayload(
    val schemaVersion: Int = 3,
    val exportedAt: Long = System.currentTimeMillis(),
    val runs: List<BenchmarkRun> = emptyList(),
    val scoreTable: BenchmarkScoreSnapshot? = null,
    val scoreHistory: List<BenchmarkScoreSnapshot> = emptyList(),
    val enrollment: Map<String, String>? = null,
    val runtimeTrials: List<RuntimeTrial> = emptyList(),
    val runtimeQualifications: List<RuntimeQualificationResult> = emptyList(),
    val runtimeDecisions: List<RuntimePlanDecisionRecord> = emptyList(),
    val batches: List<BenchmarkBatch> = emptyList(),
    val attempts: List<BenchmarkAttempt> = emptyList(),
    val batchEvents: List<BenchmarkBatchEvent> = emptyList()
)

internal class BenchmarkStorageFailure(cause: Throwable) : Exception("Canonical benchmark storage is unavailable; the queue is paused.", cause)

@Singleton
class BenchmarkStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(context.applicationContext, BenchmarkDatabase::class.java, "benchmark_v2.db").addMigrations(BenchmarkDatabase.MIGRATION_1_2, BenchmarkDatabase.MIGRATION_2_3).build()
    private val dao = database.dao()
    private val mutableRuntimeTrials = MutableStateFlow<List<RuntimeTrial>>(emptyList())
    val runtimeTrials = mutableRuntimeTrials.asStateFlow()
    private val mutableQualifications = MutableStateFlow<List<RuntimeQualificationResult>>(emptyList())
    val runtimeQualifications = mutableQualifications.asStateFlow()
    private val mutableSnapshot = MutableStateFlow<BenchmarkScoreSnapshot?>(null)
    internal val snapshot = mutableSnapshot.asStateFlow()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutableBatches = MutableStateFlow<List<BenchmarkBatch>>(emptyList())
    val batches = mutableBatches.asStateFlow()
    private var mirrorJob: kotlinx.coroutines.Job? = null
    private var scoreJob: kotlinx.coroutines.Job? = null
    private val mutex = Mutex()
    private var activeRevisions: Map<String, String>? = preferences.getString("active_revisions", null)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
    private val mutableHistory = MutableStateFlow<List<BenchmarkRun>>(emptyList())
    val history = mutableHistory.asStateFlow()
    private val mutableWarning = MutableStateFlow<String?>(null)
    val warning = mutableWarning.asStateFlow()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            if (failure !is Exception) throw failure
            if (failure !is kotlinx.coroutines.CancellationException) {
                loadWarning = "Benchmark background maintenance failed. Saved results were not replaced."
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Benchmark", dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(failure.message.orEmpty()).take(300), "W")
            }
        }
    )

    @Volatile private var writingMirror = false

    @Volatile private var restoreRequested = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!writingMirror && (key == "history" || key == null)) {
            restoreRequested = true
            scope.launch {
                mutex.withLock {
                    loaded = false
                    loadLocked()
                }
            }
        }
    }
    private var loaded = false
    private var mirrorRecoveryBlocked = false
    var loadWarning: String?
        get() = mutableWarning.value
        private set(value) {
            mutableWarning.value = value
        }

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        scope.launch {
            while (true) {
                delay(60_000)
                mutex.withLock {
                    if (loaded) {
                        val current = mutableSnapshot.value
                        val recalculated = withContext(Dispatchers.Default) { DynamicScoreEngine.snapshot(mutableHistory.value, (current?.generation ?: 0) + 1, System.currentTimeMillis(), activeRevisions) }
                        if (recalculated.rows != current?.rows) publishBestEffort(mutableHistory.value, recalculated)
                    }
                }
            }
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        // Canonical rows are read independently of optional preferences recovery.
        val records = dao.runs()
        val stored = records.mapNotNull { runCatching { json.decodeFromString<BenchmarkRun>(it.payload) }.getOrNull() }
        val existing = dao.current()
        val initialized = records.isNotEmpty() || dao.canonicalInitialized()
        val saved = try {
            preferences.getString("history", null)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            loadWarning = "The optional backup mirror could not be read. Canonical results remain available."
            null
        }
        val recovered = saved?.let { raw ->
            val entries = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            val valid = entries.orEmpty().mapNotNull { entry -> runCatching { json.decodeFromJsonElement<BenchmarkRun>(entry) }.getOrNull() }
            if (entries == null || valid.size != entries.size) {
                // Keep the original for recovery instead of letting a later save destroy it.
                val preserved = try {
                    preferences.edit().putString("recovery_history", raw).commit()
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    false
                }
                mirrorRecoveryBlocked = !preserved
                loadWarning = if (preserved) "Some backup records could not be read. Canonical results and recovered valid records remain available." else "The optional backup mirror is damaged and its recovery copy could not be saved. Canonical results remain available; the original mirror is retained."
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Benchmark", "Recovered benchmark history with invalid records", "W")
            }
            valid
        }.orEmpty()
        // Room is authoritative after a crash between the transaction and backup mirror write.
        val source = if (!initialized) recovered else stored
        restoreRequested = false
        mutableSnapshot.value = existing?.let { runCatching { json.decodeFromString<BenchmarkScoreSnapshot>(it.payload) }.getOrNull() }
        if (source != stored) dao.insertRuns(source.map { BenchmarkRunRecord(it.id, it.startedAt, json.encodeToString(it)) })
        // Initialization is canonical even if the optional first score snapshot fails.
        // Otherwise deleting the last row could later resurrect an obsolete mirror.
        if (!initialized) dao.pointTo(BenchmarkCurrentRecord(generation = 0))
        mutableHistory.value = source
        mutableBatches.value = dao.batches().map { json.decodeFromString<BenchmarkBatch>(it.payload) }
        mutableRuntimeTrials.value = dao.runtimeTrials().map { json.decodeFromString<RuntimeTrial>(it.payload) }
        mutableQualifications.value = dao.runtimeQualifications().map { json.decodeFromString<RuntimeQualificationResult>(it.payload) }
        loaded = true
        publishBestEffort(source)
    }

    internal suspend fun updateEnrollment(revisions: Map<String, String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (revisions == activeRevisions) return@withLock
            activeRevisions = revisions.toMap()
            check(preferences.edit().putString("active_revisions", json.encodeToString(revisions)).commit()) { "Could not save benchmark enrollment." }
            loadLocked()
            publishBestEffort(mutableHistory.value)
        }
    }

    suspend fun save(run: BenchmarkRun) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            dao.insertRuns(listOf(BenchmarkRunRecord(run.id, run.startedAt, json.encodeToString(run))))
            // Publish canonical state before any optional mirror or score work.
            mutableHistory.value = dao.runs().map { json.decodeFromString<BenchmarkRun>(it.payload) }
            if (run.finished) publishBestEffort(mutableHistory.value)
            scheduleMirror()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            dao.deleteRun(id)
            mutableHistory.value = dao.runs().map { json.decodeFromString<BenchmarkRun>(it.payload) }
            publishBestEffort(mutableHistory.value)
            scheduleMirror()
        }
    }

    /** Selection, current attempt and case result advance in one canonical transaction. */
    internal suspend fun checkpoint(batch: BenchmarkBatch, run: BenchmarkRun? = null, attempts: List<BenchmarkAttempt> = emptyList(), event: String = "CHECKPOINT") = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                loadLocked()
                val saved = dao.batches().firstOrNull { it.id == batch.id }?.let { json.decodeFromString<BenchmarkBatch>(it.payload) }
                // A concurrent Stop action cannot be overwritten by a case checkpoint.
                val effectiveBatch = if (event in setOf("CASE_STARTED", "CASE_FINISHED", "PREFLIGHT", "PREFLIGHT_CLEANUP")) batch.copy(stopOrigin = saved?.stopOrigin ?: batch.stopOrigin) else batch
                var effectiveRun = run?.copy(samples = run.samples.map { sample -> sample.copy(attemptId = sample.attemptId ?: "${run.id}:${sample.testId}:0") })
                val existing = dao.attempts(batch.id).map { json.decodeFromString<BenchmarkAttempt>(it.payload) }.associateBy { it.id }.toMutableMap()
                val changed = attempts.map { it.id }.toMutableSet()
                attempts.forEach { existing[it.id] = it }
                effectiveRun = effectiveRun?.let { retainedRun ->
                    retainedRun.copy(
                        samples = retainedRun.samples.map { sample ->
                            existing[sample.attemptId]?.let { prior -> sample.copy(physicalRequestIds = sample.physicalRequestIds.ifEmpty { prior.physicalRequestIds }, invocationIds = sample.invocationIds.ifEmpty { prior.invocationIds }, logicalRequestId = sample.logicalRequestId ?: prior.logicalRequestId) } ?: sample
                        }
                    )
                }
                val finalizedRun = effectiveRun
                effectiveRun?.samples?.forEachIndexed { index, sample ->
                    val id = checkNotNull(sample.attemptId)
                    val ownerRun = checkNotNull(finalizedRun)
                    val started = existing[id] ?: BenchmarkAttempt(id, batch.id, ownerRun.queueOrdinal ?: 0, ownerRun.id, sample.testId, index, ownerRun.startedAt, 0)
                    val terminal = started.copy(
                        state = when (sample.outcome) {
                            BenchmarkOutcome.SKIPPED -> "SKIPPED"
                            BenchmarkOutcome.CANCELED -> "INTERRUPTED"
                            else -> "TERMINAL"
                        },
                        requestDispatched = sample.requestDispatched,
                        dispatchUncertain = sample.requestDispatched == null,
                        sample = sample,
                        physicalRequestIds = sample.physicalRequestIds,
                        logicalRequestId = sample.logicalRequestId ?: started.logicalRequestId,
                        invocationIds = sample.invocationIds
                    )
                    if (existing[id] != terminal) changed += id
                    existing[id] = terminal
                }
                if (finalizedRun?.finished == true) {
                    existing.replaceAll { id, attempt ->
                        if (attempt.runId == finalizedRun.id && attempt.state == "STARTED") {
                            changed += id
                            attempt.copy(state = "INTERRUPTED", dispatchUncertain = true)
                        } else {
                            attempt
                        }
                    }
                }
                checkpointLocked(effectiveBatch, effectiveRun, changed.mapNotNull(existing::get), event)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                throw BenchmarkStorageFailure(failure)
            }
        }
    }

    private suspend fun checkpointLocked(batch: BenchmarkBatch, run: BenchmarkRun?, attempts: List<BenchmarkAttempt>, event: String) {
        val sequence = dao.nextSequence(batch.id)
        dao.checkpoint(
            BenchmarkBatchRecord(batch.id, batch.createdAt, batch.state.name, json.encodeToString(batch)),
            batch.items.map { BenchmarkQueueRecord(batch.id, it.ordinal, it.runId, it.state.name, json.encodeToString(it)) },
            run?.let { BenchmarkRunRecord(it.id, it.startedAt, json.encodeToString(it)) },
            attempts.map { BenchmarkAttemptRecord(it.id, it.batchId, it.ordinal, it.caseId, json.encodeToString(it)) },
            BenchmarkEventRecord(batch.id, sequence, json.encodeToString(BenchmarkBatchEvent(batch.id, sequence, event, System.currentTimeMillis(), run?.id, attempts.lastOrNull()?.id)))
        )
        mutableBatches.value = (listOf(batch) + mutableBatches.value.filterNot { it.id == batch.id }).sortedByDescending { it.createdAt }
        if (run != null) mutableHistory.value = (listOf(run) + mutableHistory.value.filterNot { it.id == run.id }).sortedByDescending { it.startedAt }
        if (run?.finished == true) {
            scoreJob?.cancel()
            scoreJob = scope.launch {
                delay(100)
                mutex.withLock { publishBestEffort(mutableHistory.value) }
            }
        }
        scheduleMirror()
    }

    internal suspend fun recordStop(id: String, origin: BenchmarkStopOrigin, ordinal: Int?): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val batch = dao.batches().firstOrNull { it.id == id }?.let { json.decodeFromString<BenchmarkBatch>(it.payload) } ?: return@withLock false
            if (origin == BenchmarkStopOrigin.USER_SKIP && (batch.activeOrdinal != ordinal || ordinal == null)) return@withLock false
            checkpointLocked(batch.copy(stopOrigin = origin), null, emptyList(), origin.name)
            true
        }
    }

    internal suspend fun batch(id: String): BenchmarkBatch? = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                loadLocked()
                dao.batches().firstOrNull { it.id == id }?.let { json.decodeFromString<BenchmarkBatch>(it.payload) }
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw BenchmarkStorageFailure(failure)
        }
    }

    internal suspend fun recoverBatches() {
        load()
        batches.value.filter { it.state in setOf(BenchmarkBatchState.RUNNING, BenchmarkBatchState.QUEUED) || it.items.any { item -> item.state == BenchmarkQueueState.RUNNING } }.forEach { old ->
            val savedAttempts = dao.attempts(old.id).map { json.decodeFromString<BenchmarkAttempt>(it.payload) }
            val active = old.items.firstOrNull { it.state == BenchmarkQueueState.RUNNING }
            val savedRun = active?.let { item -> history.value.firstOrNull { it.id == item.runId } }
            // A crash after the final case transaction must not turn a fully recorded profile into a replay.
            val allCasesCommitted = savedRun != null &&
                savedRun.plannedTrials > 0 &&
                savedRun.samples.size == savedRun.plannedTrials &&
                savedRun.samples.map { it.testId }.toSet() == benchmarkSuiteForRun(savedRun).map { it.id }.toSet() &&
                savedAttempts.none { it.runId == savedRun.id && it.state == "STARTED" } &&
                savedRun.samples.none { it.outcome == BenchmarkOutcome.CANCELED }
            val terminal = when {
                !allCasesCommitted -> BenchmarkQueueState.INTERRUPTED
                old.stopOrigin == BenchmarkStopOrigin.USER_SKIP -> BenchmarkQueueState.SKIPPED
                savedRun!!.samples.any { it.executionFailure() || it.cleanupReceipt == "cleanup_unconfirmed_local_blocked" } -> BenchmarkQueueState.FAILED
                else -> BenchmarkQueueState.COMPLETED
            }
            val now = System.currentTimeMillis()
            val recoveredRun = savedRun?.takeIf { allCasesCommitted }?.copy(finished = true, state = terminal.name, canceled = false, cancellationOrigin = if (terminal == BenchmarkQueueState.SKIPPED) BenchmarkStopOrigin.USER_SKIP else null, endedAt = now, stoppedReason = if (terminal == BenchmarkQueueState.COMPLETED) null else savedRun.samples.firstOrNull { it.executionFailure() }?.error)
            val recovered = old.copy(
                state = if (old.state == BenchmarkBatchState.STORAGE_PAUSED) {
                    old.state
                } else if (old.stopOrigin == BenchmarkStopOrigin.USER_STOP) {
                    BenchmarkBatchState.STOPPED
                } else {
                    BenchmarkBatchState.INTERRUPTED
                },
                activeOrdinal = null,
                endedAt = now,
                stopOrigin = old.stopOrigin ?: BenchmarkStopOrigin.PROCESS_INTERRUPTION,
                items = old.items.map { if (it.state == BenchmarkQueueState.RUNNING) it.copy(state = terminal, endedAt = now, activeCaseId = null, completedCases = savedRun?.samples?.size ?: it.completedCases, failure = if (allCasesCommitted) savedRun?.samples?.firstOrNull { sample -> sample.executionFailure() }?.failure else BenchmarkFailure("PROCESS_INTERRUPTION", "unknown", "Android interrupted this profile; an in-flight request may have reached its provider.")) else it }
            )
            val interrupted = savedAttempts.filter { it.state == "STARTED" }.map { it.copy(state = "INTERRUPTED", dispatchUncertain = true) }
            checkpoint(recovered.copy(state = recovered.completionState()), recoveredRun, interrupted, "RECOVERED")
        }
    }

    internal suspend fun markInterruptedRuns() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val recovered = mutableHistory.value.map { run ->
                if (run.finished) {
                    run
                } else {
                    run.copy(
                        finished = true,
                        canceled = false,
                        state = "INTERRUPTED",
                        cancellationOrigin = BenchmarkStopOrigin.PROCESS_INTERRUPTION,
                        endedAt = System.currentTimeMillis(),
                        stoppedReason = run.stoppedReason ?: "Benchmark interrupted when Android stopped the app."
                    )
                }
            }
            if (recovered != mutableHistory.value) persist(recovered)
            val interrupted = mutableRuntimeTrials.value.filterNot { it.finished }.map { it.copy(finished = true, completed = false, failure = "Benchmark interrupted before its completion record was saved") }
            if (interrupted.isNotEmpty()) {
                dao.insertRuntimeTrials(interrupted.map { RuntimeTrialRecord(it.id, it.timestamp, json.encodeToString(it)) })
                val byId = interrupted.associateBy { it.id }
                mutableRuntimeTrials.value = mutableRuntimeTrials.value.map { byId[it.id] ?: it }
            }
        }
    }

    private suspend fun publishBestEffort(runs: List<BenchmarkRun>, prepared: BenchmarkScoreSnapshot? = null) {
        try {
            publish(runs, prepared)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            loadWarning = "Measurements are saved. Derived scores could not refresh and will be rebuilt from saved results."
        }
    }

    private suspend fun publish(runs: List<BenchmarkRun>, prepared: BenchmarkScoreSnapshot? = null) {
        val previous = dao.current()?.generation ?: 0L
        val next = prepared?.copy(generation = previous + 1) ?: withContext(Dispatchers.Default) {
            DynamicScoreEngine.snapshot(runs, previous + 1, System.currentTimeMillis(), activeRevisions)
        }
        if (next.rows == mutableSnapshot.value?.rows) return
        dao.publish(runs.map { BenchmarkRunRecord(it.id, it.startedAt, json.encodeToString(it)) }, BenchmarkSnapshotRecord(next.generation, next.createdAt, json.encodeToString(next)))
        mutableSnapshot.value = next
    }

    fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        scope.cancel()
        database.close()
    }

    suspend fun exportJson(includeScoreHistory: Boolean = false): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            json.encodeToString(
                BenchmarkBackupPayload(
                    runs = mutableHistory.value,
                    scoreTable = mutableSnapshot.value,
                    scoreHistory = if (includeScoreHistory) dao.snapshots().map { json.decodeFromString<BenchmarkScoreSnapshot>(it.payload) } else emptyList(),
                    enrollment = if (includeScoreHistory) activeRevisions else null,
                    runtimeTrials = mutableRuntimeTrials.value,
                    runtimeQualifications = mutableQualifications.value,
                    runtimeDecisions = dao.runtimeDecisions().map { json.decodeFromString<RuntimePlanDecisionRecord>(it.payload) },
                    batches = mutableBatches.value,
                    attempts = mutableBatches.value.flatMap { batch -> dao.attempts(batch.id).map { json.decodeFromString<BenchmarkAttempt>(it.payload) } },
                    batchEvents = if (includeScoreHistory) mutableBatches.value.flatMap { batch -> dao.events(batch.id).map { json.decodeFromString<BenchmarkBatchEvent>(it.payload) } } else emptyList()
                )
            )
        }
    }

    /** Restore benchmark history and rebuild the dynamic score tables from their source runs. */
    suspend fun importBackupJson(payload: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val runs = decodeBackupRuns(payload)
            loadLocked()
            val restoredRuns = runs.distinctBy { it.id }.map { run ->
                if (run.finished) run else run.copy(finished = true, canceled = false, state = "INTERRUPTED", cancellationOrigin = BenchmarkStopOrigin.PROCESS_INTERRUPTION, stoppedReason = run.stoppedReason ?: "Benchmark was in progress at export; restored without replaying requests.")
            }.sortedByDescending { it.startedAt }
            val bundle = runCatching { json.decodeFromString<BenchmarkBackupPayload>(payload) }.getOrNull()
            val trials = bundle?.runtimeTrials.orEmpty().map { it.copy(historical = true, finished = true, completed = it.finished && it.completed, failure = if (it.finished) it.failure else "Benchmark interrupted at backup time") }
            val qualifications = bundle?.runtimeQualifications.orEmpty().map { it.copy(status = QualificationStatus.HISTORICAL, actualBackendConfirmed = false, reason = "Restored evidence requires local verification") }
            val restoredBatches = bundle?.batches.orEmpty().map { batch ->
                if (batch.state in setOf(BenchmarkBatchState.RUNNING, BenchmarkBatchState.QUEUED) || batch.items.any { it.state == BenchmarkQueueState.RUNNING }) {
                    batch.copy(
                        state = BenchmarkBatchState.INTERRUPTED,
                        activeOrdinal = null,
                        endedAt = System.currentTimeMillis(),
                        stopOrigin = BenchmarkStopOrigin.PROCESS_INTERRUPTION,
                        items = batch.items.map { if (it.state == BenchmarkQueueState.RUNNING) it.copy(state = BenchmarkQueueState.INTERRUPTED, endedAt = System.currentTimeMillis()) else it }
                    )
                } else {
                    batch
                }
            }
            val restoredAttempts = bundle?.attempts.orEmpty().map { if (it.state == "STARTED") it.copy(state = "INTERRUPTED", dispatchUncertain = true) else it }
            val next = DynamicScoreEngine.snapshot(restoredRuns, (dao.current()?.generation ?: 0) + 1, System.currentTimeMillis(), activeRevisions)
            dao.restoreAll(
                restoredRuns.map { BenchmarkRunRecord(it.id, it.startedAt, json.encodeToString(it)) },
                BenchmarkSnapshotRecord(next.generation, next.createdAt, json.encodeToString(next)),
                trials.map { RuntimeTrialRecord(it.id, it.timestamp, json.encodeToString(it)) },
                qualifications.map { RuntimeQualificationRecord(it.plan.id, json.encodeToString(it)) },
                bundle?.runtimeDecisions.orEmpty().take(500).map { RuntimeDecisionRecord(it.id, it.timestamp, json.encodeToString(it.copy(historical = true))) },
                restoredBatches.map { BenchmarkBatchRecord(it.id, it.createdAt, it.state.name, json.encodeToString(it)) },
                restoredBatches.flatMap { batch -> batch.items.map { BenchmarkQueueRecord(batch.id, it.ordinal, it.runId, it.state.name, json.encodeToString(it)) } },
                restoredAttempts.map { BenchmarkAttemptRecord(it.id, it.batchId, it.ordinal, it.caseId, json.encodeToString(it)) },
                bundle?.batchEvents.orEmpty().map { BenchmarkEventRecord(it.batchId, it.sequence, json.encodeToString(it)) }
            )
            mutableRuntimeTrials.value = trials
            mutableQualifications.value = qualifications
            mutableHistory.value = restoredRuns
            mutableSnapshot.value = next
            mutableBatches.value = restoredBatches
            scheduleMirror()
        }
    }

    fun validateBackupJson(payload: String) {
        decodeBackupRuns(payload)
    }

    private fun decodeBackupRuns(payload: String): List<BenchmarkRun> {
        val decoded = json.parseToJsonElement(payload)
        val bundle = runCatching { json.decodeFromJsonElement<BenchmarkBackupPayload>(decoded) }.getOrNull()
        if (bundle != null) {
            val storageField = (decoded as? kotlinx.serialization.json.JsonObject)?.get("storageSchemaVersion")
            val storageVersion = if (storageField == null) bundle.schemaVersion else requireNotNull((storageField as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull) { "Invalid benchmark storage schema version." }
            require(bundle.schemaVersion in 1..3 && storageVersion in 1..3) { "This benchmark backup requires a newer app version." }
        }
        val runs = bundle?.runs ?: json.decodeFromJsonElement<List<BenchmarkRun>>(decoded)
        require(runs.size <= MAX_BACKUP_RUNS) { "Benchmark backup contains too many runs." }
        require(runs.all { it.id.isNotBlank() && it.profileUid.isNotBlank() }) { "Benchmark backup contains invalid records." }
        require(bundle?.batches.orEmpty().size <= MAX_BACKUP_RUNS && bundle?.attempts.orEmpty().size <= MAX_BACKUP_RUNS * 100) { "Benchmark queue backup is too large" }
        require(bundle?.batches.orEmpty().all { batch -> batch.id.isNotBlank() && batch.items.map { it.ordinal }.distinct().size == batch.items.size && batch.items.all { it.runId.isNotBlank() && it.profile.uid.isNotBlank() } }) { "Invalid benchmark queue" }
        require(bundle?.runtimeTrials.orEmpty().size <= MAX_BACKUP_RUNS && bundle?.runtimeQualifications.orEmpty().size <= MAX_BACKUP_RUNS) { "Runtime evidence backup is too large" }
        require(bundle?.runtimeTrials.orEmpty().all { it.id.isNotBlank() && it.durationMs >= 0 }) { "Invalid runtime trial" }
        return runs
    }

    suspend fun saveRuntimeTrial(trial: RuntimeTrial) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val next = (listOf(trial) + mutableRuntimeTrials.value.filterNot { it.id == trial.id }).sortedWith(compareByDescending<RuntimeTrial> { it.timestamp }.thenBy { it.id }).take(MAX_BACKUP_RUNS)
            val retainedIds = next.map { it.id }.toSet()
            val results = mutableQualifications.value.filter { retainedIds.containsAll(it.sourceRunIds) && trial.id !in it.sourceRunIds }
            val invalidated = mutableQualifications.value.filterNot { it in results }.map { it.plan.id }
            dao.appendRuntimeTrial(RuntimeTrialRecord(trial.id, trial.timestamp, json.encodeToString(trial)), MAX_BACKUP_RUNS, invalidated)
            mutableRuntimeTrials.value = next
            mutableQualifications.value = results
        }
    }

    internal suspend fun qualifyRuntime(result: RuntimeQualificationResult, release: RuntimeReleaseEvidence) {
        val failures = RuntimeReleaseGates.failures(release, result.plan.mode)
        require(failures.isEmpty()) { failures.joinToString("; ") }
        require(result.completedRuns >= 500 && result.failedRuns <= result.completedRuns / 99) { "Insufficient controlled runtime evidence" }
        saveQualification(result.copy(status = QualificationStatus.QUALIFIED, actualBackendConfirmed = true, qualityEquivalent = true))
    }

    internal suspend fun saveQualification(result: RuntimeQualificationResult) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            require(result.sourceRunIds.isNotEmpty()) { "Qualification needs source runs" }
            val source = mutableRuntimeTrials.value.filter { it.id in result.sourceRunIds }
            require(source.size == result.sourceRunIds.size && source.all { it.finished && !it.historical && it.key == result.key }) { "Qualification evidence is missing, historical or mismatched" }
            if (result.status == QualificationStatus.QUALIFIED) {
                require(source.size >= 500 && source.count { it.completed } == result.completedRuns && source.count { !it.completed } == result.failedRuns) { "Qualification counts must match the retained raw trials" }
                require(source.filter { it.completed }.all { it.nativeMetrics?.isValid == true && it.outputCharacters > 0 && it.plan == result.plan }) { "Completed qualification trials need real native counters and output" }
            }
            dao.insertQualifications(listOf(RuntimeQualificationRecord(result.plan.id, json.encodeToString(result))))
            mutableQualifications.value = listOf(result) + mutableQualifications.value.filterNot { it.plan.id == result.plan.id }
        }
    }

    suspend fun saveRuntimeDecision(record: RuntimePlanDecisionRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = listOf(RuntimeDecisionRecord(record.id, record.timestamp, json.encodeToString(record))) + dao.runtimeDecisions().filterNot { it.id == record.id }
            dao.replaceDecisions(next.take(500))
        }
    }

    suspend fun clearRuntimeEvidence() = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            dao.clearDecisions()
            dao.replaceRuntime(emptyList(), emptyList())
            mutableRuntimeTrials.value = emptyList()
            mutableQualifications.value = emptyList()
        }
    }

    private suspend fun persist(runs: List<BenchmarkRun>) {
        dao.insertRuns(runs.map { BenchmarkRunRecord(it.id, it.startedAt, json.encodeToString(it)) })
        mutableHistory.value = dao.runs().map { json.decodeFromString<BenchmarkRun>(it.payload) }
        publishBestEffort(mutableHistory.value)
        scheduleMirror()
    }

    private fun scheduleMirror() {
        // Do not overwrite the damaged source when its independent recovery copy failed.
        if (mirrorRecoveryBlocked) return
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            delay(750)
            mutex.withLock {
                writingMirror = true
                try {
                    if (!preferences.edit().putString("history", json.encodeToString(mutableHistory.value)).putLong("snapshot_generation", mutableSnapshot.value?.generation ?: 0).commit()) {
                        loadWarning = "Results are saved. The optional backup mirror could not be updated."
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    loadWarning = "Results are saved. The optional backup mirror could not be updated."
                } finally {
                    writingMirror = false
                }
            }
        }
    }

    private companion object {
        const val MAX_BACKUP_RUNS = 10_000
    }
}
