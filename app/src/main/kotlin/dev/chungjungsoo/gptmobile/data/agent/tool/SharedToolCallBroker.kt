package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExecutionOwner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.OwnedAgentTool
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
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
        val result: Deferred<AgentToolResult>,
        var subscribers: Int = 0,
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

        return object : OwnedAgentTool {
            override val definition = tool.definition
            override val managesExecutionBudget = tool.managesExecutionBudget
            override val coordinatesChildWork = tool.coordinatesChildWork
            override val executionOwner = (tool as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

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

        val context = currentCoroutineContext()
        var shared = false
        val entry = synchronized(entries) {
            purgeExpired(nowMillis())
            val existing = entries[key]
            val selected = if (existing != null) {
                shared = true
                existing
            } else {
                // The physical read belongs to all subscribers, never to the first waiter.
                // Every caller enters through its own admitted tool/authority wrapper.
                lateinit var created: Entry
                val producer = CoroutineScope(context.minusKey(Job)).async(start = CoroutineStart.LAZY) {
                    try {
                        val result = execute().copy(statisticsProducerId = context[dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsContext]?.invocationId)
                        created.completedAtMillis = nowMillis()
                        if (result.isError) synchronized(entries) { entries.remove(key, created) }
                        result
                    } catch (error: Throwable) {
                        synchronized(entries) { entries.remove(key, created) }
                        throw error
                    }
                }
                created = Entry(producer)
                entries[key] = created
                created
            }
            selected.subscribers++
            selected
        }
        try {
            entry.result.start()
            return entry.result.await().copy(callId = callId, sharedResult = shared)
        } finally {
            val cancelProducer = synchronized(entries) {
                entry.subscribers--
                (entry.subscribers == 0 && !entry.result.isCompleted).also { cancel ->
                    if (cancel) entries.remove(key, entry)
                }
            }
            // Join native/network cleanup before the last subscriber releases its owner.
            if (cancelProducer) withContext(NonCancellable) { entry.result.cancelAndJoin() }
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
