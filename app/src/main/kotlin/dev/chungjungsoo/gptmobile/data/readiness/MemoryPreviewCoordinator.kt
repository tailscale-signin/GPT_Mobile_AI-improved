package dev.chungjungsoo.gptmobile.data.readiness

import dev.chungjungsoo.gptmobile.data.rag.FactRecall
import dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Exact ephemeral identity, no text/hash logging or persistence. All facts retain their original authority. */
@Singleton
class MemoryPreviewCoordinator @Inject constructor(
    private val vault: FactVaultRepository,
    @dev.chungjungsoo.gptmobile.di.ReadinessScope private val scope: CoroutineScope
) {
    private data class Key(val chat: Int, val profile: String, val text: String, val local: Boolean, val localOnly: Boolean, val history: String)
    private data class Preview(val key: Key, val revision: Long, val snapshot: dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot, val boundary: dev.chungjungsoo.gptmobile.data.knowledge.MemoryScope, val recall: FactRecall, val semanticAttempted: Boolean = false)
    internal class VoiceSearch(val chat: Int, val profile: String)
    private val voiceOwners = mutableMapOf<Pair<Int, String>, VoiceSearch>()
    private val voicePreviews = mutableMapOf<VoiceSearch, LinkedHashMap<Key, Preview>>()

    @Synchronized
    internal fun openVoice(chat: Int, profile: String): VoiceSearch {
        val owner = VoiceSearch(chat, profile)
        voiceOwners.put(chat to profile, owner)?.let { voicePreviews.remove(it) }
        voicePreviews[owner] = linkedMapOf()
        return owner
    }

    @Synchronized
    internal fun closeVoice(owner: VoiceSearch) {
        voiceOwners.remove(owner.chat to owner.profile, owner)
        voicePreviews.remove(owner)
    }

    internal suspend fun previewVoice(owner: VoiceSearch, text: String, local: Boolean, localOnly: Boolean, history: String) {
        if (text.length !in 3..8000 || sensitiveDraft.containsMatchIn(text)) return
        val key = Key(owner.chat, owner.profile, text, local, localOnly, history)
        val epoch = ReadinessInvalidation.epoch
        vault.ensureLoadedReadOnly()
        val snapshot = vault.state.value
        if (!snapshot.enabled || !snapshot.settings.recallEnabled || !local && !snapshot.settings.allowCloudRecall) return
        val boundary = vault.scopeForChat(owner.chat)
        if (boundary.isTemporary) return
        val done = synchronized(this) { voicePreviews[owner]?.get(key) }
        if (done?.snapshot == snapshot && done.boundary == boundary && done.revision == epoch && (done.semanticAttempted || !snapshot.settings.semanticRecall)) return
        suspend fun rank(semantic: Boolean) {
            val result = vault.recallReadOnly(text, owner.chat, isLocal = local, includeLocalOnly = localOnly, previousContext = history, useSemantic = semantic)
            if (vault.scopeForChat(owner.chat) != boundary) return
            synchronized(this) {
                val entries = voicePreviews[owner] ?: return
                if (epoch != ReadinessInvalidation.epoch || snapshot != vault.state.value) return
                entries.entries.removeAll { it.value.snapshot != snapshot }
                entries.remove(key)
                entries[key] = Preview(key, epoch, snapshot, boundary, result, semanticAttempted = semantic)
                if (entries.size > 6) entries.remove(entries.keys.first())
            }
        }
        rank(false)
        if (snapshot.settings.semanticRecall && !dev.chungjungsoo.gptmobile.data.localruntime.InferenceAdmission.isExclusiveBusy) rank(true)
    }

    internal suspend fun voiceRecall(chat: Int, profile: String, text: String, messageId: Int, local: Boolean, localOnly: Boolean, history: String, requireSemantic: Boolean = false): FactRecall? {
        val key = Key(chat, profile, text, local, localOnly, history)
        val (owner, ready) = synchronized(this) {
            val owner = voiceOwners[chat to profile] ?: return null
            owner to (voicePreviews[owner]?.get(key) ?: return null)
        }
        if (requireSemantic && ready.snapshot.settings.semanticRecall && !ready.semanticAttempted) return null
        val boundary = vault.scopeForChat(chat)
        val usable = synchronized(this) {
            voiceOwners[chat to profile] === owner && ready.revision == ReadinessInvalidation.epoch && ready.snapshot == vault.state.value && ready.boundary == boundary && !boundary.isTemporary
        }
        if (!usable) return null
        val now = System.currentTimeMillis()
        return ready.recall.copy(
            facts = ready.recall.facts.filter { fact ->
                dev.chungjungsoo.gptmobile.data.rag.MemoryEligibility.accepts(fact, ready.snapshot.settings, chat, messageId, local, localOnly, now, ready.recall.includesHistory)
            }
        )
    }

    private val sensitiveDraft = Regex("(?i)(password|api[_ -]?key|bearer|secret)\\s*[:=]")
    private var revision = 0L
    private var job: Job? = null
    private var preview: Preview? = null
    private val captured = linkedSetOf<String>()
    suspend fun warmMetadata() {
        vault.ensureLoadedReadOnly()
    }

    @Synchronized
    fun clear() {
        revision++
        preview = null
        job?.cancel()
        job = null
    }

    @Synchronized
    fun draft(chat: Int, profile: String, text: String, local: Boolean, localOnly: Boolean, history: String = "") {
        clear()
        if (chat <= 0 || text.length !in 16..16_384 || sensitiveDraft.containsMatchIn(text)) return
        val expected = revision
        val key = Key(chat, profile, text, local, localOnly, history)
        val snapshot = vault.state.value
        job = scope.launch {
            try {
                kotlinx.coroutines.delay(300)
                val boundary = vault.scopeForChat(chat)
                val lexical = vault.recallReadOnly(text, chat, isLocal = local, includeLocalOnly = localOnly, previousContext = history)
                synchronized(this@MemoryPreviewCoordinator) {
                    if (revision == expected && vault.state.value == snapshot) preview = Preview(key, expected, snapshot, boundary, lexical)
                }
                val result = vault.recallReadOnly(text, chat, isLocal = local, includeLocalOnly = localOnly, previousContext = history, useSemantic = true)
                val currentBoundary = vault.scopeForChat(chat)
                synchronized(this@MemoryPreviewCoordinator) {
                    if (revision == expected && vault.state.value == snapshot && boundary == currentBoundary) preview = Preview(key, expected, snapshot, boundary, result, semanticAttempted = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { }
        }
    }

    internal suspend fun submitted(chat: Int, profile: String, text: String, messageId: Int, local: Boolean, localOnly: Boolean, history: String, budgetMillis: Long): FactRecall {
        val ready = synchronized(this) { preview?.takeIf { it.key == Key(chat, profile, text, local, localOnly, history) && it.revision == revision && it.snapshot == vault.state.value } }
        if (budgetMillis <= 0) return FactRecall()
        val fallback = java.util.concurrent.atomic.AtomicReference(FactRecall())
        // Producer is not a child of Send: timeout does not join native/DB cleanup.
        val lookup = scope.async(start = CoroutineStart.LAZY) {
            val usable = ready?.takeIf { it.boundary == vault.scopeForChat(chat) }
            val lexical = usable?.recall ?: vault.recallReadOnly(text, chat, messageId, local, localOnly, history)
            fallback.set(lexical)
            if (usable?.semanticAttempted == true) lexical else vault.recallReadOnly(text, chat, messageId, local, localOnly, history, useSemantic = true)
        }
        return try {
            withTimeoutOrNull(budgetMillis) { lookup.await() } ?: fallback.get()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fallback.get()
        } catch (_: LinkageError) {
            fallback.get()
        } finally {
            lookup.cancel()
        }
    }

    fun captureAccepted(text: String, chatId: Int, messageId: Int, local: Boolean, history: String) {
        if (chatId <= 0 || messageId <= 0) return
        val key = "$chatId:$messageId:${dev.chungjungsoo.gptmobile.data.research.researchHash(text)}"
        synchronized(captured) {
            if (!captured.add(key)) return
            while (captured.size > 512) captured.remove(captured.first())
        }
        scope.launch {
            try {
                vault.prepareTurn(text, chatId, messageId, isLocal = local, previousContext = history, recall = false)
            } catch (cancelled: CancellationException) {
                synchronized(captured) { captured.remove(key) }
                throw cancelled
            } catch (_: Exception) {
                synchronized(captured) { captured.remove(key) }
            }
        }
    }
}
