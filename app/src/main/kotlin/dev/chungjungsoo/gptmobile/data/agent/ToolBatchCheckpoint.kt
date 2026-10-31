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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Accepted job context, distinct from the optional checkpoint-load latency objective. */
@Serializable
data class ToolBatchJobContext(
    val objective: String,
    val stage: String,
    val repository: String,
    val branch: String,
    val commitSha: String,
    val pullRequest: Int? = null,
    val retryPolicy: String = "TRANSIENT_READ_BACKOFF_ONLY;MUTATIONS_REQUIRE_RECONCILIATION;PRECHECK_REJECTIONS_NEVER_RETRY",
    val lastVerifiedReceipt: JsonObject? = null
)

/** An objective for loading a checkpoint, not for reconciling remote writes. */
data class CheckpointRestoreObjective(val maximumMillis: Long) {
    init {
        require(maximumMillis > 0)
    }
    fun assess(elapsedMillis: Long): String = if (elapsedMillis.coerceAtLeast(0) <= maximumMillis) "MET" else "EXCEEDED"
}

data class ToolBatchRecoverySnapshot(
    val pending: List<ProviderEvent.ToolCall>,
    val dispatchedCount: Int,
    val pendingDispatchedIds: Set<String>,
    val pendingRetainedIds: Set<String>,
    val loadMillis: Long,
    val objectiveMillis: Long?,
    val objectiveStatus: String,
    val completedCount: Int = 0,
    val unknownCount: Int = 0,
    val queuedCount: Int = 0,
    val rejectedCount: Int = 0,
    val failedCount: Int = 0,
    val jobContext: ToolBatchJobContext? = null
)

/** Durable dispatch receipts. A crash after dispatch is unknown, never permission to repeat a side effect. */
class ToolBatchCheckpoint(private val file: File?, private val binding: String, private val initialJob: ToolBatchJobContext? = null) {
    @Serializable private data class Call(val id: String, val name: String, val arguments: JsonObject) {
        fun event() = ProviderEvent.ToolCall(id, name, arguments)
    }

    @Serializable private data class Result(val body: String, val json: Boolean, val error: Boolean, val dispatched: Boolean, val code: String?, val callLimit: Boolean, val outputLimit: Boolean, val execution: JsonObject? = null) {
        fun restore(id: String) = AgentContractValidation.receipt(id, AgentToolResult(id, if (json) ToolResultContent.Json(Json.parseToJsonElement(body)) else ToolResultContent.Text(body), error, sharedResult = true, dispatched = dispatched, errorCode = code, toolCallBudgetExhausted = callLimit, outputBudgetExhausted = outputLimit, executionReceipt = execution))
    }

    @Serializable private data class Entry(val call: Call, val started: Boolean = false, val result: Result? = null, val priorBudgetRejection: Result? = null)

    @Serializable private data class State(val binding: String, val schemaVersion: Int = 1, val pending: List<String> = emptyList(), val entries: Map<String, Entry> = emptyMap(), val jobContext: ToolBatchJobContext? = null)
    private val mutex = file?.let { locks.computeIfAbsent(it.absolutePath) { Mutex() } } ?: Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private var memory = State(binding, jobContext = initialJob)
    private fun read(): State {
        val saved = file?.takeIf { it.isFile || File(it.path + ".bak").isFile }?.let {
            require(maxOf(it.length(), File(it.path + ".bak").length()) <= 32 * 1024 * 1024) { "Tool recovery journal exceeds capacity." }
            json.decodeFromString<State>(AtomicFile(it).openRead().bufferedReader().use { stream -> stream.readText() })
        } ?: memory
        require(saved.schemaVersion == 1) { "Unsupported tool recovery checkpoint version." }
        require(saved.binding == binding) { "Tool recovery authorization changed." }
        require(saved.entries.size <= 1024 && saved.pending.distinct().size == saved.pending.size && saved.pending.all(saved.entries::containsKey)) { "Tool recovery journal has invalid call identities." }
        require(saved.entries.all { (id, entry) -> id.isNotBlank() && id == entry.call.id }) { "Tool recovery journal has inconsistent receipts." }
        if (initialJob != null && saved.jobContext != null) {
            require(initialJob.objective == saved.jobContext.objective && initialJob.repository == saved.jobContext.repository && initialJob.branch == saved.jobContext.branch) { "Saved engineering objective or destination changed; reconcile the previous job before starting another." }
        }
        return if (saved.jobContext == null && initialJob != null) saved.copy(jobContext = initialJob) else saved
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

    // A claimed call with no receipt may have executed. A saved pre-dispatch
    // rejection, however, must not consume the executable-call budget on resume.
    private fun Entry.countsAsDispatched(): Boolean = result?.dispatched ?: started
    suspend fun dispatchedCount(): Int = locked { read().entries.values.count { it.countsAsDispatched() } }
    suspend fun pendingDispatchedIds(): Set<String> = locked {
        val state = read()
        state.pending.filterTo(mutableSetOf()) { state.entries.getValue(it).countsAsDispatched() }
    }

    /** Only an explicit user retry may reopen a proven pre-dispatch budget rejection. */
    suspend fun prepareExplicitBudgetRetry() = locked {
        val state = read()
        val entries = state.entries.toMutableMap()
        state.pending.forEach { id ->
            val entry = entries.getValue(id)
            val result = entry.result ?: return@forEach
            entries[id] = if (result.error && !result.dispatched && result.code in setOf("BUDGET_EXHAUSTED", "DEPENDENCY_DEFERRED")) {
                // Keep the old refusal for audit, but let ordinary admission decide
                // whether this still-unexecuted call fits the current allowance.
                entry.copy(started = false, result = null, priorBudgetRejection = result)
            } else {
                // These are attempt-local signals, not execution authority. The
                // new budget is enforced on new work; the receipt stays retained.
                entry.copy(result = result.copy(callLimit = false, outputLimit = false))
            }
        }
        if (entries != state.entries) write(state.copy(entries = entries))
    }

    suspend fun recoverySnapshot(objective: CheckpointRestoreObjective? = null): ToolBatchRecoverySnapshot {
        val started = System.nanoTime()
        return locked {
            val state = read()
            val pending = state.pending.map { state.entries.getValue(it).call.event() }
            val dispatchedCount = state.entries.values.count { it.countsAsDispatched() }
            val pendingDispatchedIds = state.pending.filterTo(mutableSetOf()) { state.entries.getValue(it).countsAsDispatched() }
            val pendingRetainedIds = state.pending.filterTo(mutableSetOf()) { state.entries.getValue(it).let { entry -> entry.started || entry.result != null } }
            val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
            ToolBatchRecoverySnapshot(
                pending = pending,
                dispatchedCount = dispatchedCount,
                pendingDispatchedIds = pendingDispatchedIds,
                pendingRetainedIds = pendingRetainedIds,
                loadMillis = elapsed,
                objectiveMillis = objective?.maximumMillis,
                objectiveStatus = objective?.assess(elapsed) ?: "NOT_CONFIGURED",
                completedCount = state.entries.values.count { it.result?.let { result -> !result.error && result.code !in ToolResultEnvelope.unknownOutcomeCodes } == true },
                unknownCount = state.entries.values.count { it.started && it.result == null || it.result?.code in ToolResultEnvelope.unknownOutcomeCodes },
                queuedCount = state.entries.values.count { !it.started && it.result == null },
                rejectedCount = state.entries.values.count { it.result?.let { result -> result.error && !result.dispatched && result.code !in ToolResultEnvelope.unknownOutcomeCodes } == true },
                failedCount = state.entries.values.count { it.result?.let { result -> result.error && result.dispatched && result.code !in ToolResultEnvelope.unknownOutcomeCodes } == true },
                jobContext = state.jobContext
            )
        }
    }
    suspend fun enqueue(calls: List<ProviderEvent.ToolCall>) = locked {
        val state = read()
        require(calls.map { it.callId }.distinct().size == calls.size) { "Duplicate tool call IDs cannot be checkpointed." }
        val incoming = calls.map { it.callId }
        require(
            state.pending == incoming ||
                state.pending.none { id ->
                    state.entries.getValue(id).let { !it.started && it.result == null }
                }
        ) { "An earlier batch still has queued calls; restore it before starting another batch." }
        val entries = state.entries.toMutableMap()
        calls.forEach { call ->
            val row = Call(call.callId, call.name, call.arguments)
            require(entries[call.callId]?.call?.let { it == row } != false) { "Tool call ID changed its operation; dispatch rejected." }
            entries.putIfAbsent(call.callId, Entry(row))
        }
        require(entries.size <= 1024) { "Tool recovery call capacity reached before dispatch." }
        write(state.copy(pending = calls.map { it.callId }, entries = entries))
    }
    suspend fun execute(call: ProviderEvent.ToolCall, replayableRead: Boolean = false, dispatch: suspend () -> AgentToolResult): AgentToolResult {
        val restored = locked {
            val state = read()
            val entry = requireNotNull(state.entries[call.callId])
            require(entry.call.event() == call)
            when {
                entry.result != null -> entry.result.restore(call.callId)
                entry.started && !replayableRead -> AgentToolResult(call.callId, ToolResultContent.Text("Execution was interrupted after dispatch. Its outcome is unknown; inspect the external state before requesting a new operation. This call was not dispatched again."), true, sharedResult = true, errorCode = "TOOL_OUTCOME_UNKNOWN", dispatched = false)
                else -> {
                    write(state.copy(entries = state.entries + (call.callId to entry.copy(started = true))))
                    null
                }
            }
        }
        if (restored != null) return restored
        val result = dispatch()
        withContext(NonCancellable) { saveResult(call, result, claimed = true) }
        return result
    }

    suspend fun complete(call: ProviderEvent.ToolCall, result: AgentToolResult) = saveResult(call, result, claimed = false)

    private suspend fun saveResult(call: ProviderEvent.ToolCall, result: AgentToolResult, claimed: Boolean) = locked {
        val state = read()
        val entry = requireNotNull(state.entries[call.callId])
        require(entry.call.event() == call && result.callId == call.callId) { "Tool receipt does not match the checkpointed call." }
        // A later deferred/suppressed replay must never replace the first receipt.
        if (entry.result != null || (entry.started && !claimed)) return@locked
        // Replay is bounded separately from the authoritative Research/ToolEvent evidence stores.
        val retained = ToolExchangeCompactor.compact(listOf(AgentToolExchange(listOf(call), listOf(result.copy(traceContent = null, retainedContent = null)))), 4096, 4096).single().results.single()
        val content = retained.content
        val body = when (content) {
            is ToolResultContent.Json -> content.value.toString()
            is ToolResultContent.Text -> content.text
            is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
        }
        val saved = Result(body, content is ToolResultContent.Json, result.isError, result.dispatched, result.errorCode, result.toolCallBudgetExhausted, result.outputBudgetExhausted, result.executionReceipt)
        val observed = if (!result.isError && result.dispatched && result.errorCode !in ToolResultEnvelope.unknownOutcomeCodes) result.executionReceipt else null
        val operation = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(call.name)
        val action = (call.arguments["action"] as? JsonPrimitive)?.content
        val prSource = operation == "github_pr" || call.name == "github" && action in setOf("create_pull_request", "get_pull_request", "update_pull_request")
        val payload = if (prSource) ToolResultEnvelope.element(result.content) as? JsonObject else null
        val pr = (payload?.get("pull_request_number") as? JsonPrimitive)?.intOrNull
            ?: ((payload?.get("pull_request") as? JsonObject)?.get("number") as? JsonPrimitive)?.intOrNull
            ?: if (operation == "github_pr") (payload?.get("number") as? JsonPrimitive)?.intOrNull else null
        val job = state.jobContext?.let { context ->
            if (observed == null) {
                context
            } else {
                context.copy(
                    pullRequest = pr?.takeIf { it > 0 } ?: context.pullRequest,
                    lastVerifiedReceipt = buildJsonObject {
                        put("callId", call.callId)
                        put("tool", call.name)
                        put("execution", observed)
                    }
                )
            }
        }
        write(state.copy(entries = state.entries + (call.callId to entry.copy(result = saved)), jobContext = job))
    }
    suspend fun finish() = locked {
        val state = read()
        require(state.pending.none { id -> state.entries.getValue(id).let { !it.started && it.result == null } }) {
            "Cannot finish while checkpointed calls remain queued."
        }
        if (state.pending.isNotEmpty()) write(state.copy(pending = emptyList()))
    }
    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val deleted = ConcurrentHashMap.newKeySet<String>()
        fun forTurn(directory: File, chatId: Int, binding: String, persistent: Boolean, jobContext: ToolBatchJobContext? = null): ToolBatchCheckpoint = ToolBatchCheckpoint(if (persistent) File(directory, "tool-batches/$chatId/${combinedDigest(binding)}.json") else null, binding, jobContext)
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
