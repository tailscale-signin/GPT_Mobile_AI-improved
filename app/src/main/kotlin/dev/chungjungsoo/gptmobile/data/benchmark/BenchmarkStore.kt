package dev.chungjungsoo.gptmobile.data.benchmark

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

@Singleton
class BenchmarkStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("profile_benchmarks_v1", Context.MODE_PRIVATE)
    private val database = Room.databaseBuilder(context.applicationContext, BenchmarkDatabase::class.java, "benchmark_v2.db").build()
    private val dao = database.dao()
    private val mutableSnapshot = MutableStateFlow<BenchmarkScoreSnapshot?>(null)
    internal val snapshot = mutableSnapshot.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
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
                        val recalculated = DynamicScoreEngine.snapshot(mutableHistory.value, (current?.generation ?: 0) + 1, System.currentTimeMillis())
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
        if (source != stored || existing == null) publish(source)
        mutableHistory.value = source
        loaded = true
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

    private suspend fun publish(runs: List<BenchmarkRun>) {
        val previous = dao.current()?.generation ?: 0L
        val next = DynamicScoreEngine.snapshot(runs, previous + 1, System.currentTimeMillis())
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
            json.encodeToString(mutableHistory.value)
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
}
