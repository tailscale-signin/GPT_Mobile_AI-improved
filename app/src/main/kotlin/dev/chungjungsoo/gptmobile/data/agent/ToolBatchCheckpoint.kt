package dev.chungjungsoo.gptmobile.data.agent

import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Durable dispatch receipts. A crash after dispatch is unknown, never permission to repeat a side effect. */
class ToolBatchCheckpoint(private val file: File?, private val binding: String) {
    @Serializable private data class Call(val id: String, val name: String, val arguments: JsonObject) {
        fun event() = ProviderEvent.ToolCall(id, name, arguments)
    }

    @Serializable private data class Result(val body: String, val json: Boolean, val error: Boolean, val dispatched: Boolean, val code: String?, val callLimit: Boolean, val outputLimit: Boolean) {
        fun restore(id: String) = AgentToolResult(id, if (json) ToolResultContent.Json(Json.parseToJsonElement(body)) else ToolResultContent.Text(body), error, sharedResult = true, dispatched = dispatched, errorCode = code, toolCallBudgetExhausted = callLimit, outputBudgetExhausted = outputLimit)
    }

    @Serializable private data class Entry(val call: Call, val started: Boolean = false, val result: Result? = null)

    @Serializable private data class State(val binding: String, val pending: List<String> = emptyList(), val entries: Map<String, Entry> = emptyMap())
    private val mutex = file?.let { locks.computeIfAbsent(it.absolutePath) { Mutex() } } ?: Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private var memory = State(binding)
    private fun read(): State {
        val saved = file?.takeIf { it.isFile }?.let {
            require(it.length() <= 32 * 1024 * 1024) { "Tool recovery journal exceeds capacity." }
            json.decodeFromString<State>(AtomicFile(it).openRead().bufferedReader().use { stream -> stream.readText() })
        } ?: memory
        require(saved.binding == binding) { "Tool recovery authorization changed." }
        return saved
    }
    private fun write(state: State) {
        val bytes = json.encodeToString(state).toByteArray()
        require(bytes.size <= 32 * 1024 * 1024) { "Tool recovery capacity reached before dispatch." }
        file?.let {
            check(it.absolutePath !in deleted) { "Conversation was deleted; tool checkpoint rejected." }
            check(it.parentFile?.let { parent -> parent.isDirectory || parent.mkdirs() } == true)
            val atomic = AtomicFile(it)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                atomic.finishWrite(output)
            } catch (error: Throwable) {
                atomic.failWrite(output)
                throw error
            }
        }
        memory = state
    }
    private suspend fun <T> locked(action: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { action() } }
    suspend fun pending(): List<ProviderEvent.ToolCall> = locked {
        val state = read()
        state.pending.map { state.entries.getValue(it).call.event() }
    }
    suspend fun dispatchedCount(): Int = locked { read().entries.values.count { it.started } }
    suspend fun pendingDispatchedCount(): Int = locked {
        val state = read()
        state.pending.count { state.entries.getValue(it).started }
    }
    suspend fun enqueue(calls: List<ProviderEvent.ToolCall>) = locked {
        val state = read()
        val entries = state.entries.toMutableMap()
        calls.forEach { call ->
            val row = Call(call.callId, call.name, call.arguments)
            require(entries[call.callId]?.call?.let { it == row } != false) { "Tool call ID changed its operation; dispatch rejected." }
            entries.putIfAbsent(call.callId, Entry(row))
        }
        require(entries.size <= 1024) { "Tool recovery call capacity reached before dispatch." }
        write(state.copy(pending = calls.map { it.callId }, entries = entries))
    }
    suspend fun execute(call: ProviderEvent.ToolCall, dispatch: suspend () -> AgentToolResult): AgentToolResult {
        val restored = locked {
            val state = read()
            val entry = requireNotNull(state.entries[call.callId])
            require(entry.call.event() == call)
            when {
                entry.result != null -> entry.result.restore(call.callId)
                entry.started -> AgentToolResult(call.callId, ToolResultContent.Text("Execution was interrupted after dispatch. Its outcome is unknown; inspect the external state before requesting a new operation. This call was not dispatched again."), true, sharedResult = true, errorCode = "TOOL_OUTCOME_UNKNOWN", dispatched = false)
                else -> {
                    write(state.copy(entries = state.entries + (call.callId to entry.copy(started = true))))
                    null
                }
            }
        }
        if (restored != null) return restored
        val result = dispatch()
        withContext(NonCancellable) { complete(call, result) }
        return result
    }
    suspend fun complete(call: ProviderEvent.ToolCall, result: AgentToolResult) = locked {
        val state = read()
        val entry = requireNotNull(state.entries[call.callId])
        // Replay is bounded separately from the authoritative Research/ToolEvent evidence stores.
        val retained = ToolExchangeCompactor.compact(listOf(AgentToolExchange(listOf(call), listOf(result.copy(traceContent = null, retainedContent = null)))), 4096, 4096).single().results.single()
        val content = retained.content
        val body = when (content) {
            is ToolResultContent.Json -> content.value.toString()
            is ToolResultContent.Text -> content.text
            is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
        }
        val saved = Result(body, content is ToolResultContent.Json, result.isError, result.dispatched, result.errorCode, result.toolCallBudgetExhausted, result.outputBudgetExhausted)
        write(state.copy(entries = state.entries + (call.callId to entry.copy(result = saved))))
    }
    suspend fun finish() = locked {
        val state = read()
        if (state.pending.isNotEmpty()) write(state.copy(pending = emptyList()))
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val deleted = ConcurrentHashMap.newKeySet<String>()
        fun forTurn(directory: File, chatId: Int, binding: String, persistent: Boolean): ToolBatchCheckpoint = ToolBatchCheckpoint(if (persistent) File(directory, "tool-batches/$chatId/${combinedDigest(binding)}.json") else null, binding)
        suspend fun deleteChat(directory: File, chatId: Int) = withContext(Dispatchers.IO) {
            val folder = File(directory, "tool-batches/$chatId")
            val files = (folder.listFiles().orEmpty().toList() + locks.keys.filter { it.startsWith(folder.absolutePath + File.separator) }.map(::File)).distinctBy { it.absolutePath }
            files.forEach { file ->
                locks.computeIfAbsent(file.absolutePath) { Mutex() }.withLock {
                    deleted += file.absolutePath
                    AtomicFile(file).delete()
                }
            }
        }
    }
}
