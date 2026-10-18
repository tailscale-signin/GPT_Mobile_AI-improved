package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded opportunistic producer. Remote Send never joins its queue, load or teardown. */
internal object OptionalAssistArtifacts {
    private class Entry(val original: AgentToolResult, val valid: suspend () -> Boolean, val fingerprint: () -> String) {
        val created = System.nanoTime()
        val runtime = fingerprint()
        val sourceDigest = digest(original)

        @Volatile var completed: AgentToolResult? = null
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val producing = AtomicBoolean()

    fun enqueue(context: CoroutineContext, original: AgentToolResult, valid: suspend () -> Boolean, fingerprint: () -> String, ownerJob: Job? = null, produce: suspend () -> AgentToolResult): AgentToolResult {
        if (!producing.compareAndSet(false, true)) return original
        val key = UUID.randomUUID().toString()
        val entry = Entry(original, valid, fingerprint)
        entries.entries.removeIf { System.nanoTime() - it.value.created > 120_000_000_000L }
        if (entries.size >= 64) entries.clear()
        entries[key] = entry
        val scope = CoroutineScope(context.minusKey(Job) + SupervisorJob())
        val parentCancellation = (ownerJob ?: context[Job])?.invokeOnCompletion { cause ->
            if (ownerJob != null || cause != null) {
                entries.remove(key)
                scope.coroutineContext[Job]?.cancel()
            }
        }
        val work = scope.launch {
            val watcher = launch {
                while (isActive) {
                    delay(250)
                    if (!valid() || entry.runtime != fingerprint()) {
                        entries.remove(key)
                        scope.coroutineContext[Job]?.cancel()
                    }
                }
            }
            try {
                if (valid()) entry.completed = withTimeoutOrNull(30_000L) { produce() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                entries.remove(key)
            } finally {
                watcher.cancel()
            }
        }
        work.invokeOnCompletion { cause ->
            if (cause != null) entries.remove(key)
            parentCancellation?.dispose()
            producing.set(false)
            scope.coroutineContext[Job]?.cancel()
        }
        return original.copy(optionalAssistKey = key)
    }

    suspend fun ready(result: AgentToolResult): AgentToolResult {
        val key = result.optionalAssistKey ?: return result
        val entry = entries[key] ?: return result
        val prepared = entry.completed ?: return result
        if (System.nanoTime() - entry.created > 120_000_000_000L || entry.original.callId != result.callId || entry.sourceDigest != digest(result) || entry.runtime != entry.fingerprint() || !entry.valid()) return result
        return result.copy(assistMetadata = prepared.assistMetadata)
    }

    private fun digest(result: AgentToolResult): String = dev.chungjungsoo.gptmobile.data.research.researchHash(
        dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.element(result.retainedContent ?: result.content).toString() +
            "|${result.isError}|${result.media}"
    )
}
