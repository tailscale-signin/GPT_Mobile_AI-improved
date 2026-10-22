package dev.chungjungsoo.gptmobile.data.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small durable admission journal. A crashed or unknown attempt keeps its reservation on recovery. */
@Singleton
class CombinedTurnBudgetStore private constructor(private val directory: File?) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "combined_turn_budgets")) {
        installed = this
    }

    private val shared = states.computeIfAbsent(directory?.absolutePath ?: "memory:${UUID.randomUUID()}") { StoreState() }
    private val mutex = shared.mutex
    private val budgets = shared.budgets
    val progress = shared.progress.asStateFlow()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun open(context: CombinedExecutionContext): CombinedTurnBudget = mutex.withLock {
        require(context.rootBudgetId.isNotBlank())
        check(context.rootBudgetId !in shared.deletedRoots) { "This Combined turn belongs to a deleted conversation." }
        val plan = requireNotNull(context.plan) { "A Combined work plan must be persisted before dispatch." }
        require(plan.requested == context.requested)
        require(plan.effectivePlanDigest == combinedDigest(Json.encodeToString(plan.copy(effectivePlanDigest = "")))) { "Effective Combined plan changed after resolution." }
        val existing = budgets[context.rootBudgetId]
        if (existing != null) {
            val snapshot = existing.snapshot()
            require(snapshot.requestedPolicyDigest == context.requested.requestedPolicyDigest && snapshot.effectivePlanDigest == plan.effectivePlanDigest)
            existing.rebindConversation(context.chatId)
            return@withLock existing
        }
        val file = if (context.persistBudget) directory?.let { File(it, "${combinedDigest(context.rootBudgetId)}.json") } else null
        val loaded = withContext(Dispatchers.IO) { file?.takeIf(File::exists)?.let { read(it) } }
        val snapshot = loaded ?: CombinedBudgetSnapshot(
            rootBudgetId = context.rootBudgetId,
            chatId = context.chatId,
            requestedPolicyDigest = context.requested.requestedPolicyDigest,
            effectivePlanDigest = plan.effectivePlanDigest,
            plan = plan
        )
        require(snapshot.version == 1 && snapshot.rootBudgetId == context.rootBudgetId && snapshot.requestedPolicyDigest == context.requested.requestedPolicyDigest && snapshot.effectivePlanDigest == plan.effectivePlanDigest) {
            "Combined budget journal is incompatible; no new dispatch is authorized."
        }
        val budget = CombinedTurnBudget(snapshot, persist = { next ->
            if (context.rootBudgetId !in shared.deletedRoots) {
                if (file != null) write(file, next)
                shared.progress.update { it + (context.rootBudgetId to next.progressSnapshot()) }
            }
        })
        if (loaded == null && file != null) write(file, snapshot)
        budget.rebindConversation(context.chatId)
        budgets[context.rootBudgetId] = budget
        shared.progress.update { it + (context.rootBudgetId to snapshot.progressSnapshot()) }
        budget
    }

    suspend fun snapshot(rootBudgetId: String): CombinedBudgetSnapshot? = mutex.withLock {
        budgets[rootBudgetId]?.snapshot() ?: withContext(Dispatchers.IO) { directory?.let { File(it, "${combinedDigest(rootBudgetId)}.json") }?.takeIf(File::exists)?.let { read(it) } }
    }

    /** Restore UI milestones without opening a budget or granting new dispatch authority. */
    suspend fun loadProgress(chatIds: Set<Int>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            directory?.listFiles { file -> file.extension == "json" }?.forEach { file ->
                val saved = runCatching { read(file) }.getOrNull() ?: return@forEach
                if (saved.chatId in chatIds && saved.rootBudgetId !in shared.deletedRoots && saved.rootBudgetId !in budgets) {
                    shared.progress.update { it + (saved.rootBudgetId to saved.progressSnapshot()) }
                }
            }
        }
    }

    suspend fun cancel(rootBudgetId: String) {
        val snapshot = snapshot(rootBudgetId) ?: return
        open(CombinedExecutionContext(snapshot.plan.requested, snapshot.plan, snapshot.rootBudgetId, chatId = snapshot.chatId)).cancel()
    }

    /** Stop first, then remove retained state. An in-flight holder remains cancelled. */
    suspend fun deleteConversation(chatId: Int) = mutex.withLock {
        val active = budgets.filterValues { it.snapshot().chatId == chatId }
        active.values.forEach { it.cancel() }
        shared.deletedRoots.addAll(active.keys)
        active.keys.forEach(budgets::remove)
        shared.progress.update { rows -> rows.filterValues { it.chatId != chatId } }
        withContext(Dispatchers.IO) {
            directory?.listFiles { file -> file.extension == "json" }?.forEach { file ->
                if (read(file).chatId == chatId) check(file.delete()) { "Could not remove Combined budget journal." }
            }
        }
    }

    private suspend fun read(file: File): CombinedBudgetSnapshot = withContext(Dispatchers.IO) {
        require(file.length() <= 32L * 1024 * 1024) { "Combined budget journal exceeds its read bound." }
        json.decodeFromString<CombinedBudgetSnapshot>(file.readText())
    }

    private suspend fun write(file: File, snapshot: CombinedBudgetSnapshot) = withContext(Dispatchers.IO) {
        val encoded = json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        check(encoded.size <= 32L * 1024 * 1024) { "Combined accounting storage capacity reached; retain completed work and do not dispatch additional attempts." }
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Cannot create Combined budget journal directory." }
        val staged = File(file.parentFile, "${file.name}.pending")
        FileOutputStream(staged).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
        try {
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }

    companion object {
        private class StoreState {
            val mutex = Mutex()
            val budgets = linkedMapOf<String, CombinedTurnBudget>()
            val progress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, CombinedProgressSnapshot>>(emptyMap())
            val deletedRoots = ConcurrentHashMap.newKeySet<String>()
        }
        private val states = ConcurrentHashMap<String, StoreState>()

        @Volatile private var installed: CombinedTurnBudgetStore? = null
        fun installedOrNull(): CombinedTurnBudgetStore? = installed
        fun inMemory(): CombinedTurnBudgetStore = CombinedTurnBudgetStore(null)
        fun forDirectory(directory: File): CombinedTurnBudgetStore = CombinedTurnBudgetStore(directory)
    }
}
