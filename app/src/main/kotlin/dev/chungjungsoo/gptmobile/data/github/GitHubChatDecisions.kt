package dev.chungjungsoo.gptmobile.data.github

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
data class GitHubDecisionOption(val label: String, val detail: String = "")

@Serializable
data class GitHubChatDecision(
    val id: String,
    val chatId: Int,
    val sourceMessageId: Int,
    val profileUid: String,
    val workspace: String,
    val question: String,
    val context: String,
    val options: List<GitHubDecisionOption>,
    val recommended: Int? = null,
    val persistent: Boolean = false,
    val answer: String? = null,
    val delivered: Boolean = false,
    val cancelled: Boolean = false
) {
    init {
        require(chatId > 0 && sourceMessageId > 0 && profileUid.isNotBlank() && workspace.isNotBlank())
        require(question.isNotBlank() && question.length <= 400 && context.length <= 1200)
        require(options.size in 2..4 && options.map { it.label }.distinct().size == options.size)
        require(options.all { it.label.isNotBlank() && it.label.length <= 80 && it.detail.length <= 240 })
        require(recommended == null || recommended in options.indices)
        require(answer == null || answer.isNotBlank() && answer.length <= 2000)
    }
}

data class GitHubDecisionResponse(val accepted: Boolean, val waiting: Boolean)

/** User choices are durable input, never GitHub authorization or mutation receipts. */
@Singleton
class GitHubChatDecisions @Inject constructor(private val store: GitHubRepositoryStateStore) {
    @Serializable private data class Snapshot(val decisions: List<GitHubChatDecision> = emptyList())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow<List<GitHubChatDecision>>(emptyList())
    val decisions = mutable.asStateFlow()
    private val waitingMutable = MutableStateFlow<Set<String>>(emptySet())
    val waiting = waitingMutable.asStateFlow()
    private val waiters = mutableMapOf<String, Int>()
    private var loaded = false

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (loaded) return
        val saved = store.read(SCOPE, KEY)
        mutable.value = saved?.let { json.decodeFromJsonElement(Snapshot.serializer(), it).decisions }.orEmpty()
        loaded = true
    }

    private suspend fun save(items: List<GitHubChatDecision>) {
        store.write(SCOPE, KEY, json.encodeToJsonElement(Snapshot(items.filter { it.persistent })).jsonObject)
        mutable.value = items
    }

    suspend fun request(proposal: GitHubChatDecision, offered: suspend (GitHubChatDecision) -> Unit = {}): GitHubChatDecision {
        var created = false
        val current = mutex.withLock {
            loadLocked()
            val existing = mutable.value.firstOrNull { it.id == proposal.id }
            require(existing == null || existing.copy(answer = null, delivered = false, cancelled = false, persistent = proposal.persistent) == proposal) { "Decision identity changed." }
            val decision = existing ?: proposal.also {
                check(mutable.value.count { !it.delivered } < MAX_PENDING) { "Answer or dismiss an existing GitHub decision before adding another." }
                val retained = mutable.value.filter { !it.delivered } + mutable.value.filter { it.delivered }.takeLast(MAX_HISTORY)
                save(retained + it)
                created = true
            }
            waiters[decision.id] = (waiters[decision.id] ?: 0) + 1
            waitingMutable.value = waiters.keys.toSet()
            decision
        }
        return try {
            if (created) offered(current)
            // Waiting has no default answer or wall-clock auto-approval. Cancellation
            // leaves the question available for an explicit resume after interruption.
            val items = mutable.first { items -> items.none { it.id == current.id } || items.any { it.id == current.id && it.answer != null } }
            items.firstOrNull { it.id == current.id } ?: throw CancellationException("The conversation was deleted.")
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val count = (waiters[current.id] ?: 1) - 1
                    if (count <= 0) waiters.remove(current.id) else waiters[current.id] = count
                    waitingMutable.value = waiters.keys.toSet()
                }
            }
        }
    }

    suspend fun respond(id: String, chatId: Int, workspace: String, option: Int? = null, customAnswer: String? = null): GitHubDecisionResponse = mutex.withLock {
        loadLocked()
        val request = mutable.value.firstOrNull { it.id == id && it.chatId == chatId && it.workspace == workspace && !it.delivered } ?: return@withLock GitHubDecisionResponse(false, false)
        if (request.answer != null) return@withLock GitHubDecisionResponse(false, id in waiters)
        require((option != null) != (customAnswer != null)) { "Choose one option or provide a custom answer." }
        val answer = option?.let { request.options.getOrNull(it)?.label ?: error("Unknown decision option.") } ?: customAnswer.orEmpty().trim()
        require(answer.isNotBlank() && answer.length <= 2000)
        save(mutable.value.map { if (it.id == id) it.copy(answer = answer) else it })
        GitHubDecisionResponse(true, id in waiters)
    }

    suspend fun delivered(id: String) = mutex.withLock {
        loadLocked()
        save(mutable.value.map { if (it.id == id && it.answer != null) it.copy(delivered = true) else it })
    }

    suspend fun dismiss(id: String, chatId: Int) = mutex.withLock {
        loadLocked()
        // A live run must receive an explicit stop instruction, never a vanished form.
        save(mutable.value.map { if (it.id == id && it.chatId == chatId && !it.delivered) it.copy(answer = "Stop this task. Do not implement the proposed choice.", delivered = it.id !in waiters, cancelled = true) else it })
    }

    suspend fun setPersistent(chatId: Int, persistent: Boolean) = mutex.withLock {
        loadLocked()
        save(mutable.value.map { if (it.chatId == chatId) it.copy(persistent = persistent) else it })
    }

    suspend fun deleteChat(chatId: Int): List<String> = mutex.withLock {
        loadLocked()
        val removed = mutable.value.filter { it.chatId == chatId }.map { it.id }
        save(mutable.value.filterNot { it.chatId == chatId })
        removed
    }

    companion object {
        private const val SCOPE = "github-chat-decisions"
        private const val KEY = "decisions-v1"
        const val MAX_PENDING = 16
        const val MAX_HISTORY = 48
        fun workspaceKey(context: GitHubConversationContext): String = GitHubRepositoryStateStore.digest(listOf(context.connectionUid, context.owner.lowercase(java.util.Locale.ROOT), context.repo.lowercase(java.util.Locale.ROOT), context.ref, context.mainProfileUid).joinToString("\u0000"))
    }
}
