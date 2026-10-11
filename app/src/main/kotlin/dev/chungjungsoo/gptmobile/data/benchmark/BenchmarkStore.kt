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

@Serializable
internal data class BenchmarkBackupPayload(
    val schemaVersion: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val runs: List<BenchmarkRun> = emptyList(),
    val scoreTable: BenchmarkScoreSnapshot? = null,
    val runtimeTrials: List<RuntimeTrial> = emptyList(),
    val runtimeQualifications: List<RuntimeQualificationResult> = emptyList(),
    val runtimeDecisions: List<RuntimePlanDecisionRecord> = emptyList()
)

@Singleton
class BenchmarkStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(context.applicationContext, BenchmarkDatabase::class.java, "benchmark_v2.db").addMigrations(BenchmarkDatabase.MIGRATION_1_2).build()
    private val dao = database.dao()
    private val mutableRuntimeTrials = MutableStateFlow<List<RuntimeTrial>>(emptyList())
    val runtimeTrials = mutableRuntimeTrials.asStateFlow()
    private val mutableQualifications = MutableStateFlow<List<RuntimeQualificationResult>>(emptyList())
    val runtimeQualifications = mutableQualifications.asStateFlow()
    private val mutableSnapshot = MutableStateFlow<BenchmarkScoreSnapshot?>(null)
    internal val snapshot = mutableSnapshot.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var activeRevisions: Map<String, String>? = preferences.getString("active_revisions", null)?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
    private val mutableHistory = MutableStateFlow<List<BenchmarkRun>>(emptyList())
    val history = mutableHistory.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    var loadWarning: String? = null
        private set

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        scope.launch {
            while (true) {
                delay(60_000)
                mutex.withLock {
                    if (loaded) {
                        val current = mutableSnapshot.value
                        val recalculated = withContext(Dispatchers.Default) { DynamicScoreEngine.snapshot(mutableHistory.value, (current?.generation ?: 0) + 1, System.currentTimeMillis(), activeRevisions) }
                        if (recalculated.rows != current?.rows) publish(mutableHistory.value)
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
        val saved = preferences.getString("history", null)
        val recovered = saved?.let { raw ->
            val entries = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            val valid = entries.orEmpty().mapNotNull { entry -> runCatching { json.decodeFromJsonElement<BenchmarkRun>(entry) }.getOrNull() }
            if (entries == null || valid.size != entries.size) {
                // Keep the original for recovery instead of letting a later save destroy it.
                check(preferences.edit().putString("recovery_history", raw).commit()) { "Could not preserve damaged benchmark history." }
                loadWarning = "Some saved benchmark records could not be read. Valid records were recovered; new benchmarks are available."
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Benchmark", "Recovered benchmark history with invalid records", "W")
            }
            valid
        }.orEmpty()
        val records = dao.runs()
        val stored = records.mapNotNull { runCatching { json.decodeFromString<BenchmarkRun>(it.payload) }.getOrNull() }
        val existing = dao.current()
        // Room is authoritative after a crash between the transaction and backup mirror write.
        val mirrorGeneration = preferences.getLong("snapshot_generation", 0)
        val source = if (restoreRequested || existing == null || (saved != null && mirrorGeneration >= existing.generation)) recovered else stored
        restoreRequested = false
        mutableSnapshot.value = existing?.let { runCatching { json.decodeFromString<BenchmarkScoreSnapshot>(it.payload) }.getOrNull() }
        if (source != stored || existing == null || DynamicScoreEngine.snapshot(source, 0, System.currentTimeMillis(), activeRevisions).rows != mutableSnapshot.value?.rows) publish(source)
        mutableHistory.value = source
        mutableRuntimeTrials.value = dao.runtimeTrials().map { json.decodeFromString<RuntimeTrial>(it.payload) }
        mutableQualifications.value = dao.runtimeQualifications().map { json.decodeFromString<RuntimeQualificationResult>(it.payload) }
        loaded = true
    }

    internal suspend fun updateEnrollment(revisions: Map<String, String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (revisions == activeRevisions) return@withLock
            activeRevisions = revisions.toMap()
            check(preferences.edit().putString("active_revisions", json.encodeToString(revisions)).commit()) { "Could not save benchmark enrollment." }
            loadLocked()
            publish(mutableHistory.value)
        }
    }

    suspend fun save(run: BenchmarkRun) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            persist((listOf(run) + mutableHistory.value.filterNot { it.id == run.id }))
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            persist(mutableHistory.value.filterNot { it.id == id })
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
                        canceled = true,
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

    private suspend fun publish(runs: List<BenchmarkRun>) {
        val previous = dao.current()?.generation ?: 0L
        val next = DynamicScoreEngine.snapshot(runs, previous + 1, System.currentTimeMillis(), activeRevisions)
        dao.publish(runs.map { BenchmarkRunRecord(it.id, it.startedAt, json.encodeToString(it)) }, BenchmarkSnapshotRecord(next.generation, next.createdAt, json.encodeToString(next)))
        mutableSnapshot.value = next
    }

    fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        scope.cancel()
        database.close()
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            json.encodeToString(
                BenchmarkBackupPayload(
                    runs = mutableHistory.value,
                    scoreTable = mutableSnapshot.value,
                    runtimeTrials = mutableRuntimeTrials.value,
                    runtimeQualifications = mutableQualifications.value,
                    runtimeDecisions = dao.runtimeDecisions().map { json.decodeFromString<RuntimePlanDecisionRecord>(it.payload) }
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
                if (run.finished) run else run.copy(finished = true, canceled = true, stoppedReason = run.stoppedReason ?: "Benchmark was interrupted at backup time.")
            }.sortedByDescending { it.startedAt }
            val bundle = runCatching { json.decodeFromString<BenchmarkBackupPayload>(payload) }.getOrNull()
            val trials = bundle?.runtimeTrials.orEmpty().map { it.copy(historical = true, finished = true, completed = it.finished && it.completed, failure = if (it.finished) it.failure else "Benchmark interrupted at backup time") }
            val qualifications = bundle?.runtimeQualifications.orEmpty().map { it.copy(status = QualificationStatus.HISTORICAL, actualBackendConfirmed = false, reason = "Restored evidence requires local verification") }
            val next = DynamicScoreEngine.snapshot(restoredRuns, (dao.current()?.generation ?: 0) + 1, System.currentTimeMillis(), activeRevisions)
            dao.restoreAll(
                restoredRuns.map { BenchmarkRunRecord(it.id, it.startedAt, json.encodeToString(it)) },
                BenchmarkSnapshotRecord(next.generation, next.createdAt, json.encodeToString(next)),
                trials.map { RuntimeTrialRecord(it.id, it.timestamp, json.encodeToString(it)) },
                qualifications.map { RuntimeQualificationRecord(it.plan.id, json.encodeToString(it)) },
                bundle?.runtimeDecisions.orEmpty().take(500).map { RuntimeDecisionRecord(it.id, it.timestamp, json.encodeToString(it.copy(historical = true))) }
            )
            mutableRuntimeTrials.value = trials
            mutableQualifications.value = qualifications
            mutableHistory.value = restoredRuns
            mutableSnapshot.value = next
            writingMirror = true
            try {
                check(preferences.edit().putString("history", json.encodeToString(restoredRuns)).putLong("snapshot_generation", next.generation).commit()) { "Restored in Room; backup mirror update failed" }
            } finally {
                writingMirror = false
            }
        }
    }

    fun validateBackupJson(payload: String) {
        decodeBackupRuns(payload)
    }

    private fun decodeBackupRuns(payload: String): List<BenchmarkRun> {
        val decoded = json.parseToJsonElement(payload)
        val bundle = runCatching { json.decodeFromJsonElement<BenchmarkBackupPayload>(decoded) }.getOrNull()
        if (bundle != null) require(bundle.schemaVersion in 1..2) { "This benchmark backup requires a newer app version." }
        val runs = bundle?.runs ?: json.decodeFromJsonElement<List<BenchmarkRun>>(decoded)
        require(runs.size <= MAX_BACKUP_RUNS) { "Benchmark backup contains too many runs." }
        require(runs.all { it.id.isNotBlank() && it.profileUid.isNotBlank() }) { "Benchmark backup contains invalid records." }
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
        publish(runs)
        writingMirror = true
        try {
            check(preferences.edit().putString("history", json.encodeToString(runs)).putLong("snapshot_generation", mutableSnapshot.value?.generation ?: 0).commit()) { "Benchmark saved in Room, but backup mirror could not be updated. Check available storage." }
        } finally {
            writingMirror = false
        }
        mutableHistory.value = runs
    }

    private companion object {
        const val MAX_BACKUP_RUNS = 10_000
    }
}
