package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shares identical read-only tool executions across concurrent AI runs handling
 * the same user turn. Results are cached only briefly so multi-chat peers can
 * reuse fresh data without turning the broker into a long-lived response cache.
 */
class SharedToolCallBroker(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class Key(
        val scopeId: String,
        val toolIdentity: String,
        val canonicalArguments: String
    )

    private data class Entry(
        val result: CompletableDeferred<AgentToolResult>,
        @Volatile var completedAtMillis: Long? = null
    )

    private val entries = ConcurrentHashMap<Key, Entry>()

    fun wrap(
        scopeId: String?,
        toolIdentity: String,
        shareableReadOnly: Boolean,
        tool: AgentTool
    ): AgentTool {
        if (scopeId.isNullOrBlank() || !shareableReadOnly) return tool

        return object : AgentTool {
            override val definition = tool.definition

            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult =
                executeShared(
                    scopeId = scopeId,
                    toolIdentity = toolIdentity,
                    callId = callId,
                    arguments = arguments
                ) {
                    tool.execute(callId, arguments)
                }
        }
    }

    internal suspend fun executeShared(
        scopeId: String,
        toolIdentity: String,
        callId: String,
        arguments: JsonObject,
        execute: suspend () -> AgentToolResult
    ): AgentToolResult {
        val key = Key(scopeId, toolIdentity, canonicalJson(arguments))

        while (true) {
            val now = nowMillis()
            purgeExpired(now)

            val candidate = Entry(CompletableDeferred())
            val existing = entries.putIfAbsent(key, candidate)

            if (existing == null) {
                try {
                    val result = execute()
                    candidate.completedAtMillis = nowMillis()
                    candidate.result.complete(result)
                    if (result.isError) entries.remove(key, candidate)
                    return result
                } catch (cancellation: CancellationException) {
                    entries.remove(key, candidate)
                    candidate.result.completeExceptionally(cancellation)
                    throw cancellation
                } catch (error: Throwable) {
                    entries.remove(key, candidate)
                    candidate.result.completeExceptionally(error)
                    throw error
                }
            }

            if (existing.isExpired(now) && entries.remove(key, existing)) {
                continue
            }

            try {
                return existing.result.await().copy(callId = callId, sharedResult = true)
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                entries.remove(key, existing)
            }
        }
    }

    private fun purgeExpired(now: Long) {
        entries.entries.removeIf { (_, entry) ->
            entry.isExpired(now)
        }
    }

    private fun Entry.isExpired(now: Long): Boolean =
        result.isCompleted && completedAtMillis?.let { now - it >= ttlMillis } == true

    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject ->
            element.entries
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                    "${quote(key)}:${canonicalJson(value)}"
                }

        is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") { value ->
            canonicalJson(value)
        }
        else -> element.toString()
    }

    private fun quote(value: String): String = JsonPrimitive(value).toString()

    private companion object {
        const val DEFAULT_TTL_MILLIS = 30_000L
    }
}
