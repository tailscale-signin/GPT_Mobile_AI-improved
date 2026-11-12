package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Resume a complete, output-limited text segment without taking tools away from unfinished work. */
internal class GitHubOutputContinuationSession(
    private val delegate: AgentProviderSession,
    private val maxContinuations: Int = 2,
    private val maxCharacters: Int = 1_048_576,
    private val continuationRevision: String = "",
    private val recoveryRevision: String = "",
    private val expectedProviderCheckpoint: dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint? = null,
    private val checkpointContext: JsonObject = JsonObject(emptyMap()),
    private val awaitConnectivity: suspend (Long) -> Boolean = { true },
    private val reconciledCallIds: suspend () -> Set<String> = { emptySet() },
    private val durableToolReceipts: suspend (Set<String>) -> List<ToolHandoffReceipt> = { emptyList() },
    private val publishProgress: suspend (String, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage) -> Unit = { _, _ -> },
    private val readCheckpoint: suspend () -> JsonObject? = { null },
    private val saveCheckpoint: suspend (JsonObject) -> Unit = {},
    private val reopen: suspend (String, List<AgentToolExchange>, List<AgentToolDefinition>) -> AgentProviderSession
) : AgentProviderSession by delegate {
    private var currentCheckpoint: JsonObject? = null
    private var providerCheckpointValidated = false
    private val checkpointMutex = Mutex()

    /** The runner calls this only after both its execution receipt and evidence observer are saved. */
    suspend fun observeToolResult(call: ProviderEvent.ToolCall, result: AgentToolResult) = checkpointMutex.withLock {
        val previous = readCheckpoint() ?: currentCheckpoint ?: return@withLock
        if (previous["state"] !in GitHubToolHandoff.states) return@withLock
        val durable = durableToolReceipts(GitHubToolHandoff.pending(previous))
        val receipt = durable.firstOrNull { it.callId == call.callId }
            ?: ToolHandoffReceipt.from(call, result, originalAvailable = !result.sharedResult)
        val updated = GitHubToolHandoff.reconcile(previous, durable + receipt, reconciledCallIds())
        if (updated != previous) {
            saveCheckpoint(updated)
            currentCheckpoint = updated
        }
    }

    override suspend fun awaitRecoveryReady(maxWaitMillis: Long): Boolean = awaitConnectivity(maxWaitMillis)

    /** Hydrate a saved turn with one handoff reconciliation and at most one write. */
    suspend fun restoreToolResults(exchanges: List<AgentToolExchange>) = checkpointMutex.withLock {
        val previous = readCheckpoint() ?: currentCheckpoint ?: return@withLock
        if (previous["state"] !in GitHubToolHandoff.states) return@withLock
        val durable = durableToolReceipts(GitHubToolHandoff.pending(previous))
        val restored = exchanges.flatMap { exchange ->
            exchange.calls.mapNotNull { call ->
                exchange.results.firstOrNull { it.callId == call.callId }?.let { ToolHandoffReceipt.from(call, it, originalAvailable = false) }
            }
        }
        val updated = GitHubToolHandoff.reconcile(previous, restored + durable, reconciledCallIds())
        if (updated != previous) saveCheckpoint(updated)
        currentCheckpoint = updated
    }

    init {
        require(maxContinuations in 0..4)
        require(maxCharacters in 1..1_048_576)
    }

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (delegate.handlesToolsInternally) {
            emitAll(delegate.streamRound(tools, exchanges))
            return@flow
        }
        val evidence = continuationEvidenceDigests(exchanges)
        val evidenceDigest = evidence.full
        val providerEvidenceDigest = evidence.provider
        val previous = loadCheckpointForRound(evidence.receipts, providerEvidenceDigest)
        val reconciled = reconciledCallIds()
        val sameEvidence = if (previous?.get("state") == JsonPrimitive("WAITING_PROVIDER_LIMIT")) {
            previous["provider_evidence_digest"] == JsonPrimitive(providerEvidenceDigest)
        } else {
            previous?.get("evidence_digest") == JsonPrimitive(evidenceDigest)
        }
        val revised = previous != null && (previous["continuation_revision"] ?: JsonPrimitive("")) != JsonPrimitive(continuationRevision)
        if (sameEvidence &&
            !revised &&
            previous?.get("resume_allowed") == JsonPrimitive(false) &&
            previous["state"] in setOf(JsonPrimitive("OUTPUT_LIMIT"), JsonPrimitive("INTERRUPTED"))
        ) {
            emit(
                ProviderEvent.Failed(
                    "The saved provider request requires its recorded recovery action before continuation. Saved text and tool receipts remain available.",
                    errorCode = (previous["failure_code"] as? JsonPrimitive)?.contentOrNull ?: "OUTPUT_CONTINUATION_UNAVAILABLE",
                    taskOutcome = "BLOCKED",
                    failureDetails = previous["failure_details"] as? JsonObject
                )
            )
            return@flow
        }
        if (sameEvidence && previous?.get("state") == JsonPrimitive("BLOCKED_NO_PROGRESS") && !revised) {
            emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_PROGRESS: The saved draft is blocked because its unchanged continuation added no new work. Change the request or supported output configuration before resuming.", errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS", taskOutcome = "BLOCKED"))
            return@flow
        }
        val handoff = previous?.takeIf {
            it["state"] in GitHubToolHandoff.states
        }
        if (handoff != null && !handoffResolved(handoff)) {
            emit(
                ProviderEvent.Failed(
                    "OUTPUT_CONTINUATION_PENDING_TOOLS: ${GitHubToolHandoff.pending(handoff).size} saved invocation(s) still require a matching terminal receipt or outcome reconciliation. Completed actions were retained and were not dispatched again.",
                    errorCode = "OUTPUT_CONTINUATION_PENDING_TOOLS",
                    taskOutcome = "BLOCKED",
                    failureDetails = buildJsonObject {
                        put("failed_stage", "tool_reconciliation")
                        put("pending_invocation_fingerprints", JsonArray(GitHubToolHandoff.pending(handoff).map(::JsonPrimitive)))
                        put("requires_reconciliation", true)
                        put("resume_allowed", false)
                        put("retryable_without_new_input", false)
                        put("user_input_required", false)
                        put("next_action", "RECONCILE_TOOL_RECEIPTS")
                        put("dispatched", false)
                    }
                )
            )
            return@flow
        }
        // A normal first tool round has no answer draft to continue. Its next
        // round must still use the original session with the now-confirmed results.
        // A handoff made by an interrupted continuation can be reasoning-only;
        // retain its explicit origin even when its visible draft is empty.
        val restored = handoff?.takeIf {
            (it["draft"] as? JsonPrimitive)?.contentOrNull?.isNotEmpty() == true ||
                it["handoff_continuation"] == JsonPrimitive(true)
        } ?: previous?.takeIf {
            sameEvidence &&
                (it["resume_allowed"] != JsonPrimitive(false) && (it["state"] == JsonPrimitive("OUTPUT_LIMIT") || it["resume_allowed"] == JsonPrimitive(true)) || revised && it["state"] == JsonPrimitive("BLOCKED_NO_PROGRESS"))
        }
        var draft = (restored?.get("draft") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val draftLineage = dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineageAccumulator()
        if (draft.isNotEmpty()) draftLineage.append(dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.RETAINED_CONTENT))
        var attempts = if (revised) 0 else (restored?.get("continuations") as? JsonPrimitive)?.intOrNull ?: 0
        require(draft.length <= maxCharacters && attempts in 0..maxContinuations) { "Saved GitHub continuation is invalid; no generation was dispatched." }
        var active = delegate
        var needsContinuation = restored != null
        var claimContinuation = restored != null &&
            handoff == null &&
            (restored["resume_kind"] == JsonPrimitive("OUTPUT_CONTINUATION") || restored["resume_kind"] == null && restored["state"] == JsonPrimitive("OUTPUT_LIMIT"))
        var interruptedContinuation = restored != null && handoff == null && !claimContinuation
        var interruptionAttempts = if (previous?.get("recovery_revision") == JsonPrimitive(recoveryRevision)) {
            (previous["interruption_attempts"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, GitHubTaskRecovery.MAX_CONNECTION_RECOVERIES) ?: 0
        } else {
            0
        }
        val pendingApproval = dev.chungjungsoo.gptmobile.data.github.GitHubPendingApproval.waiting(exchanges)
        val unresolvedOutcome = exchanges.any { exchange -> exchange.results.any { result -> result.callId !in reconciled && (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes || result.executionReceipt?.get("requires_reconciliation") == JsonPrimitive(true) || GatewayOutcomeRecovery.requiresReconciliation("", result.errorCode)) } }
        val receipts = continuationReceiptBoundary(previous, evidence.receipts, reconciled)
        val receiptBoundary = receipts.completed
        val settledCallIds = receipts.settledCallIds

        suspend fun save(state: String, text: String = draft, resumable: Boolean = state == "OUTPUT_LIMIT", pendingCalls: List<ProviderEvent.ToolCall> = emptyList(), failure: ProviderEvent.Failed? = null): JsonObject = persistCheckpoint(
            state,
            text,
            resumable,
            pendingCalls,
            failure,
            evidenceDigest,
            providerEvidenceDigest,
            attempts,
            interruptionAttempts,
            receiptBoundary,
            settledCallIds,
            claimContinuation,
            pendingApproval,
            needsContinuation
        )

        if (pendingApproval || unresolvedOutcome) {
            save(if (pendingApproval) "WAITING_FOR_APPROVAL" else "RECONCILIATION_REQUIRED", resumable = false)
            emit(
                ProviderEvent.Failed(
                    if (pendingApproval) "Waiting for approval of the existing staged patch. Open its Approve & Commit card; completed results and the fixture are retained." else "A saved remote operation has an uncertain outcome. Reconcile it before continuing; no model request or tool action was repeated.",
                    errorCode = if (pendingApproval) "WAITING_FOR_APPROVAL" else "RECONCILIATION_REQUIRED",
                    taskOutcome = "BLOCKED",
                    failureDetails = buildJsonObject {
                        put("failed_stage", "tool_continuation")
                        put("dispatched", false)
                        put("user_input_required", pendingApproval)
                        put("requires_reconciliation", unresolvedOutcome)
                    }
                )
            )
            return@flow
        }

        while (true) {
            if (needsContinuation) {
                if (interruptedContinuation && interruptionAttempts >= GitHubTaskRecovery.MAX_CONNECTION_RECOVERIES) {
                    save("WAITING_FOR_CONNECTION", resumable = true)
                    emit(
                        ProviderEvent.Failed(
                            "The bounded continuation attempt did not finish. Saved results remain available; resume after the connection or provider condition changes.",
                            errorCode = "OUTPUT_CONTINUATION_UNAVAILABLE",
                            taskOutcome = "PARTIAL",
                            failureDetails = buildJsonObject {
                                put("recovery_state", "WAITING_FOR_CONNECTION")
                                put("resume_required", true)
                                put("request_replay_allowed", false)
                            }
                        )
                    )
                    return@flow
                }
                if (claimContinuation && attempts >= maxContinuations) {
                    save("OUTPUT_LIMIT")
                    emit(
                        ProviderEvent.Failed(
                            "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED: The saved GitHub draft and operation receipts are retained after $attempts output-length continuation requests. Tool rounds have a separate allowance. Narrow the remaining request or increase the supported output capacity.",
                            errorCode = "OUTPUT_CONTINUATION_BUDGET_EXHAUSTED",
                            taskOutcome = "BLOCKED",
                            failureDetails = buildJsonObject {
                                put("failed_stage", "final_synthesis")
                                put("stop_reason", "OUTPUT_LENGTH_CONTINUATION_LIMIT")
                                put("continuations_used", attempts)
                                put("continuation_limit", maxContinuations)
                                put("dispatched", false)
                                put("retryable", false)
                                put("resume_allowed", false)
                                put("user_input_required", true)
                                put("next_action", "UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST")
                            }
                        )
                    )
                    return@flow
                }
                if (claimContinuation) attempts++
                if (interruptedContinuation) interruptionAttempts++
                // Claim before dispatch so process death cannot replenish the allowance.
                save(if (claimContinuation) "OUTPUT_LIMIT" else "GENERATING", resumable = true)
                emit(ProviderEvent.Notice(if (claimContinuation) "Continuing the saved GitHub draft ($attempts/$maxContinuations); unfinished operations keep their authorized tools." else "Resuming the saved GitHub draft with the confirmed tool results.", false))
                active = try {
                    reopen(draft, exchanges, tools)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_UNAVAILABLE: The saved GitHub draft could not fit the current context or reach its provider. Draft and operation receipts remain available.", errorCode = "OUTPUT_CONTINUATION_UNAVAILABLE", taskOutcome = "PARTIAL"))
                    return@flow
                }
                check(!active.handlesToolsInternally) { "GitHub continuation requires client-owned tools; reconcile server-owned actions before resuming." }
            } else {
                // The manifest binds profile/repository, receipt identities and even an
                // empty visible draft before the provider can emit reasoning or lose the process.
                save("GENERATING", resumable = true)
            }
            val generated = collectSegment(active, tools, if (needsContinuation) emptyList() else exchanges, draft) { state, text, resumable ->
                save(state, text, resumable)
            }
            val calls = generated.calls
            val completed = generated.completed
            val finishReason = generated.finishReason
            val failure = generated.failure
            val receivedToolResult = generated.receivedToolResult
            val novel = if (needsContinuation) continuationSuffix(draft, generated.text) else generated.text
            if (novel.isNotEmpty()) draftLineage.append(generated.lineage)
            draft += novel
            val limited = finishReason?.lowercase() in setOf("length", "max_tokens", "output_limit") || failure?.let { isProviderOutputLimitFailure(it.message) } == true
            val continuationPermitted = failure?.errorCode?.uppercase(java.util.Locale.ROOT) !in dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure.codes &&
                failure?.failureDetails?.get("retryable") != JsonPrimitive(false) &&
                failure?.failureDetails?.get("resume_allowed") != JsonPrimitive(false) &&
                failure?.taskOutcome !in setOf("BLOCKED", "CONFIGURATION_INVALIDATED", "WAITING_FOR_APPROVAL")
            val safeBoundary = !receivedToolResult &&
                calls.isEmpty() &&
                failure?.providerLimit == null &&
                failure?.errorCode?.startsWith("TOOL_PROTOCOL_") != true &&
                !GatewayOutcomeRecovery.requiresReconciliation(failure?.message.orEmpty(), failure?.errorCode) &&
                (failure?.transportComplete == true || failure == null && completed) &&
                continuationPermitted
            if (limited && safeBoundary && needsContinuation && novel.isBlank()) {
                save("BLOCKED_NO_PROGRESS")
                emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_PROGRESS: The provider exhausted its output without adding to the saved draft. Change the request or supported output capacity before resuming.", errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS", taskOutcome = "BLOCKED"))
                return@flow
            }
            if (limited && safeBoundary && draft.isNotBlank() && novel.isNotBlank()) {
                needsContinuation = true
                claimContinuation = true
                interruptedContinuation = false
                save("OUTPUT_LIMIT")
                continue
            }
            if (failure != null || limited || !completed) {
                val mayResumeText = !receivedToolResult &&
                    calls.isEmpty() &&
                    failure?.errorCode?.startsWith("TOOL_PROTOCOL_") != true &&
                    failure?.errorCode !in ToolResultEnvelope.unknownOutcomeCodes &&
                    continuationPermitted &&
                    !GatewayOutcomeRecovery.requiresReconciliation(failure?.message.orEmpty(), failure?.errorCode)
                val limit = failure?.providerLimit
                val binding = (checkpointContext["task_binding"] as? JsonPrimitive)?.contentOrNull
                if (limit != null &&
                    (limit.knownNotAccepted || limit.checkpointContinuationAllowed) &&
                    mayResumeText &&
                    binding?.matches(Regex("[a-f0-9]{64}")) == true
                ) {
                    if (interruptedContinuation) interruptionAttempts--
                    if (claimContinuation && limit.knownNotAccepted) attempts--
                    val saved = save("WAITING_PROVIDER_LIMIT", resumable = true, failure = failure)
                    val checkpoint = dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint(binding, GitHubEvidenceBook.digest(saved.toString()))
                    emit(requireNotNull(failure).copy(providerLimit = limit.copy(gitHubCheckpoint = checkpoint)))
                    return@flow
                }
                if (!limited || !continuationPermitted) claimContinuation = false
                save(if (limited && safeBoundary) "OUTPUT_LIMIT" else "INTERRUPTED", resumable = mayResumeText, failure = failure)
                // Incomplete frames stay with the runner's protocol repair; no buffered call is dispatched.
                emit(failure ?: ProviderEvent.Failed("The GitHub draft did not reach a complete response boundary. Saved text and receipts remain available.", errorCode = if (limited) "OUTPUT_LIMIT" else "INCOMPLETE_PROVIDER_STREAM"))
                return@flow
            }
            if (needsContinuation && calls.isEmpty() && novel.isBlank()) {
                save("BLOCKED_NO_PROGRESS")
                emit(ProviderEvent.Failed("OUTPUT_CONTINUATION_NO_PROGRESS: The continuation repeated the saved draft without new work. Saved evidence remains available.", errorCode = "OUTPUT_CONTINUATION_NO_PROGRESS", taskOutcome = "BLOCKED"))
                return@flow
            }
            save(if (calls.isEmpty()) "COMPLETED" else "TOOL_HANDOFF", pendingCalls = calls)
            if (calls.isEmpty() && draft.isNotEmpty()) emit(ProviderEvent.TextDelta(draft, draftLineage.lineage))
            calls.forEach { emit(it) }
            emit(ProviderEvent.Completed)
            return@flow
        }
    }

    /**
     * Keep provider collection out of the continuation state machine's generated
     * method. Besides avoiding the ART/JVM method-size cliff, this boundary makes
     * the persistence rule explicit: a partial segment is durably checkpointed
     * before cancellation or another exception escapes.
     */
    private suspend fun FlowCollector<ProviderEvent>.collectSegment(
        active: AgentProviderSession,
        tools: List<AgentToolDefinition>,
        exchanges: List<AgentToolExchange>,
        draft: String,
        checkpoint: suspend (state: String, text: String, resumable: Boolean) -> Unit
    ): GeneratedSegment {
        val segment = StringBuilder()
        val publicProgress = GitHubPublicProgress(publishProgress)
        val segmentLineage = dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineageAccumulator()
        val calls = mutableListOf<ProviderEvent.ToolCall>()
        val admission = ToolRoundAdmission()
        var completed = false
        var finishReason: String? = null
        var failure: ProviderEvent.Failed? = null
        var receivedToolResult = false
        var savedAt = 0
        try {
            active.streamRound(tools, exchanges).stopAtUnknownGatewayOutcome().withSummedRequestUsage().collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        check(draft.length.toLong() + segment.length + event.text.length <= maxCharacters) { "GitHub draft exceeded its retention allowance; completed evidence remains saved." }
                        segment.append(publicProgress.accept(event))
                        segmentLineage.append(event.lineage)
                        if (segment.length - savedAt >= CHECKPOINT_CHARACTER_INTERVAL) {
                            checkpoint("WRITING", draft + continuationSuffix(draft, segment.toString()), calls.isEmpty() && !receivedToolResult)
                            savedAt = segment.length
                        }
                    }
                    is ProviderEvent.ToolCall -> {
                        if (!admission.admit(event)) throw ToolBatchOversizeException()
                        calls += event
                        checkpoint("TOOL_CALL_BUFFERED", draft + continuationSuffix(draft, segment.toString()), false)
                    }
                    is ProviderEvent.ToolResult -> {
                        receivedToolResult = true
                        checkpoint("TOOL_RESULT_OBSERVED", draft + continuationSuffix(draft, segment.toString()), false)
                        emit(event)
                    }
                    is ProviderEvent.Usage -> {
                        finishReason = event.finishReason ?: finishReason
                        emit(event)
                    }
                    is ProviderEvent.Failed -> if (failure == null) failure = event
                    ProviderEvent.Completed -> completed = true
                    else -> emit(event)
                }
            }
            segment.append(publicProgress.finish())
        } catch (error: Exception) {
            withContext(NonCancellable) {
                try {
                    checkpoint(
                        if (error is CancellationException) "CANCELLED" else "INTERRUPTED",
                        draft + continuationSuffix(draft, segment.toString()),
                        calls.isEmpty() && !receivedToolResult && !GatewayOutcomeRecovery.requiresReconciliation(error.message.orEmpty())
                    )
                } catch (storage: Exception) {
                    error.addSuppressed(storage)
                }
            }
            throw error
        }
        return GeneratedSegment(segment.toString(), segmentLineage.lineage, calls, completed, finishReason, failure, receivedToolResult)
    }

    private suspend fun loadCheckpointForRound(
        replayReceipts: List<ToolHandoffReceipt>,
        providerEvidenceDigest: String
    ): JsonObject? = checkpointMutex.withLock {
        val saved = readCheckpoint() ?: currentCheckpoint
        if (!providerCheckpointValidated && expectedProviderCheckpoint != null) {
            check(saved != null && expectedProviderCheckpoint.matches(saved) && saved["provider_evidence_digest"] == JsonPrimitive(providerEvidenceDigest)) {
                "PROVIDER_WAIT_CHECKPOINT_CHANGED"
            }
            providerCheckpointValidated = true
        }
        if (saved == null || saved["state"] !in GitHubToolHandoff.states) {
            saved
        } else {
            val durable = durableToolReceipts(GitHubToolHandoff.pending(saved))
            val updated = GitHubToolHandoff.reconcile(saved, replayReceipts + durable, reconciledCallIds())
            if (updated != saved) saveCheckpoint(updated)
            currentCheckpoint = updated
            updated
        }
    }

    private suspend fun continuationReceiptBoundary(
        previous: JsonObject?,
        current: List<ToolHandoffReceipt>,
        reconciled: Set<String>
    ): ContinuationReceiptBoundary = withContext(Dispatchers.Default) {
        val retained = (previous?.get("completed_results") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .associateByTo(linkedMapOf()) { (it["call_id"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        current.forEach { receipt ->
            val projection = receipt.json()
            val merged = retained[receipt.callId].orEmpty() + projection
            retained[receipt.callId] = JsonObject(merged + ("source_hash_available" to JsonPrimitive(merged["source_result_sha256"] != null)))
        }
        val settled = (previous?.get("reconciled_call_ids") as? JsonArray).orEmpty()
            .mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.contentOrNull } + reconciled
        ContinuationReceiptBoundary(JsonArray(retained.values.toList()), settled)
    }

    private suspend fun persistCheckpoint(
        state: String,
        text: String,
        resumable: Boolean,
        pendingCalls: List<ProviderEvent.ToolCall>,
        failure: ProviderEvent.Failed?,
        evidenceDigest: String,
        providerEvidenceDigest: String,
        attempts: Int,
        interruptionAttempts: Int,
        receiptBoundary: JsonArray,
        settledCallIds: Set<String>,
        claimContinuation: Boolean,
        pendingApproval: Boolean,
        needsContinuation: Boolean
    ): JsonObject {
        val checkpoint = buildJsonObject {
            put("schema", "github.output-checkpoint.v1")
            put("evidence_digest", evidenceDigest)
            if (state == "WAITING_PROVIDER_LIMIT") put("provider_evidence_digest", providerEvidenceDigest)
            put("state", state)
            put("draft", text)
            put("continuations", attempts)
            put("continuation_revision", continuationRevision)
            put("recovery_revision", recoveryRevision)
            put("interruption_attempts", interruptionAttempts)
            put("context", checkpointContext)
            put("completed_results", receiptBoundary)
            put("reconciled_call_ids", JsonArray(settledCallIds.sorted().map(::JsonPrimitive)))
            put(
                "resume_kind",
                when {
                    failure?.errorCode.equals("CONVERSATION_CONFLICT", ignoreCase = true) -> "ACTIVE_GATEWAY_JOB"
                    claimContinuation -> "OUTPUT_CONTINUATION"
                    receiptBoundary.isNotEmpty() -> "AFTER_TOOL_RESULTS"
                    else -> "PROVIDER_REQUEST"
                }
            )
            put("resume_allowed", resumable)
            put("tool_actions_replayed", false)
            put("draft_kind", if (text.isBlank()) "NO_VISIBLE_DRAFT" else "UNVERIFIED_VISIBLE_DRAFT")
            put("user_input_required", pendingApproval || failure?.failureDetails?.get("user_input_required") == JsonPrimitive(true))
            if (pendingApproval) put("blocking_condition", "WAITING_FOR_APPROVAL")
            if (pendingCalls.isNotEmpty()) {
                put("pending_calls", JsonArray(pendingCalls.map { JsonPrimitive(callDigest(it)) }))
                put("handoff_continuation", needsContinuation)
            }
            failure?.let {
                it.errorCode?.let { code -> put("failure_code", code) }
                it.failureDetails?.let { details -> put("failure_details", details) }
                (it.failureDetails?.get("next_action") ?: it.failureDetails?.get("recovery_action"))?.let { action -> put("next_action", action) }
            }
        }
        saveCheckpoint(checkpoint)
        currentCheckpoint = checkpoint
        return checkpoint
    }

    /** Hashing raw retained evidence is CPU work and must not run on the UI collector. */
    private suspend fun continuationEvidenceDigests(exchanges: List<AgentToolExchange>): ContinuationEvidenceDigests = withContext(Dispatchers.Default) {
        val full = GitHubEvidenceBook.digest(
            exchanges.joinToString("\n") { exchange ->
                exchange.calls.joinToString { "${it.callId}:${it.name}:${it.arguments}" } + "\n" + exchange.results.joinToString { "${it.callId}:${it.isError}:${it.dispatched}:${it.content}" }
            }
        )
        val receipts = exchanges.flatMap { exchange ->
            exchange.calls.mapNotNull { call ->
                val result = exchange.results.firstOrNull { it.callId == call.callId } ?: return@mapNotNull null
                ToolHandoffReceipt.from(call, result, originalAvailable = false)
            }
        }
        val provider = GitHubEvidenceBook.digest(
            JsonArray(
                receipts.sortedBy { it.invocationFingerprint }.map { receipt ->
                    buildJsonObject {
                        put("invocation", receipt.invocationFingerprint)
                        put("result", receipt.sourceResultSha256 ?: receipt.projectionSha256)
                        put("dispatched", receipt.dispatched)
                        put("requires_reconciliation", receipt.requiresReconciliation)
                        receipt.errorCode?.let { put("error", it) }
                    }
                }
            ).toString()
        )
        ContinuationEvidenceDigests(full, provider, receipts)
    }

    // Reconciliation above considers both replay and durable receipts. Rechecking
    // replay alone here would let stale success override an unknown durable effect.
    private fun handoffResolved(checkpoint: JsonObject): Boolean =
        checkpoint["state"] == JsonPrimitive("TOOL_RESULTS_READY") &&
            (checkpoint["pending_calls"] as? JsonArray)?.isEmpty() == true

    // Newly emitted provider calls have not crossed the runner's trusted routing
    // boundary. Hash their original fields; a provider-supplied fingerprint cannot
    // select the receipt identity that survives authorized aliasing and compaction.
    private fun callDigest(call: ProviderEvent.ToolCall): String = GitHubEvidenceBook.digest("${call.callId}:${call.name}:${call.arguments}")

    private data class GeneratedSegment(
        val text: String,
        val lineage: dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage,
        val calls: List<ProviderEvent.ToolCall>,
        val completed: Boolean,
        val finishReason: String?,
        val failure: ProviderEvent.Failed?,
        val receivedToolResult: Boolean
    )

    private data class ContinuationEvidenceDigests(
        val full: String,
        val provider: String,
        val receipts: List<ToolHandoffReceipt>
    )

    private data class ContinuationReceiptBoundary(
        val completed: JsonArray,
        val settledCallIds: Set<String>
    )

    private companion object {
        const val CHECKPOINT_CHARACTER_INTERVAL = 4096
    }
}
