package dev.chungjungsoo.gptmobile.data.delegation

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded, content-free activity. Temporary responses never reach disk or export. */
@Singleton
class DelegationActivityStore @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.noBackupFilesDir, "delegation-activity-v1")
    private val prefs = context.getSharedPreferences("delegation-activity", Context.MODE_PRIVATE)
    private val persistent = ConcurrentHashMap.newKeySet<String>()
    private val deletedChats = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Mutex()
    private val written = mutableMapOf<String, DelegationActivity>()
    private val mutable = MutableStateFlow<Map<String, DelegationActivity>>(emptyMap())
    val runs = mutable.asStateFlow()
    private val enabledState = MutableStateFlow(prefs.getBoolean("enabled", true))
    val enabled = enabledState.asStateFlow()
    private val errorState = MutableStateFlow(false)
    val unavailable = errorState.asStateFlow()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            safely {
                lock.withLock {
                    val cutoff = System.currentTimeMillis() - RETENTION_MS
                    val loaded = directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.take(200).mapNotNull { file ->
                        if (file.length() > 262144) return@mapNotNull null
                        runCatching { Json.decodeFromString<DelegationActivity>(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) }.getOrNull()
                            ?.takeIf { it.schema == 1 && it.startedAt >= cutoff && it.events.size <= 200 && it.chatId !in deletedChats }
                            ?.let { if (it.status == DelegationStepStatus.RUNNING) it.append(DelegationEvent(DelegationEventKind.RUN_TERMINAL, status = DelegationStepStatus.INTERRUPTED)) else it }
                    }
                    persistent.addAll(loaded.map { it.runId })
                    mutable.update { loaded.associateBy { it.runId } + it }
                }
                flush()
            }
            for (ignored in writes) {
                delay(250)
                safely { flush() }
            }
        }
    }

    fun start(runId: String, chatId: Int, policyRevision: String, primaryUid: String, retain: Boolean) {
        if (!enabled.value || chatId in deletedChats) return
        val run = DelegationActivity(runId = runId, chatId = chatId, policyRevision = policyRevision, primaryUid = primaryUid)
            .append(DelegationEvent(DelegationEventKind.POLICY_RESOLVED, role = DelegationRole.PRIMARY, profileUid = primaryUid))
        mutable.update { old -> (old + (runId to run)).values.sortedByDescending { it.startedAt }.take(200).associateBy { it.runId } }
        if (retain) persistent.add(runId)
        writes.trySend(Unit)
    }

    fun record(runId: String, event: DelegationEvent) {
        if (!enabled.value) return
        mutable.update { old ->
            val run = old[runId]?.takeIf { it.chatId !in deletedChats } ?: return@update old
            if (event.kind == DelegationEventKind.RUN_TERMINAL && runId !in persistent) old - runId else old + (runId to run.append(event))
        }
        writes.trySend(Unit)
    }

    suspend fun setEnabled(value: Boolean) = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean("enabled", value).commit())
        enabledState.value = value
        if (!value) {
            mutable.update { values -> values.mapValues { (_, run) -> if (run.status == DelegationStepStatus.RUNNING) run.append(DelegationEvent(DelegationEventKind.RUN_TERMINAL, status = DelegationStepStatus.INTERRUPTED)) else run } }
            writes.trySend(Unit)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            mutable.value = emptyMap()
            persistent.clear()
            written.clear()
            directory.listFiles().orEmpty().forEach { check(it.delete()) }
        }
    }

    suspend fun deleteChat(chatId: Int) = withContext(Dispatchers.IO) {
        deletedChats.add(chatId)
        mutable.update { runs -> runs.filterValues { it.chatId != chatId } }
        safely { flush() }
    }

    fun export(): String = Json.encodeToString(mutable.value.values.filter { it.runId in persistent && it.chatId !in deletedChats }.sortedBy { it.startedAt })

    private suspend fun flush() = lock.withLock {
        check(directory.mkdirs() || directory.isDirectory)
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        mutable.update { values -> values.filterValues { it.startedAt >= cutoff && it.chatId !in deletedChats } }
        val retained = mutable.value.values.filter { it.runId in persistent }
        val files = retained.associateBy { UUID.nameUUIDFromBytes(it.runId.toByteArray()).toString() + ".json" }
        directory.listFiles().orEmpty().filter { it.extension == "json" && it.name !in files }.forEach { check(it.delete()) }
        written.keys.retainAll(retained.map { it.runId }.toSet())
        persistent.retainAll(mutable.value.keys)
        retained.forEach { run ->
            if (written[run.runId] == run) return@forEach
            val bytes = Json.encodeToString(run).toByteArray()
            check(bytes.size <= 262144)
            val atomic = AtomicFile(File(directory, UUID.nameUUIDFromBytes(run.runId.toByteArray()).toString() + ".json"))
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
                written[run.runId] = run
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            errorState.value = true
        }
    }

    companion object {
        private const val RETENTION_MS = 30L * 86400000
    }
}
