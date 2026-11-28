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
enum class GitHubDecisionMode { ASK, SUGGESTED, AUTO_APPROVE }

@Serializable
data class GitHubDecisionPreference(val conversationIdentity: String, val workspace: String, val mode: GitHubDecisionMode)

internal fun dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.githubDecisionMode(chatId: Int, context: GitHubConversationContext): GitHubDecisionMode =
    githubDecisionPreferences[chatId]?.takeIf {
        it.conversationIdentity.isNotBlank() &&
            it.conversationIdentity == conversationIdentities[chatId] &&
            it.workspace == GitHubChatDecisions.workspaceKey(context) &&
            conversationGitHub[chatId]?.sameWorkspace(context) == true
    }?.mode ?: GitHubDecisionMode.ASK

@Serializable
data class GitHubDecisionOption(val label: String, val detail: String = "", val enterValue: Boolean = false)

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
    val cancelled: Boolean = false,
    val automatic: Boolean = false,
    val offeredAtMillis: Long? = null,
    val answeredAtMillis: Long? = null,
    val conversationIdentity: String = "",
    val runId: String? = null,
    val expectedHeadSha: String? = null,
    val selectedOption: Int? = null
) {
    init {
        require(chatId > 0 && sourceMessageId > 0 && profileUid.isNotBlank() && workspace.isNotBlank())
        require(question.isNotBlank() && question.length <= 400 && context.length <= 1200)
        require(options.size in 2..4 && options.map { it.label }.distinct().size == options.size)
        require(options.all { it.label.isNotBlank() && it.label.length <= 80 && it.detail.length <= 240 })
        require(recommended == null || recommended in options.indices)
        require(answer == null || answer.isNotBlank() && answer.length <= 2000)
        require(selectedOption == null || selectedOption in options.indices)
        require(expectedHeadSha == null || expectedHeadSha.matches(Regex("[a-fA-F0-9]{40}")))
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
        val current = store.read(SCOPE, KEY)
        val saved = current ?: store.read(SCOPE, LEGACY_KEY)
        val restored = saved?.let { json.decodeFromJsonElement(Snapshot.serializer(), it).decisions }.orEmpty()
        // Reloading after a failed disk acknowledgment must not erase live
        // temporary-chat inputs; those intentionally have no on-disk copy.
        mutable.value = restored + mutable.value.filter { !it.persistent && restored.none { savedDecision -> savedDecision.id == it.id } }
        if (current == null && saved != null) {
            // Install in the non-evictable input tier before removing the legacy cache entry.
            store.write(SCOPE, KEY, saved)
            store.remove(SCOPE, LEGACY_KEY)
        }
        loaded = true
    }

    private suspend fun save(items: List<GitHubChatDecision>) {
        withContext(NonCancellable) {
            try {
                store.write(SCOPE, KEY, json.encodeToJsonElement(Snapshot(items.filter { it.persistent })).jsonObject)
                mutable.value = items
            } catch (failure: Exception) {
                // An interrupted filesystem acknowledgment may follow a successful
                // rename. Reload authoritative input before accepting another answer.
                loaded = false
                throw failure
            }
        }
    }

    suspend fun request(proposal: GitHubChatDecision, useSuggestion: Boolean = false, waitForAnswer: Boolean = true, offered: suspend (GitHubChatDecision) -> Unit = {}): GitHubChatDecision {
        var created = false
        val current = mutex.withLock {
            loadLocked()
            val existing = mutable.value.firstOrNull { it.id == proposal.id }
            require(existing == null || existing.copy(answer = null, delivered = false, cancelled = false, automatic = false, persistent = proposal.persistent, offeredAtMillis = proposal.offeredAtMillis, answeredAtMillis = proposal.answeredAtMillis, runId = proposal.runId, expectedHeadSha = proposal.expectedHeadSha, selectedOption = proposal.selectedOption) == proposal) { "Decision identity changed." }
            // A resumed task may own the same question under a new run. Rebind
            // unanswered choices before issuing replacement notification actions.
            val rebound = existing?.takeIf { it.answer == null && it.runId != proposal.runId }?.copy(runId = proposal.runId)
            if (rebound != null) {
                save(mutable.value.map { if (it.id == rebound.id) rebound else it })
                created = true
            }
            val decision = rebound ?: existing ?: proposal.let {
                val label = if (useSuggestion) it.recommended?.let(it.options::get)?.takeUnless(GitHubDecisionOption::enterValue)?.label else null
                val at = System.currentTimeMillis()
                if (label == null) it.copy(offeredAtMillis = at) else it.copy(answer = label, automatic = true, offeredAtMillis = at, answeredAtMillis = at, selectedOption = it.recommended)
            }.also {
                check(mutable.value.count { !it.delivered } < MAX_PENDING) { "Answer or dismiss an existing GitHub decision before adding another." }
                val retained = mutable.value.filter { !it.delivered } + mutable.value.filter { it.delivered }.takeLast(MAX_HISTORY)
                save(retained + it)
                created = true
            }
            if (waitForAnswer) {
                waiters[decision.id] = (waiters[decision.id] ?: 0) + 1
                waitingMutable.value = waiters.keys.toSet()
            }
            decision
        }
        return try {
            if (created && current.answer == null) offered(current)
            // The agent path parks the durable checkpoint immediately. Waiting
            // for days must not occupy its worker, network request or model budget.
            if (!waitForAnswer) return current
            // An opted-in suggestion is durable before the tool receives it.
            // Other questions remain available after an interruption.
            val items = mutable.first { items -> items.none { it.id == current.id } || items.any { it.id == current.id && it.answer != null } }
            items.firstOrNull { it.id == current.id } ?: throw CancellationException("The conversation was deleted.")
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (waitForAnswer) {
                        val count = (waiters[current.id] ?: 1) - 1
                        if (count <= 0) waiters.remove(current.id) else waiters[current.id] = count
                        waitingMutable.value = waiters.keys.toSet()
                    }
                }
            }
        }
    }

    suspend fun respond(id: String, chatId: Int, workspace: String, option: Int? = null, customAnswer: String? = null): GitHubDecisionResponse = mutex.withLock {
        loadLocked()
        val request = mutable.value.firstOrNull { it.id == id && it.chatId == chatId && it.workspace == workspace && !it.delivered && !it.cancelled } ?: return@withLock GitHubDecisionResponse(false, false)
        respondLocked(request, option, customAnswer)
    }

    /** A notification only supplies an answer to its exact retained question. */
    internal suspend fun respondNotification(
        id: String,
        binding: String,
        option: Int,
        ownsQuestion: suspend (GitHubChatDecision) -> Boolean
    ): GitHubDecisionResponse = mutex.withLock {
        loadLocked()
        val request = mutable.value.firstOrNull { it.id == id && !it.delivered && !it.cancelled }
            ?: return@withLock GitHubDecisionResponse(false, false)
        if (request.notificationBinding() != binding || !ownsQuestion(request)) return@withLock GitHubDecisionResponse(false, false)
        if (request.options.getOrNull(option)?.enterValue != false) return@withLock GitHubDecisionResponse(false, false)
        respondLocked(request, option, null)
    }

    private suspend fun respondLocked(request: GitHubChatDecision, option: Int?, customAnswer: String?): GitHubDecisionResponse {
        val id = request.id
        if (request.answer != null) return GitHubDecisionResponse(false, id in waiters)
        require((option != null) != (customAnswer != null)) { "Choose one option or provide a custom answer." }
        val selected = option?.let { requireNotNull(request.options.getOrNull(it)) { "Unknown decision option." } }
        require(selected?.enterValue != true) { "Enter a commit, tag or branch." }
        val answer = selected?.label ?: customAnswer.orEmpty().trim()
        if (customAnswer != null && request.options.any(GitHubDecisionOption::enterValue)) {
            require(GitHubReviewComparison.acceptsBaselineInput(answer)) { "Enter one commit, tag or branch." }
        }
        require(answer.isNotBlank() && answer.length <= 2000)
        save(mutable.value.map { if (it.id == id) it.copy(answer = answer, answeredAtMillis = System.currentTimeMillis(), selectedOption = option) else it })
        return GitHubDecisionResponse(true, id in waiters)
    }

    suspend fun delivered(id: String) = mutex.withLock {
        loadLocked()
        save(mutable.value.map { if (it.id == id && it.answer != null) it.copy(delivered = true) else it })
    }

    suspend fun dismiss(id: String, chatId: Int) = mutex.withLock {
        loadLocked()
        // A live run must receive an explicit stop instruction, never a vanished form.
        save(mutable.value.map { if (it.id == id && it.chatId == chatId && !it.delivered) it.copy(answer = "Stop this task. Do not implement the proposed choice.", delivered = it.id !in waiters, cancelled = true, answeredAtMillis = System.currentTimeMillis()) else it })
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

    /** Imported chat IDs cannot inherit questions offered to this installation's old chats. */
    suspend fun resetAfterRestore(): List<String> = mutex.withLock {
        loadLocked()
        val removed = mutable.value.map { it.id }
        store.remove(SCOPE, KEY)
        store.remove(SCOPE, LEGACY_KEY)
        mutable.value = emptyList()
        loaded = true
        removed
    }

    companion object {
        private const val SCOPE = "github-chat-decisions"
        private const val KEY = "user-input:decisions-v2"
        private const val LEGACY_KEY = "decisions-v1"
        const val MAX_PENDING = 16
        const val MAX_HISTORY = 48
        fun workspaceKey(context: GitHubConversationContext): String = GitHubRepositoryStateStore.digest(listOf(context.connectionUid, context.owner.lowercase(java.util.Locale.ROOT), context.repo.lowercase(java.util.Locale.ROOT), context.ref, context.mainProfileUid).joinToString("\u0000"))
    }
}
