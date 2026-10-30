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
        if (chat <= 0 || text.length !in 16..16_384 || Regex("(?i)(password|api[_ -]?key|bearer|secret)\\s*[:=]").containsMatchIn(text)) return
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
