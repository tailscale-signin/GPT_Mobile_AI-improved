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
    val unsettledCalls: List<ProviderEvent.ToolCall> = emptyList(),
    val queuedCount: Int = 0,
    val rejectedCount: Int = 0,
    val failedCount: Int = 0,
    val jobContext: ToolBatchJobContext? = null,
    val completedExchanges: List<AgentToolExchange> = emptyList(),
    val consumedCallIds: Set<String> = emptySet(),
    val pendingCompletedExchange: AgentToolExchange? = null
) {
    val unknownCount: Int get() = unsettledCalls.size
}

/** Durable dispatch receipts. A crash after dispatch is unknown, never permission to repeat a side effect. */
class ToolBatchCheckpoint(private val file: File?, private val binding: String, private val initialJob: ToolBatchJobContext? = null, private val expectedProviderEvidenceDigest: String? = null, private val expectedProviderJournalDigest: String? = null) {
    @Serializable private data class Call(val id: String, val name: String, val arguments: JsonObject, val originalInvocationFingerprint: String? = null, val originalProviderName: String? = null, val originalProviderArguments: JsonObject? = null) {
        fun event() = ProviderEvent.ToolCall(id, name, arguments, originalInvocationFingerprint, originalProviderName, originalProviderArguments)
    }

    @Serializable private data class Result(val body: String, val json: Boolean, val error: Boolean, val dispatched: Boolean, val code: String?, val callLimit: Boolean, val outputLimit: Boolean, val execution: JsonObject? = null, val sourceResultSha256: String? = null, val projectionSha256: String? = null) {
        fun restore(id: String): AgentToolResult {
            // The body is an evictable replay projection. Its original identity was
            // captured before compaction and must survive a process restart.
            val identity = sourceResultSha256?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { source ->
                JsonObject(execution.orEmpty() + mapOf("source_result_sha256" to JsonPrimitive(source), "source_hash_scope" to JsonPrimitive("ORIGINAL_TOOL_RESULT_PAYLOAD_UTF8")))
            } ?: execution
            return AgentContractValidation.receipt(id, AgentToolResult(id, if (json) ToolResultContent.Json(Json.parseToJsonElement(body)) else ToolResultContent.Text(body), error, sharedResult = true, dispatched = dispatched, errorCode = code, toolCallBudgetExhausted = callLimit, outputBudgetExhausted = outputLimit, executionReceipt = identity))
        }
    }

    @Serializable private data class Entry(val call: Call, val started: Boolean = false, val result: Result? = null, val priorBudgetRejection: Result? = null)

    @Serializable private data class State(val binding: String, val schemaVersion: Int = 2, val pending: List<String> = emptyList(), val entries: Map<String, Entry> = emptyMap(), val jobContext: ToolBatchJobContext? = null, val consumedCallIds: Set<String> = emptySet(), val batches: List<List<String>> = emptyList())
    private val mutex = file?.let { locks.computeIfAbsent(it.absolutePath) { Mutex() } } ?: Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private var memory = State(binding, jobContext = initialJob)

    @Volatile private var providerRecoveryValidated = false

    init {
        require(expectedProviderEvidenceDigest == null || expectedProviderEvidenceDigest.matches(Regex("[a-f0-9]{64}")))
        require(expectedProviderJournalDigest == null || expectedProviderJournalDigest.matches(Regex("[a-f0-9]{64}")))
    }

    private fun read(): State {
        val saved = file?.takeIf { it.isFile || File(it.path + ".bak").isFile }?.let {
            require(maxOf(it.length(), File(it.path + ".bak").length()) <= 32 * 1024 * 1024) { "Tool recovery journal exceeds capacity." }
            json.decodeFromString<State>(AtomicFile(it).openRead().bufferedReader().use { stream -> stream.readText() })
        } ?: memory
        return validated(saved)
    }

    private fun validated(saved: State): State {
        require(saved.schemaVersion in setOf(1, 2)) { "Unsupported tool recovery checkpoint version." }
        require(saved.binding == binding) { "Tool recovery authorization changed." }
        require(saved.entries.size <= 1024 && saved.pending.distinct().size == saved.pending.size && saved.pending.all(saved.entries::containsKey)) { "Tool recovery journal has invalid call identities." }
        require(saved.entries.all { (id, entry) -> id.isNotBlank() && id == entry.call.id && (entry.call.originalProviderName == null) == (entry.call.originalProviderArguments == null) }) { "Tool recovery journal has inconsistent receipts." }
        require(saved.batches.size <= 1024 && saved.batches.sumOf { it.size } <= 8192 && saved.batches.all { batch -> batch.isNotEmpty() && batch.distinct().size == batch.size && batch.all(saved.entries::containsKey) }) { "Tool recovery journal has invalid batch identities." }
        require(saved.batches.isEmpty() || saved.pending.isEmpty() || saved.batches.last() == saved.pending) { "Tool recovery pending batch no longer matches its original grouping." }
        require(saved.consumedCallIds.size <= 1024 && saved.consumedCallIds.all { saved.entries[it]?.result != null }) { "Tool recovery ledger has invalid consumed identities." }
        if (!providerRecoveryValidated && (expectedProviderEvidenceDigest != null || expectedProviderJournalDigest != null)) {
            val receipts = GitHubProviderReplayEvidence.receipts(saved.settledExchanges())
            ProviderRecoveryFailure.require(
                saved.entries.values.all { it.result != null } &&
                    receipts.none { it.requiresReconciliation } &&
                    if (expectedProviderJournalDigest != null) {
                        GitHubProviderReplayEvidence.journalDigest(receipts) == expectedProviderJournalDigest
                    } else {
                        GitHubProviderReplayEvidence.digest(receipts) == expectedProviderEvidenceDigest
                    },
                "PROVIDER_WAIT_CHECKPOINT_CHANGED"
            )
        }
        if (initialJob != null && saved.jobContext != null) {
            require(initialJob.objective == saved.jobContext.objective && initialJob.repository == saved.jobContext.repository) { "Saved engineering objective or repository changed; reconcile the previous job before starting another." }
            // Branch creation/selection can legitimately update the conversation during a run.
            // Derive that transition from durable successful receipts, including older journals.
            val destinationVerified = initialJob.branch == saved.jobContext.branch ||
                saved.entries.values.any { entry ->
                    val operation = entry.call.name.substringBefore("__")
                    val action = (entry.call.arguments["action"] as? JsonPrimitive)?.content
                    if (!(operation == "github_commit" && action == "branch" || operation == "github_context" && action == "resolve")) return@any false
                    val result = entry.result ?: return@any false
                    dev.chungjungsoo.gptmobile.data.github.GitHubBranchTransition.verified(
                        initialJob.repository,
                        entry.call.name,
                        entry.call.arguments,
                        runCatching { Json.parseToJsonElement(result.body) }.getOrNull(),
                        !result.error && result.code == null
                    )?.branch == initialJob.branch
                }
            require(destinationVerified) {
                "Saved engineering destination changed without a verified branch-selection receipt; reconcile the previous job before starting another."
            }
        }
        return if (saved.jobContext == null && initialJob != null) saved.copy(jobContext = initialJob) else saved
    }
    private fun write(state: State) {
        val bytes = json.encodeToString(state.copy(schemaVersion = 2)).toByteArray()
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

    /** Isolation must not restore arguments/results derived from the removed history. */
    suspend fun requireFreshPersonalContext() = locked {
        check(read().entries.isEmpty()) {
            "This saved attempt may contain personal context. Send a new repository request. Earlier operations remain recorded and must be reconciled before repeating writes."
        }
    }

    suspend fun pending(): List<ProviderEvent.ToolCall> = locked {
        val state = read()
        state.pending.map { state.entries.getValue(it).call.event() }
    }

    /** Older journals prove only their current pending batch; unrelated history stays separate. */
    private fun State.orderedBatches(): List<List<String>> {
        val recorded = batches.ifEmpty { listOfNotNull(pending.takeIf { it.isNotEmpty() }) }
        val grouped = recorded.flatten().toSet()
        return entries.keys.filterNot { it in grouped }.map { listOf(it) } + recorded
    }

    private fun State.settledExchanges(excluded: Set<String> = emptySet()): List<AgentToolExchange> = orderedBatches().mapNotNull { batch ->
        val settled = batch.filter { it !in excluded }.map { entries.getValue(it) }.filter { it.result != null }
        if (settled.isEmpty()) null else AgentToolExchange(settled.map { it.call.event() }, settled.map { requireNotNull(it.result).restore(it.call.id) })
    }

    /** Resolve exact handoff identities without parsing or replaying retained payload bodies. */
    internal suspend fun handoffReceipts(fingerprints: Set<String>): List<ToolHandoffReceipt> = locked {
        read().entries.values.mapNotNull { entry ->
            val fingerprint = ToolHandoffReceipt.fingerprint(entry.call.event())
            if (fingerprint !in fingerprints) return@mapNotNull null
            val result = entry.result ?: return@mapNotNull null
            ToolHandoffReceipt(
                entry.call.id,
                fingerprint,
                result.sourceResultSha256,
                result.projectionSha256 ?: dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(result.body),
                result.dispatched,
                result.code,
                result.code in ToolResultEnvelope.unknownOutcomeCodes ||
                    result.execution?.get("requires_reconciliation") == JsonPrimitive(true) ||
                    GatewayOutcomeRecovery.requiresReconciliation("", result.code),
                "TOOL_BATCH_CHECKPOINT"
            )
        }
    }

    suspend fun markConsumed(ids: Set<String>) = locked {
        val state = read()
        val consumed = state.consumedCallIds + ids.filter { state.entries[it]?.result != null }
        if (consumed != state.consumedCallIds) write(state.copy(consumedCallIds = consumed))
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
        if (entries != state.entries) write(state.copy(entries = entries, consumedCallIds = state.consumedCallIds.filterTo(mutableSetOf()) { entries[it]?.result != null }))
    }

    suspend fun recoverySnapshot(objective: CheckpointRestoreObjective? = null): ToolBatchRecoverySnapshot {
        val started = System.nanoTime()
        // State and its entries are immutable. Restore/parse retained result bodies
        // after releasing the file lock so another completed tool can checkpoint.
        val state = locked { read() }
        val snapshot = withContext(Dispatchers.Default) {
            // Resume a partially settled batch as one original assistant batch.
            // execute() returns its completed members from receipts without dispatch.
            val pendingIds = state.pending.takeIf { ids -> ids.any { state.entries.getValue(it).result == null } }.orEmpty()
            val pending = pendingIds.map { state.entries.getValue(it).call.event() }
            val dispatchedCount = state.entries.values.count { it.countsAsDispatched() }
            val pendingDispatchedIds = pendingIds.filterTo(mutableSetOf()) { state.entries.getValue(it).countsAsDispatched() }
            val pendingRetainedIds = pendingIds.filterTo(mutableSetOf()) { state.entries.getValue(it).let { entry -> entry.started || entry.result != null } }
            val pendingCompleted = pendingIds.map { state.entries.getValue(it) }.filter { it.result != null }
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
                unsettledCalls = state.entries.values.filter { it.started && it.result == null || it.result?.code in ToolResultEnvelope.unknownOutcomeCodes }.map { it.call.event() },
                queuedCount = state.entries.values.count { !it.started && it.result == null },
                rejectedCount = state.entries.values.count { it.result?.let { result -> result.error && !result.dispatched && result.code !in ToolResultEnvelope.unknownOutcomeCodes } == true },
                failedCount = state.entries.values.count { it.result?.let { result -> result.error && result.dispatched && result.code !in ToolResultEnvelope.unknownOutcomeCodes } == true },
                jobContext = state.jobContext,
                completedExchanges = state.settledExchanges(pendingIds.toSet()),
                consumedCallIds = state.consumedCallIds,
                // Observe these receipts once, but replay the assistant batch only
                // after its unsettled members also have terminal results.
                pendingCompletedExchange = pendingCompleted.takeIf { it.isNotEmpty() }?.let { settled ->
                    AgentToolExchange(settled.map { it.call.event() }, settled.map { requireNotNull(it.result).restore(it.call.id) })
                }
            )
        }
        if (expectedProviderEvidenceDigest != null || expectedProviderJournalDigest != null) {
            locked {
                if (!providerRecoveryValidated) {
                    ProviderRecoveryFailure.require(read() == state, "PROVIDER_WAIT_CHECKPOINT_CHANGED")
                }
            }
            // Both dispatcher handoffs must complete before releasing the guard.
            // A cancelled snapshot must leave the next restoration fenced.
            providerRecoveryValidated = true
        }
        return snapshot
    }
    suspend fun enqueue(calls: List<ProviderEvent.ToolCall>) = locked {
        val state = read()
        require(calls.map { it.callId }.distinct().size == calls.size) { "Duplicate tool call IDs cannot be checkpointed." }
        val incoming = calls.map { it.callId }
        val restoring = state.pending.isNotEmpty() && (state.pending == incoming || state.pending.filter { state.entries.getValue(it).result == null } == incoming)
        require(
            state.pending == incoming ||
                state.pending.filter { state.entries.getValue(it).result == null } == incoming ||
                state.pending.none { id ->
                    state.entries.getValue(id).let { !it.started && it.result == null }
                }
        ) { "An earlier batch still has queued calls; restore it before starting another batch." }
        val entries = state.entries.toMutableMap()
        calls.forEach { call ->
            val row = Call(call.callId, call.name, call.arguments, call.originalInvocationFingerprint, call.originalProviderName, call.originalProviderArguments)
            require(entries[call.callId]?.call?.let { it == row } != false) { "Tool call ID changed its operation; dispatch rejected." }
            entries.putIfAbsent(call.callId, Entry(row))
        }
        require(entries.size <= 1024) { "Tool recovery call capacity reached before dispatch." }
        val batches = state.orderedBatches().let { if (restoring || incoming.isEmpty()) it else it + listOf(incoming) }
        require(batches.size <= 1024 && batches.sumOf { it.size } <= 8192) { "Tool recovery batch capacity reached before dispatch." }
        write(state.copy(pending = if (restoring) state.pending else incoming, entries = entries, batches = batches))
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

    private suspend fun saveResult(call: ProviderEvent.ToolCall, result: AgentToolResult, claimed: Boolean) {
        // Replay is bounded separately from the authoritative Research/ToolEvent evidence stores.
        // Compaction and JSON encoding depend only on this immutable result. They
        // must not hold the dispatch/checkpoint mutex while other tools finish.
        val saved = withContext(Dispatchers.Default) {
            val retained = ToolExchangeCompactor.compact(listOf(AgentToolExchange(listOf(call), listOf(result.copy(traceContent = null, retainedContent = null)))), 4096, 4096).single().results.single()
            val content = retained.content
            val body = when (content) {
                is ToolResultContent.Json -> content.value.toString()
                is ToolResultContent.Text -> content.text
                is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
            }
            Result(
                body, content is ToolResultContent.Json, result.isError, result.dispatched, result.errorCode,
                result.toolCallBudgetExhausted, result.outputBudgetExhausted, result.executionReceipt,
                ToolHandoffReceipt.sourceHash(result),
                dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest(body)
            )
        }
        locked {
            val state = read()
            val entry = requireNotNull(state.entries[call.callId])
            require(entry.call.event() == call && result.callId == call.callId) { "Tool receipt does not match the checkpointed call." }
            // Revalidate ownership after preparation. A concurrent receipt, deletion
            // or unclaimed completion must never replace the first durable result.
            if (entry.result != null || (entry.started && !claimed)) return@locked
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
        fun forTurn(directory: File, chatId: Int, binding: String, persistent: Boolean, jobContext: ToolBatchJobContext? = null, expectedProviderEvidenceDigest: String? = null, expectedProviderJournalDigest: String? = null): ToolBatchCheckpoint = ToolBatchCheckpoint(if (persistent) File(directory, "tool-batches/$chatId/${combinedDigest(binding)}.json") else null, binding, jobContext, expectedProviderEvidenceDigest, expectedProviderJournalDigest)

        /**
         * Older output checkpoints did not retain their receipt-journal key. Recover
         * only a unique, settled journal with the exact protected provider evidence.
         * The caller still validates current dispatch and saved-evidence authority.
         */
        internal suspend fun legacyProviderBinding(
            directory: File,
            chatId: Int,
            scope: String,
            expected: dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint,
            checkpoint: JsonObject
        ): String? = withContext(Dispatchers.IO) {
            if (!scope.startsWith("$chatId:") || scope.length > 1024 || !expected.matches(checkpoint)) return@withContext null
            val context = checkpoint["context"] as? JsonObject ?: return@withContext null
            if (context["task_binding"] != JsonPrimitive(expected.binding) || "tool_checkpoint_binding" in context) return@withContext null
            val evidenceDigest = (checkpoint["provider_evidence_digest"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return@withContext null
            val journalDigest = GitHubProviderReplayEvidence.protectedJournalDigest(checkpoint)
            val folder = File(directory, "tool-batches/$chatId")
            val entries = folder.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}\\.json(?:\\.bak)?")) } ?: return@withContext null
            // An incomplete scan cannot establish uniqueness. Large or malformed
            // legacy inventories require explicit recovery instead of a guessed key.
            if (entries.size > 256 || entries.sumOf { it.length() } > 32L * 1024 * 1024) return@withContext null
            if (entries.any { !it.isFile || it.canonicalFile.parentFile != folder.canonicalFile }) return@withContext null
            val files = entries.map { File(folder, it.name.removeSuffix(".bak")) }.distinctBy { it.name }
            var inspectedBytes = 0L
            var matched: String? = null
            for (file in files) {
                if (file.canonicalFile.parentFile != folder.canonicalFile) return@withContext null
                val stored = locks.computeIfAbsent(file.absolutePath) { Mutex() }.withLock {
                    if (file.absolutePath in deleted) return@withLock null
                    inspectedBytes += maxOf(file.length(), File(file.path + ".bak").length())
                    if (inspectedBytes > 32L * 1024 * 1024) return@withLock null
                    runCatching {
                        val reader = ToolBatchCheckpoint(file, "")
                        AtomicFile(file).openRead().bufferedReader().use { reader.json.decodeFromString<State>(it.readText()) }
                    }.getOrNull()
                } ?: return@withContext null
                val retained = stored.binding
                if (!retained.startsWith("$scope:") || retained.length > 4096) continue
                if (file.name != "${combinedDigest(retained)}.json") return@withContext null
                val candidate = ToolBatchCheckpoint(file, retained)
                val settled = try {
                    candidate.validated(stored)
                } catch (_: Exception) {
                    return@withContext null
                }
                if (settled.entries.values.any { it.result == null }) continue
                val receipts = GitHubProviderReplayEvidence.receipts(candidate.run { settled.settledExchanges() })
                if (receipts.isEmpty() || receipts.any { it.requiresReconciliation }) continue
                val matches = if (journalDigest != null) {
                    GitHubProviderReplayEvidence.journalDigest(receipts) == journalDigest
                } else {
                    GitHubProviderReplayEvidence.digest(receipts) == evidenceDigest
                }
                if (!matches) continue
                if (matched != null) return@withContext null
                matched = retained
            }
            matched
        }

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
