package dev.chungjungsoo.gptmobile.data.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CombinedCollectionDecision(val rootBudgetId: String, val acknowledgedAttempts: Set<String> = emptySet(), val updatedAt: Long = 0, val chosenWriterUid: String? = null)

/** Durable user decisions; the coordinator chat gate serializes acknowledgement, retry and freeze. */
@Singleton
class CombinedCollectionDecisionStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<Map<String, CombinedCollectionDecision>>(emptyMap())
    val decisions = mutable.asStateFlow()
    private var loaded = false
    private val persistentRoots = mutableSetOf<String>()
    private val deletedRoots = mutableSetOf<String>()
    private val temporaryRoots = mutableSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load() = lock.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        val saved = withContext(Dispatchers.IO) { context.getSharedPreferences("combined_collection_decisions", Context.MODE_PRIVATE).getString("decisions", null) }
        mutable.value = saved?.let { runCatching { json.decodeFromString<Map<String, CombinedCollectionDecision>>(it) }.getOrNull() }.orEmpty()
        persistentRoots.addAll(mutable.value.keys)
        loaded = true
    }

    fun acknowledged(rootBudgetId: String, attemptId: String?): Boolean = attemptId != null && attemptId in mutable.value[rootBudgetId]?.acknowledgedAttempts.orEmpty()

    suspend fun pending(rootBudgetId: String, attemptIds: Set<String>): Set<String> = lock.withLock {
        loadLocked()
        attemptIds - mutable.value[rootBudgetId]?.acknowledgedAttempts.orEmpty()
    }

    suspend fun chosenWriter(rootBudgetId: String): String? = lock.withLock {
        loadLocked()
        mutable.value[rootBudgetId]?.chosenWriterUid
    }

    suspend fun chooseWriter(rootBudgetId: String, profileUid: String, persistent: Boolean = true) = lock.withLock {
        require(profileUid.isNotBlank())
        loadLocked()
        val previous = mutable.value[rootBudgetId] ?: CombinedCollectionDecision(rootBudgetId)
        if (previous.chosenWriterUid == profileUid) return@withLock
        persist(previous.copy(chosenWriterUid = profileUid, updatedAt = System.currentTimeMillis()), persistent)
    }

    suspend fun acknowledge(rootBudgetId: String, attemptIds: Set<String>, persistent: Boolean = true) = lock.withLock {
        loadLocked()
        val previous = mutable.value[rootBudgetId]
        if (previous != null && previous.acknowledgedAttempts.containsAll(attemptIds)) return@withLock
        val decision = (previous ?: CombinedCollectionDecision(rootBudgetId)).copy(acknowledgedAttempts = previous?.acknowledgedAttempts.orEmpty() + attemptIds, updatedAt = System.currentTimeMillis())
        persist(decision, persistent)
    }

    suspend fun deleteRoots(rootBudgetIds: Set<String>) = lock.withLock {
        loadLocked()
        deletedRoots.addAll(rootBudgetIds)
        val next = mutable.value - rootBudgetIds
        if (rootBudgetIds.any { it in persistentRoots }) write(next.filterKeys { it in persistentRoots && it !in rootBudgetIds })
        persistentRoots.removeAll(rootBudgetIds)
        mutable.value = next
    }

    suspend fun setPersistent(rootBudgetIds: Set<String>, persistent: Boolean) = lock.withLock {
        loadLocked()
        if (persistent) {
            temporaryRoots.removeAll(rootBudgetIds)
            persistentRoots.addAll(rootBudgetIds.filter { it in mutable.value && it !in deletedRoots })
        } else {
            temporaryRoots.addAll(rootBudgetIds)
            persistentRoots.removeAll(rootBudgetIds)
        }
        write(mutable.value.filterKeys { it in persistentRoots })
    }

    private suspend fun persist(decision: CombinedCollectionDecision, persistent: Boolean) {
        check(decision.rootBudgetId !in deletedRoots) { "This Combined turn belongs to a deleted conversation." }
        val next = mutable.value + (decision.rootBudgetId to decision)
        if (persistent && decision.rootBudgetId !in temporaryRoots) {
            write(next.filterKeys { it in persistentRoots || it == decision.rootBudgetId })
            persistentRoots.add(decision.rootBudgetId)
        }
        mutable.value = next
    }

    private suspend fun write(value: Map<String, CombinedCollectionDecision>) {
        val encoded = json.encodeToString(value)
        withContext(Dispatchers.IO) {
            check(context.getSharedPreferences("combined_collection_decisions", Context.MODE_PRIVATE).edit().putString("decisions", encoded).commit()) { "The Combined decision could not be saved." }
        }
    }
}
