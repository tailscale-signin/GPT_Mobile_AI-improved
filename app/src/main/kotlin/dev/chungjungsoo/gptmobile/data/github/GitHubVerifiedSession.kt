package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All providers keep drafts private. Only code-rendered receipt facts and labeled interpretations are published. */
internal class GitHubVerifiedSession(
    private val delegate: AgentProviderSession,
    private val evidence: GitHubEvidenceBook,
    private val task: String,
    private val inputLimit: () -> Int,
    private val openVerifier: suspend (String) -> AgentProviderSession,
    private val validatePublication: suspend () -> Unit,
    private val progress: suspend (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val privateReviewCandidate: () -> String? = { null },
    private val authorizedTools: List<AgentToolDefinition> = emptyList(),
    private val lifecycle: (dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind) -> Unit = {},
    private val resumeCommitSha: String? = null,
    private val resumeRequestKey: String = task,
    private val gatewayDiagnostic: (suspend () -> String)? = null,
    private val onPublication: suspend (JsonObject) -> Unit = {},
    private val remainingToolCalls: () -> Int? = { null },
    private val currentRequest: String = task,
    private val openExecutionContinuation: (suspend (String) -> AgentProviderSession)? = null,
    private val resumeClaimExtraction: Boolean = false
) : AgentProviderSession by delegate {
    override val handlesToolResultRecovery: Boolean = GitHubTestChecklist.integration(task) ||
        GitHubTaskContinuity.reportOnly(currentRequest) ||
        GitHubTaskContinuity.diagnostic(currentRequest)
    private var lastChecklist: JsonObject? = null
    private var initialReceiptIds: Set<String>? = null
    private var initiallyVerifiedComplete = false
    private var executionCorrectionAttempted = false
    private var correctionProgress: Set<String> = emptySet()
    private var progressCorrections = 0
    private var transportWaited = false
    private class ProgressCheckpointFailure(cause: Exception, val originalFailure: ProviderEvent.Failed?) : Exception(cause)

    private fun failureCauses(error: Throwable): Sequence<Throwable> =
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }.take(16)

    private suspend fun retainProgress(tools: List<AgentToolDefinition>, failure: ProviderEvent.Failed? = null) {
        try {
            withContext(computationDispatcher) {
                evidence.retainProgress(task, authorizedTools.ifEmpty { tools }, failure?.let { it.errorCode ?: "MODEL_GENERATION_FAILED" }, failure, remainingToolCalls())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val accessFailure = failureCauses(error).firstOrNull {
                it is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException ||
                    it is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged ||
                    it is dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied
            }
            if (accessFailure != null) {
                if (accessFailure is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException &&
                    failure != null &&
                    dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails).category in setOf("TOOL_OUTCOME_UNKNOWN", "GATEWAY_OUTCOME_UNKNOWN")
                ) {
                    throw ProgressCheckpointFailure(error, failure)
                }
                throw accessFailure
            }
            throw ProgressCheckpointFailure(error, failure)
        }
    }

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val intent = dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract.from(GitHubTaskContinuity.request(task))
        if (intent.conversational) {
            emit(ProviderEvent.TextDelta(intent.acknowledgement(), dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
            emit(ProviderEvent.Completed)
            return@flow
        }
        exchanges.forEach { exchange -> exchange.results.forEach { result -> exchange.calls.firstOrNull { it.callId == result.callId }?.let { evidence.observe(it, result) } } }
        if (!evidence.complete()) {
            emitAll(publish(null, failureCode = "GITHUB_EVIDENCE_RETENTION_INCOMPLETE"))
            return@flow
        }
        if (GitHubTaskContinuity.diagnostic(currentRequest)) {
            validatePublication()
            val (receipts, _) = evidence.snapshot()
            emit(ProviderEvent.TextDelta(GitHubTaskContinuity.explain(evidence.diagnostic(), receipts), dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
            emit(ProviderEvent.Completed)
            return@flow
        }
        if (GitHubTaskContinuity.reportOnly(currentRequest)) {
            // Delivery of retained results cannot restart the checklist or replay writes.
            emitAll(deliverRetainedReport())
            return@flow
        }
        if (evidence.remainingTaskMillis() == 0L) {
            emitAll(
                publish(
                    null,
                    failureCode = "GITHUB_TASK_INCOMPLETE",
                    originalFailure = GitHubExecutionLimits.expiredTaskFailure()
                )
            )
            return@flow
        }
        if (isGatewayIntegrationTask(task)) {
            validatePublication()
            val report = gatewayDiagnostic?.invoke() ?: "Gateway integration: unavailable. Configure an engineering gateway connection in GitHub settings."
            validatePublication()
            emit(ProviderEvent.TextDelta(report, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
            emit(ProviderEvent.Completed)
            return@flow
        }
        if (initialReceiptIds == null) {
            initialReceiptIds = evidence.snapshot().first.keys.toSet()
            correctionProgress = GitHubExecutionContinuation.progressKeys(evidence.snapshot().first)
            initiallyVerifiedComplete = evidence.diagnostic()?.get("completion_verified") == kotlinx.serialization.json.JsonPrimitive(true)
        }
        val approvalReceipts = evidence.snapshot().first
        val pendingApprovals = GitHubPendingApproval.snapshot(approvalReceipts)
        if (pendingApprovals.isNotEmpty()) {
            GitHubApprovalContinuation.next(resumeRequestKey, tools, approvalReceipts, initialReceiptIds.orEmpty())?.let { call ->
                progress("Checking the existing saved approval; no commit is being repeated")
                emit(call)
                emit(ProviderEvent.Completed)
                return@flow
            }
            val saved = evidence.retainedAnswer()
            if (saved != null && GitHubTaskContinuity.control(currentRequest)) {
                validatePublication()
                val (body, verdict) = saved
                val text = "## Saved approval pending\n\nReview the saved approval or publication status to resume.\n\n$body"
                val delivered = evidence.retainVerdict(
                    JsonObject(
                        verdict + buildJsonObject {
                            put("task_outcome", "BLOCKED")
                            put("completion_verified", false)
                            put("pending_approvals", pendingApprovals)
                            put("response_delivered", true)
                            put("response_sha256", GitHubEvidenceBook.digest(text))
                            put("delivery_mode", "RETAINED_APPROVAL_CHECKPOINT")
                        }
                    )
                )
                onPublication(delivered)
                emit(ProviderEvent.TextDelta(text, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.RETAINED_CONTENT)))
                emit(ProviderEvent.Failed("Review the saved change to continue.", errorCode = "WAITING_FOR_APPROVAL", transportComplete = true, taskOutcome = "BLOCKED"))
            } else {
                emitAll(publish(null))
            }
            return@flow
        }
        if (resumeCommitSha == null && (resumeClaimExtraction || GitHubTaskContinuity.control(currentRequest))) {
            val (receipts, epoch) = evidence.snapshot()
            val saved = evidence.retainedClaimExtraction(claimExtractionContext(receipts, epoch))
            val draft = (saved?.get("draft") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            if (saved?.get("pending") == JsonPrimitive(true) && draft != null) {
                emitAll(publish(draft))
                return@flow
            }
        }
        retainProgress(tools)
        resumeCommitSha?.let { sha ->
            GitHubCommitContinuation.next(sha, resumeRequestKey, tools, evidence.snapshot().first)?.let { call ->
                progress("Reconciling the approved commit and its checks; completed writes are retained")
                emit(call)
                emit(ProviderEvent.Completed)
                return@flow
            }
        }
        if (GitHubCiObservationReport.matches(task)) {
            val (receipts, epoch) = evidence.snapshot()
            val observed = withContext(computationDispatcher) { GitHubCiObservationReport.render(task, receipts, now(), epoch) }
            if (observed != null) {
                // Complete this bounded factual diagnosis directly from retained
                // evidence; another model request cannot discover a missing failure.
                emitAll(publish(null))
                return@flow
            }
        }
        if (GitHubTestChecklist.integration(task)) {
            val receipts = evidence.snapshot().first
            val (checkpoint, planned, missing) = withContext(computationDispatcher) {
                Triple(
                    GitHubTestChecklist.checkpoint(task, receipts, authorizedTools, remainingToolCalls()),
                    GitHubTestChecklist.nextCall(task, tools, receipts, remainingToolCalls()),
                    authorizedTools.filter { definition -> definition.name.startsWith("github_") && tools.none { it.name == definition.name } && receipts.values.none { it["tool"] == kotlinx.serialization.json.JsonPrimitive(definition.name) } }.take(12)
                )
            }
            val discovery = tools.firstOrNull { it.name == "discover_available_tools" }
            val catalogNext = GitHubAuthorizedCatalog.next(tools, receipts, authorizedTools)
            val catalogComplete = GitHubAuthorizedCatalog.snapshot(receipts, authorizedTools)["complete"] == kotlinx.serialization.json.JsonPrimitive(true)
            val discoveryArgs = buildJsonObject { put("names", JsonArray(missing.map { kotlinx.serialization.json.JsonPrimitive(it.name) })) }
            val discoveryAlreadyTried = receipts.values.any { it["tool"] == kotlinx.serialization.json.JsonPrimitive("discover_available_tools") && it["arguments"] == discoveryArgs }
            val acquisitionAllowed = GitHubChecklistBudget.remaining(receipts, remainingToolCalls())?.let { it > GitHubChecklistBudget.VERIFICATION_RESERVE } != false
            val next = when {
                planned != null && planned.name.substringBefore("__") == "github_commit" && planned.arguments["action"] in setOf(kotlinx.serialization.json.JsonPrimitive("delete_branch"), kotlinx.serialization.json.JsonPrimitive("delete_fixture_branch")) -> planned
                acquisitionAllowed && catalogNext != null -> catalogNext
                acquisitionAllowed && catalogComplete && discovery != null && planned == null && missing.isNotEmpty() && !discoveryAlreadyTried -> ProviderEvent.ToolCall("checklist-discovery-" + GitHubEvidenceBook.digest(discoveryArgs.toString()).take(20), discovery.name, discoveryArgs)
                else -> planned
            }
            if (checkpoint != lastChecklist) {
                if (receipts.isNotEmpty()) withContext(computationDispatcher) { evidence.retainChecklist(checkpoint) }
                lastChecklist = checkpoint
                if (receipts.isNotEmpty()) {
                    progress(GitHubTestChecklist.progressSummary(checkpoint, next))
                }
            }
            if (next != null) {
                val retryAt = GitHubTransportRecovery.retryNotBefore(receipts.values)
                val wait = retryAt?.minus(now())?.coerceAtLeast(0) ?: 0L
                if (wait > 0 && next.name != "discover_available_tools" && "view_cursor" !in next.arguments) {
                    if (transportWaited || wait > GitHubTransportRecovery.MAX_WAIT_MILLIS || (evidence.remainingTaskMillis()?.let { it <= wait } == true)) {
                        emitAll(
                            publish(
                                null,
                                originalFailure = ProviderEvent.Failed(
                                    "GitHub transport is cooling down. Resume the saved task after the retry time.",
                                    errorCode = "GITHUB_CIRCUIT_OPEN",
                                    taskOutcome = "BLOCKED",
                                    failureDetails = buildJsonObject {
                                        put("failed_stage", "tool_continuation")
                                        put("retry_after_ms", wait)
                                        put("retry_not_before_ms", retryAt)
                                        put("dispatched", false)
                                    }
                                )
                            )
                        )
                        return@flow
                    }
                    transportWaited = true
                    progress("Waiting for GitHub transport before resuming the saved check")
                    delay(wait)
                    validatePublication()
                }
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                emit(next.copy(callId = next.callId + "-${receipts.size}"))
                emit(ProviderEvent.Completed)
                return@flow
            }
            // Coverage fields come exclusively from the cumulative ledger. No model draft
            // or answer rewrite can supply missing executions or unlock a blocked dependency.
            val acceptanceComplete = (checkpoint["requirements"] as? JsonArray)?.isNotEmpty() == true &&
                (checkpoint["remaining_requirements"] as? JsonArray)?.isEmpty() == true
            val executionBlocker = when {
                acceptanceComplete -> null
                remainingToolCalls() == 0 -> "TOOL_BUDGET_EXHAUSTED"
                tools.isEmpty() && authorizedTools.isEmpty() -> "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE"
                else -> null
            }
            if (executionBlocker == null) {
                emitAll(publish(null))
            } else {
                emitAll(
                    publish(
                        null,
                        failureCode = executionBlocker,
                        originalFailure = ProviderEvent.Failed(
                            if (executionBlocker == "TOOL_BUDGET_EXHAUSTED") {
                                "The accepted tool allowance is exhausted. Saved operation, catalog and fixture receipts are retained. Review the remaining work and explicitly authorize a new allowance before continuing execution."
                            } else {
                                "The current request has no executable tool catalog for the missing verification. Review the exact saved connection, repository scope and tool selection. Saved evidence alone cannot perform the unfinished operations."
                            },
                            errorCode = executionBlocker,
                            taskOutcome = "BLOCKED",
                            failureDetails = buildJsonObject {
                                put("failed_stage", "tool_admission")
                                put("stop_reason", executionBlocker)
                                put("dispatched", false)
                                put("retryable", false)
                            }
                        )
                    )
                )
            }
            return@flow
        }
        if (GitHubStatusRequest.matches(task) && tools.isNotEmpty()) {
            val (receipts, epoch) = evidence.snapshot()
            val next = GitHubStatusRequest.nextCall(tools, receipts, now(), epoch)
            if (next != null) {
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                emit(next)
                emit(ProviderEvent.Completed)
                return@flow
            }
            if (receipts.isNotEmpty()) {
                emitAll(publish(null))
                return@flow
            }
        }
        if (GitHubStatusRequest.matches(task)) {
            val (receipts, _) = evidence.snapshot()
            val hasStatusSnapshot = receipts.values.any { receipt ->
                (receipt["arguments"] as? JsonObject)?.get("action") == kotlinx.serialization.json.JsonPrimitive("status")
            }
            if (hasStatusSnapshot) {
                emitAll(publish(null))
                return@flow
            }
        }
        if (tools.isEmpty() && GitHubTaskCoverage.matches(task) && evidence.snapshot().first.values.any { it["trusted"] == kotlinx.serialization.json.JsonPrimitive(true) }) {
            // Tool coverage is computed from receipts; another prose review cannot
            // establish test execution that the receipts do not contain.
            emitAll(publish(null))
            return@flow
        }
        if (GitHubReviewComparison.matches(task) && remainingToolCalls()?.let { it > 0 } != false) {
            val comparison = withContext(computationDispatcher) { GitHubReviewComparison.plan(task, tools, evidence.snapshot().first) }
            comparison?.nextCall?.let { next ->
                progress("Resolving exact review comparison revisions")
                emit(next)
                emit(ProviderEvent.Completed)
                return@flow
            }
        }
        var round = collectDraft(delegate, tools, exchanges)
        val retained = evidence.snapshot().first
        if (!initiallyVerifiedComplete &&
            !executionCorrectionAttempted &&
            openExecutionContinuation != null &&
            tools.isNotEmpty() &&
            round.completed &&
            !round.failed &&
            !round.calls &&
            round.draft.isNotBlank() &&
            retained.keys == initialReceiptIds &&
            GitHubRequestCompletion.requiresRepositoryEvidence(task) &&
            remainingToolCalls()?.let { it > 0 } != false
        ) {
            executionCorrectionAttempted = true
            progress("The response proposed work without new execution evidence. Resuming the accepted task once with its tools and receipts")
            val prompt = GitHubExecutionContinuation.prompt(task, currentRequest, retained, evidence.diagnostic())
            round = collectDraft(openExecutionContinuation.invoke(prompt), tools, exchanges)
            if (!round.calls && round.completed && !round.failed) {
                // A correction can resume operations, but its raw prose has not passed
                // the selected review pipeline. Publish only the application ledger.
                emitAll(publish(null, failureCode = "GITHUB_TASK_INCOMPLETE"))
                return@flow
            }
        }
        val (draft, calls, completed, failed, providerFailure) = round
        if (failed || !completed) {
            // The runner owns retries and protocol repair. Do not turn a transient
            // failure into completion before that policy has had a chance to run.
            if (!calls && providerFailure?.errorCode?.startsWith("GITHUB_REVIEW_") == true) {
                val candidate = privateReviewCandidate()?.takeIf { it.isNotBlank() }
                val (receipts, _) = evidence.snapshot()
                if (candidate != null && receipts.values.any { it["trusted"] == kotlinx.serialization.json.JsonPrimitive(true) }) {
                    emitAll(publish(candidate, failureCode = providerFailure.errorCode, originalFailure = providerFailure))
                } else {
                    emitAll(retainedFailureResponse(requireNotNull(providerFailure)))
                }
                return@flow
            }
            emit(providerFailure ?: ProviderEvent.Failed("GitHub response ended before verification. Receipts remain available.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
            return@flow
        }
        if (calls || (draft.isBlank() && tools.isNotEmpty())) {
            emit(ProviderEvent.Completed)
            return@flow
        }
        emitAll(publish(draft, continuationTools = tools, continuationExchanges = exchanges))
    }.catch { error ->
        handleCheckpointFailure(error)
    }

    private data class DraftRound(val draft: String, val calls: Boolean, val completed: Boolean, val failed: Boolean, val failure: ProviderEvent.Failed?)

    private fun deliverRetainedReport(): Flow<ProviderEvent> = flow {
        validatePublication()
        val saved = evidence.retainedAnswer()
        if (saved == null) {
            emitAll(publish(null))
            return@flow
        }
        val (body, verdict) = saved
        val text = "## Saved report\n\nThese are the retained observations from the accepted task; no new repository checks were run.\n\n$body"
        emit(ProviderEvent.TextDelta(text, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.RETAINED_CONTENT)))
        val delivered = evidence.retainVerdict(
            JsonObject(
                verdict + buildJsonObject {
                    put("response_delivered", true)
                    put("response_sha256", GitHubEvidenceBook.digest(text))
                    put("delivery_mode", "RETAINED_REPORT")
                }
            )
        )
        onPublication(delivered)
        if (delivered["completion_verified"] == kotlinx.serialization.json.JsonPrimitive(true)) {
            emit(ProviderEvent.Completed)
        } else {
            emit(ProviderEvent.Failed("The saved report was delivered. Its unfinished task requirements remain recorded; no tool operation was repeated.", errorCode = "GITHUB_TASK_INCOMPLETE", transportComplete = true, taskOutcome = "PARTIAL"))
        }
    }

    private suspend fun FlowCollector<ProviderEvent>.collectDraft(session: AgentProviderSession, tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): DraftRound {
        val draft = StringBuilder()
        var calls = false
        var completed = false
        var failed = false
        var providerFailure: ProviderEvent.Failed? = null
        session.streamRound(tools, exchanges).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> {
                    check(draft.length.toLong() + event.text.length <= 1_048_576) { "GitHub verification draft exceeds capacity" }
                    draft.append(event.text)
                }
                is ProviderEvent.ToolCall -> {
                    calls = true
                    emit(event)
                }
                is ProviderEvent.ToolResult -> {
                    evidence.observe(event.call, event.result)
                    retainProgress(tools)
                    emit(event)
                }
                is ProviderEvent.Completed -> completed = true
                is ProviderEvent.Failed -> {
                    failed = true
                    if (providerFailure == null) {
                        providerFailure = event
                        retainProgress(tools, event)
                    }
                }
                is ProviderEvent.CombinedAnswer -> failed = true
                else -> emit(event)
            }
        }
        return DraftRound(draft.toString(), calls, completed, failed, providerFailure)
    }

    private suspend fun FlowCollector<ProviderEvent>.handleCheckpointFailure(error: Throwable) {
        // Flow.catch preserves collector failures and cancellation. A failed
        // progress checkpoint stops work before any further provider/tool action.
        val policy = failureCauses(error)
            .filterIsInstance<dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException>().firstOrNull()
        val original = (error as? ProgressCheckpointFailure)?.originalFailure
        if (original != null) {
            val checkpoint = buildJsonObject {
                put("acceptance_checkpoint", "UNAVAILABLE")
                if (policy != null) {
                    put("checkpoint_failure_code", "DISPATCH_POLICY_CHANGED")
                    put("resume_required", true)
                }
            }
            emit(original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + checkpoint)))
            return
        }
        if (policy != null) {
            // Do not ask the old profile to regenerate or acquire more evidence.
            // Earlier durable receipts remain available to a newly authorized run.
            emit(GitHubTaskLifecycle.configurationInvalidated(policy))
            return
        }
        if (error !is ProgressCheckpointFailure) throw error
        val dispatched = evidence.snapshot().first.values.any { it["dispatched"] == kotlinx.serialization.json.JsonPrimitive(true) }
        emit(
            ProviderEvent.Failed(
                "The GitHub acceptance checkpoint could not be retained. No completion is claimed. Restore local storage and reconcile recorded operations before resuming.",
                errorCode = "GITHUB_EVIDENCE_RETENTION_INCOMPLETE",
                taskOutcome = if (dispatched) "PARTIAL" else "BLOCKED"
            )
        )
    }

    override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = flow {
        if (failure.failureDetails?.get("stop_reason") == kotlinx.serialization.json.JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")) {
            if (GitHubTaskContinuity.diagnostic(currentRequest)) {
                validatePublication()
                val (receipts, _) = evidence.snapshot()
                emit(ProviderEvent.TextDelta(GitHubTaskContinuity.explain(evidence.diagnostic(), receipts), dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
                emit(ProviderEvent.Completed)
                return@flow
            }
            if (GitHubTaskContinuity.reportOnly(currentRequest)) {
                emitAll(deliverRetainedReport())
                return@flow
            }
        }
        if (failure.errorCode == "DISPATCH_POLICY_CHANGED" || failure.message.startsWith("DISPATCH_POLICY_CHANGED:")) {
            emit(failure.copy(errorCode = "DISPATCH_POLICY_CHANGED", taskOutcome = "CONFIGURATION_INVALIDATED"))
        } else if (dev.chungjungsoo.gptmobile.data.agent.GitHubTaskRecovery.isConnectionUnavailable(failure.message)) {
            // A failed connection is not a claim-verification failure. Keep its actionable cause.
            emit(failure.copy(errorCode = failure.errorCode ?: "LOCAL_SERVICE_UNREACHABLE"))
        } else {
            val (receipts, _) = evidence.snapshot()
            if (receipts.values.none { it["trusted"] == kotlinx.serialization.json.JsonPrimitive(true) }) {
                // No GitHub work was observed. A provider denial is a failed request,
                // not partial task verification, and must retain its recovery metadata.
                if (failure.errorCode?.startsWith("GITHUB_REVIEW_") == true) {
                    validatePublication()
                    emit(
                        ProviderEvent.TextDelta(
                            "The answer review could not finish. The draft remains unverified, and no GitHub completion is claimed. " +
                                "Saved operations are retained. Retry resumes only the bounded review recovery; it does not repeat a commit.\n\nRecorded failure: `${failure.errorCode}`.",
                            dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, failure.invocationId)
                        )
                    )
                }
                emit(failure)
            } else {
                emitAll(publish(null, failureCode = failure.errorCode, originalFailure = failure))
            }
        }
    }

    private fun publish(
        draft: String?,
        failureCode: String? = null,
        originalFailure: ProviderEvent.Failed? = null,
        continuationTools: List<AgentToolDefinition> = emptyList(),
        continuationExchanges: List<AgentToolExchange> = emptyList()
    ): Flow<ProviderEvent> = flow {
        val verificationStarted = System.nanoTime()
        val publication = try {
            validatePublication()
            val (receipts, epoch) = evidence.snapshot()
            val ciObservation = withContext(computationDispatcher) { GitHubCiObservationReport.render(task, receipts, now(), epoch) }
            val status = if (GitHubStatusRequest.matches(task)) {
                withContext(computationDispatcher) { GitHubEvidencePolicy.status(receipts, now(), epoch, task) }
            } else {
                null
            }
            val coverage = if (GitHubTaskCoverage.matches(task)) {
                withContext(computationDispatcher) {
                    GitHubEvidencePolicy.testing(receipts, now(), epoch, task, authorizedTools, remainingToolCalls())
                }
            } else {
                null
            }
            val requests = if (draft == null || status != null || coverage != null || ciObservation != null) {
                null
            } else {
                progress("Verifying factual claims against exact tool receipts")
                withContext(computationDispatcher) { verificationRequests(draft, receipts) }
            }
            val (renderedText, report) = status ?: coverage ?: ciObservation ?: if (requests == null) partial(receipts, epoch, failureCode ?: "GITHUB_EXTRACTION_UNAVAILABLE") else extract(requireNotNull(draft), requests, receipts, epoch)
            val (checklist, progressDigest) = withContext(computationDispatcher) {
                val checklist = if (coverage != null) GitHubTestChecklist.checkpoint(task, receipts, authorizedTools, remainingToolCalls()) else null
                checklist to checklist?.let { GitHubTestChecklist.progressDigest(task, receipts, authorizedTools) }
            }
            val previous = evidence.diagnostic()
            val previousProgressDigest = GitHubEvidenceBook.publishedChecklistDigest(previous)
            val stalled = progressDigest != null && previousProgressDigest == kotlinx.serialization.json.JsonPrimitive(progressDigest)
            val text = if (stalled) {
                val blockers = (checklist?.get("capabilities") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it["blocker"] != null }
                "No new test evidence or changed blockers since the saved checkpoint. ${receipts.size} receipts retained. " +
                    "Resume is waiting on these dependencies:\n\n" + blockers.distinctBy { it["blocker"] }.joinToString("\n") { "- ${(it["blocker"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()}" }
            } else {
                renderedText
            }
            validatePublication()
            val retention = evidence.retentionStatus()
            val retentionComplete = retention["evidence_retention_complete"] == kotlinx.serialization.json.JsonPrimitive(true)
            val partial = !retentionComplete || originalFailure != null || failureCode != null || report["task_outcome"] != kotlinx.serialization.json.JsonPrimitive("COMPLETED") || report["completion_verified"] != kotlinx.serialization.json.JsonPrimitive(true)
            val oversizedWithoutEvidence = draft != null && status == null && coverage == null && ciObservation == null && requests == null && receipts.isEmpty()
            check(!oversizedWithoutEvidence) { "No retained GitHub evidence is available for publication" }
            val retained = JsonObject(
                report + buildJsonObject {
                    progressDigest?.let { put("checklist_progress_digest", it) }
                    put("progress_state", if (stalled) "BLOCKED_UNCHANGED" else "ADVANCED_OR_FIRST_REPORT")
                    if (checklist != null) {
                        put("test_checklist", checklist)
                        val stop = if (originalFailure != null || failureCode != null) {
                            buildJsonObject {
                                put("code", originalFailure?.errorCode ?: failureCode ?: "RECORDED_EXECUTION_FAILURE")
                                put("detail", originalFailure?.message ?: "The recorded failure interrupted this verification stage.")
                                put("source", "RECORDED_EXECUTION_FAILURE")
                                remainingToolCalls()?.let { put("remaining_tool_calls", it) }
                            }
                        } else {
                            GitHubIntegrationReport.stopReason(task, receipts, authorizedTools, remainingToolCalls())
                        }
                        put("stop_reason", stop)
                    }
                    retention.forEach { (key, value) -> put(key, value) }
                    put("partial", partial)
                    put("resume_required", partial)
                    if (partial) {
                        put("completion_verified", false)
                        put("task_outcome", if (originalFailure?.taskOutcome == "BLOCKED") "BLOCKED" else "PARTIAL")
                    }
                    put("model_round_finished", draft != null)
                    put("verification_elapsed_ms", ((System.nanoTime() - verificationStarted) / 1_000_000).coerceAtLeast(0))
                    put("verification_measurement", "Observed answer verification and rendering, including claim extraction when used")
                    originalFailure?.let { failure ->
                        GitHubTaskLifecycle.failureMetadata(failure).filterKeys { it != "stop_reason" }.forEach { (key, value) -> put(key, value) }
                        put(
                            "stop_reason",
                            buildJsonObject {
                                put("code", failure.failureDetails?.get("stop_reason") as? kotlinx.serialization.json.JsonPrimitive ?: kotlinx.serialization.json.JsonPrimitive(failure.errorCode ?: "RECORDED_EXECUTION_FAILURE"))
                                put("detail", failure.message)
                                put("source", "RECORDED_EXECUTION_FAILURE")
                                remainingToolCalls()?.let { put("remaining_tool_calls", it) }
                            }
                        )
                        put(
                            "original_failure",
                            buildJsonObject {
                                put("code", failure.errorCode.orEmpty())
                                put("message", failure.message)
                                put("transport_complete", failure.transportComplete)
                                failure.failureDetails?.forEach { (key, value) -> put(key, value) }
                            }
                        )
                    }
                    if (partial && "verification_failure" !in report) {
                        put(
                            "verification_failure",
                            failureCode ?: if (!retentionComplete) {
                                "GITHUB_EVIDENCE_RETENTION_INCOMPLETE"
                            } else if (coverage != null || report["claims_verified"] == kotlinx.serialization.json.JsonPrimitive(true)) {
                                "GITHUB_TASK_INCOMPLETE"
                            } else {
                                "GITHUB_EVIDENCE_REQUIRED"
                            }
                        )
                    }
                }
            )
            val execution = GitHubExecutionLedger.attach(GitHubAcceptanceLedger.attach(retained, receipts), receipts)
            val executionLedger = execution["execution_ledger"] as? JsonObject
            val unresolved = executionLedger?.get("unresolved_remote_outcomes") as? kotlinx.serialization.json.JsonPrimitive
            val reconciliationRequired = unresolved?.content?.toIntOrNull()?.let { it > 0 } == true
            val effectivePartial = partial || executionLedger?.get("requested_task") != kotlinx.serialization.json.JsonPrimitive("VERIFIED_COMPLETE") || execution["task_outcome"] != kotlinx.serialization.json.JsonPrimitive("COMPLETED")
            val effectiveReport = JsonObject(
                execution + buildJsonObject {
                    if (effectivePartial) {
                        put("partial", true)
                        put("resume_required", true)
                        put("completion_verified", false)
                        if (execution["task_outcome"] == kotlinx.serialization.json.JsonPrimitive("COMPLETED")) put("task_outcome", "PARTIAL")
                    }
                    if (reconciliationRequired && "verification_failure" !in execution) put("verification_failure", "RECONCILIATION_REQUIRED")
                }
            )
            evidence.retainVerdict(effectiveReport)
            val cause = originalFailure?.let {
                val heading = if (it.failureDetails?.get("stop_reason") == kotlinx.serialization.json.JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")) "Time limit reached" else "Task paused"
                "**$heading.** ${it.message}\n\n"
            }.orEmpty()
            val failures = if (GitHubTaskCoverage.boundedDiagnostic(task)) withContext(computationDispatcher) { GitHubFailureSummary.render(receipts) } else ""
            val retentionNotice = if (retention["saved_view_retention_complete"] == kotlinx.serialization.json.JsonPrimitive(false)) {
                "\n\n**Evidence retention incomplete:** Some saved result pages could not be durably retained. Accepted operation receipts remain recorded. Recover and persist the same captured result; do not repeat a mutation to regenerate its output."
            } else {
                ""
            }
            val reconciliationNotice = if (reconciliationRequired) {
                "**Task partial: remote outcome unconfirmed.** Reconcile the saved operation receipt and remote state before continuing. A completed response or passing observations cannot establish the outcome of an uncertain operation. No write was repeated.\n\n"
            } else {
                ""
            }
            val approvalNotice = if ((executionLedger?.get("pending_approvals") as? JsonArray)?.isNotEmpty() == true) {
                if (originalFailure != null) {
                    "A saved approval is still unresolved in this checkpoint. Check its current state before approving or repeating a change.\n\n"
                } else {
                    "**Review needed.** Open the saved **Approve & Commit** card to review the change.\n\n"
                }
            } else {
                ""
            }
            Publication(cause + reconciliationNotice + approvalNotice + text + failures + retentionNotice, effectivePartial, effectiveReport)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val policy = failureCauses(failure).filterIsInstance<dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException>().firstOrNull()
            if (policy != null) {
                evidence.retainVerdict(
                    buildJsonObject {
                        put("task_outcome", "CONFIGURATION_INVALIDATED")
                        put("changed_policy_fields", JsonArray(policy.fields.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        put("resume_required", true)
                        put("receipt_count", evidence.snapshot().first.size)
                        put("recovery", "Rebuild using the current authorized catalog and resume the first unfinished requirement; never replay a mutation without reconciliation.")
                    }
                )
                throw policy
            } else {
                if (originalFailure != null) {
                    emit(ProviderEvent.TextDelta("The task was interrupted before verification finished. Completed operations remain saved; no completion is claimed.\n\nOriginal failure: ${originalFailure.message}\n\nVerification also could not finish: ${failure.message.orEmpty().take(400)}", dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, originalFailure.invocationId)))
                    emit(originalFailure)
                } else {
                    emit(ProviderEvent.Failed("GitHub answer withheld: ${failure.message.orEmpty().take(400)}. Not available / Not measured. Retained evidence can be inspected or refreshed.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
                }
            }
            return@flow
        }
        // Collector exceptions must propagate unchanged; they are not formatter failures.
        val (text, partial, report) = publication
        val retained = evidence.snapshot().first
        val currentProgress = GitHubExecutionContinuation.progressKeys(retained)
        if (partial &&
            originalFailure == null &&
            openExecutionContinuation != null &&
            continuationTools.isNotEmpty() &&
            progressCorrections < 3 &&
            (currentProgress - correctionProgress).isNotEmpty() &&
            GitHubRequestCompletion.requiresRepositoryEvidence(task) &&
            GitHubExecutionContinuation.canResume(report, retained, remainingToolCalls(), evidence.remainingTaskMillis())
        ) {
            // Keep the exact acceptance gaps durable before another selected-profile round.
            // The existing runner still owns tool admission, original budgets and mutation deduplication.
            evidence.retainVerdict(JsonObject(report + mapOf("response_delivered" to kotlinx.serialization.json.JsonPrimitive(false))))
            correctionProgress = currentProgress
            progressCorrections++
            validatePublication()
            progress("Continuing the first unfinished acceptance criterion from saved receipts")
            val correction = collectDraft(openExecutionContinuation.invoke(GitHubExecutionContinuation.prompt(task, currentRequest, retained, report)), continuationTools, continuationExchanges)
            if (correction.failed || !correction.completed) {
                emit(correction.failure ?: ProviderEvent.Failed("Task continuation was interrupted. Its saved checkpoint is retained.", errorCode = "GITHUB_TASK_INCOMPLETE", taskOutcome = "PARTIAL"))
                return@flow
            }
            if (correction.calls) {
                emit(ProviderEvent.Completed)
                return@flow
            }
            // A second prose-only answer is not progress, evidence or a reason to spend another round.
        }
        try {
            evidence.retainAnswer(text, report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ProgressCheckpointFailure(failure, originalFailure)
        }
        emit(ProviderEvent.TextDelta(text, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
        val delivered = evidence.retainVerdict(
            JsonObject(
                report + buildJsonObject {
                    put("response_delivered", true)
                    put("response_sha256", GitHubEvidenceBook.digest(text))
                }
            )
        )
        onPublication(delivered)
        val unresolved = ((delivered["execution_ledger"] as? JsonObject)?.get("unresolved_remote_outcomes") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { it > 0 } == true
        val extractionCode = (delivered["failure_code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeUnless { it == "null" }
        val waitingApproval = (delivered["execution_ledger"] as? JsonObject)?.get("recovery") == kotlinx.serialization.json.JsonPrimitive("WAITING_FOR_APPROVAL")
        val approvalIsPrimary = waitingApproval && originalFailure == null && !unresolved
        if (partial || delivered["completion_verified"] != kotlinx.serialization.json.JsonPrimitive(true)) {
            lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.VERIFICATION_REJECTED)
            emit(
                ProviderEvent.Failed(
                    if (approvalIsPrimary) "Review the saved change to continue." else originalFailure?.message ?: GitHubExecutionContinuation.incompleteMessage(delivered, evidence.snapshot().first),
                    errorCode = if (unresolved) {
                        "RECONCILIATION_REQUIRED"
                    } else if (originalFailure != null) {
                        originalFailure.errorCode ?: failureCode ?: "GITHUB_TASK_INCOMPLETE"
                    } else if (approvalIsPrimary) {
                        "WAITING_FOR_APPROVAL"
                    } else {
                        failureCode ?: extractionCode ?: (delivered["verification_failure"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "GITHUB_TASK_INCOMPLETE"
                    },
                    transportComplete = true,
                    providerLimit = originalFailure?.providerLimit?.takeUnless { unresolved },
                    invocationId = originalFailure?.invocationId,
                    taskOutcome = if (approvalIsPrimary || originalFailure?.taskOutcome == "BLOCKED") "BLOCKED" else "PARTIAL",
                    failureDetails = if (approvalIsPrimary) {
                        buildJsonObject {
                            put("failed_stage", "approval")
                            put("failure_domain", "APPROVAL")
                            put("dispatched", false)
                            put("user_input_required", true)
                            put("retryable", false)
                            put("pending_approvals", delivered["pending_approvals"] ?: (delivered["execution_ledger"] as? JsonObject)?.get("pending_approvals") ?: JsonArray(emptyList()))
                        }
                    } else {
                        originalFailure?.failureDetails ?: buildJsonObject {
                            val extractionFailure = (delivered["verification_failure"] as? JsonObject)?.takeIf { it["failed_stage"] == JsonPrimitive("claim_extraction") }
                            put("failed_stage", if (extractionFailure != null || extractionCode?.startsWith("GITHUB_EXTRACTION_") == true) "claim_extraction" else "acceptance_verification")
                            extractionFailure?.get("batch")?.let { put("extraction_batch", it) }
                            listOf("extraction_batches_completed", "extraction_batches_total").forEach { key -> delivered[key]?.let { put(key, it) } }
                            val stop = delivered["stop_reason"] as? JsonObject
                            put("stop_reason", stop?.get("code") ?: kotlinx.serialization.json.JsonPrimitive("UNVERIFIED_ACCEPTANCE_CRITERIA"))
                            stop?.let { put("acceptance_stop", it) }
                            put("failure_domain", "APPLICATION_VERIFICATION")
                            put("recovery_action", if (unresolved) kotlinx.serialization.json.JsonPrimitive("RECONCILE_REMOTE_OUTCOME") else stop?.get("recovery_action") ?: kotlinx.serialization.json.JsonPrimitive("RESUME_UNFINISHED_WORK"))
                            put("automatic_continuations", progressCorrections)
                            (delivered["execution_ledger"] as? JsonObject)?.let { ledger ->
                                listOf("required_criteria", "verified_criteria", "remaining_criteria").forEach { key -> ledger[key]?.let { put(key, it) } }
                            }
                        }
                    }
                )
            )
        } else {
            emit(ProviderEvent.Completed)
        }
    }

    private data class Publication(val text: String, val partial: Boolean, val report: JsonObject)

    private suspend fun partial(receipts: Map<String, JsonObject>, epoch: Long, failureCode: String? = null): Pair<String, JsonObject> = withContext(computationDispatcher) {
        GitHubEvidencePolicy.partial(receipts, now(), epoch, task, failureCode)
    }

    private suspend fun claimExtractionContext(receipts: Map<String, JsonObject>, epoch: Long): String = withContext(computationDispatcher) {
        val identity = receipts.entries.sortedBy { it.key }.joinToString("\n") { (id, receipt) ->
            "$id:${receipt["source_result_sha256"]}:${receipt["verification_eligible"]}:${receipt["epoch"]}:${receipt["observed_at_ms"]}"
        }
        GitHubEvidenceBook.digest("$task\n$epoch\n$identity")
    }

    private suspend fun extract(draft: String, requests: List<GitHubClaimBatches.Request>, receipts: Map<String, JsonObject>, epoch: Long): Pair<String, JsonObject> {
        val context = claimExtractionContext(receipts, epoch)
        val plan = withContext(computationDispatcher) {
            GitHubEvidenceBook.digest("github.claim-extraction.v1|$epoch|" + requests.joinToString("\n") { GitHubEvidenceBook.digest(it.payload) })
        }
        val saved = evidence.retainedClaimExtraction(context)?.takeIf { it["plan"] == JsonPrimitive(plan) }
        val completed = mutableListOf<JsonObject>()
        for ((batch, request) in (saved?.get("batches") as? JsonArray).orEmpty().zip(requests)) {
            val answer = batch as? JsonObject ?: break
            completed += runCatching { GitHubClaimBatches.validate(answer, request) }.getOrNull() ?: break
        }
        suspend fun failed(code: String, providerFailure: ProviderEvent.Failed? = null): Pair<String, JsonObject> {
            val (text, report) = partial(receipts, epoch, code)
            return text to JsonObject(
                report + buildJsonObject {
                    put("extraction_batches_completed", completed.size)
                    put("extraction_batches_total", requests.size)
                    put("resume_required", true)
                    put(
                        "verification_failure",
                        buildJsonObject {
                            providerFailure?.failureDetails?.forEach { (key, value) -> put(key, value) }
                            put("code", code)
                            put("failed_stage", "claim_extraction")
                            if (completed.size < requests.size) put("batch", completed.size + 1)
                            providerFailure?.message?.let { put("message", it) }
                        }
                    )
                }
            )
        }
        return try {
            withTimeoutOrNull(90_000) {
                if (completed.isEmpty()) evidence.retainClaimBatches(context, plan, draft, completed, pending = true)
                for (request in requests.drop(completed.size)) {
                    validatePublication()
                    val result = extractBatch(request)
                    validatePublication()
                    completed += result
                    evidence.retainClaimBatches(context, plan, draft, completed, pending = true)
                }
                val result = withContext(computationDispatcher) {
                    GitHubEvidencePolicy.render(GitHubClaimBatches.combine(completed), receipts, now(), epoch, task)
                }
                if (saved?.get("pending") != JsonPrimitive(false) || completed.size != (saved?.get("batches") as? JsonArray)?.size) {
                    evidence.retainClaimBatches(context, plan, draft, completed, pending = false)
                }
                result
            } ?: failed("GITHUB_EXTRACTION_TIMEOUT")
        } catch (failure: ExtractionFailed) {
            failed(failure.code, failure.providerFailure)
        }
    }

    private suspend fun extractBatch(request: GitHubClaimBatches.Request): JsonObject = try {
        val response = StringBuilder()
        var complete = false
        var finishReason: String? = null
        openVerifier(request.payload).streamRound(emptyList(), emptyList()).collect { event ->
            when (event) {
                is ProviderEvent.TextDelta -> {
                    check(response.length + event.text.length.toLong() <= 32_768) { "Claim extraction exceeded its output bound" }
                    response.append(event.text)
                }
                is ProviderEvent.Completed -> complete = true
                is ProviderEvent.Usage -> event.finishReason?.let { finishReason = it }
                is ProviderEvent.Failed -> throw ExtractionFailed(event.errorCode ?: "GITHUB_EXTRACTION_FAILED", event)
                is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> error("Evidence formatting attempted an operation")
                else -> Unit
            }
        }
        if (finishReason?.lowercase() in setOf("length", "max_tokens", "max_output_tokens", "token_limit")) throw ExtractionFailed("GITHUB_EXTRACTION_OUTPUT_LIMIT")
        check(complete) { "Claim extraction was interrupted" }
        withContext(computationDispatcher) {
            val answer = Json.parseToJsonElement(response.toString().trim()) as? JsonObject ?: error("Claim extraction was not structured")
            GitHubClaimBatches.validate(answer, request)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: ExtractionFailed) {
        throw failed
    } catch (failure: Exception) {
        val policy = failureCauses(failure).firstOrNull {
            it is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException ||
                it is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged ||
                it is dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied
        }
        if (policy != null) throw policy
        throw ExtractionFailed("GITHUB_EXTRACTION_INVALID_RESPONSE")
    }

    private class ExtractionFailed(val code: String, val providerFailure: ProviderEvent.Failed? = null) : IllegalStateException(code)

    private fun verificationRequests(draft: String, receipts: Map<String, JsonObject>): List<GitHubClaimBatches.Request>? {
        val hardLimit = inputLimit()
        val groups = GitHubRequestCompletion.requirements(task).distinct().chunked(GitHubClaimBatches.REQUIREMENTS_PER_BATCH)
        // Never let the extraction bound silently remove acceptance criteria. A task
        // that exceeds the bounded batch plan remains incomplete until it is split or
        // a larger, explicitly supported plan is introduced.
        if (groups.size > GitHubClaimBatches.MAX_BATCHES) return null
        val catalog by lazy { JsonArray(GitHubEvidencePolicy.catalog(receipts).reversed()) }
        val requests = groups.mapIndexed { index, requirements ->
            val candidate = if (groups.size == 1) draft else draftExcerpt(draft, requirements)
            val ids = requirements.map(GitHubRequestCompletion::id)
            fun payload(selected: List<kotlinx.serialization.json.JsonElement>) = buildJsonObject {
                put("task", task)
                put("batch", index + 1)
                put("batches", groups.size)
                put("claim_limit", GitHubClaimBatches.MAX_CLAIMS)
                put(
                    "requirements",
                    JsonArray(
                        requirements.zip(ids).map { (text, id) ->
                            buildJsonObject {
                                put("id", id)
                                put("text", text)
                            }
                        }
                    )
                )
                put("candidate", candidate)
                put("claim_selection", "Address the listed requirement IDs only. Prioritize the smallest set of evidenced claims needed to answer them.")
                put("catalog", JsonArray(selected))
                put("retained_receipts", receipts.size)
                put("omitted_from_context", receipts.size - selected.size)
                put("coverage", "Full originals remain in the encrypted ledger. Omitted source is not proof; unresolved claims must remain Not measured.")
            }.toString()
            var request = payload(emptyList())
            val baseTokens = TextTokenEstimate.estimate(request)
            if (baseTokens > hardLimit) return null
            val limit = minOf(hardLimit, maxOf(6_000, baseTokens + 2_048))
            val query = if (groups.size == 1) "$task\n$draft" else requirements.joinToString("\n") + "\n" + candidate
            val selected = mutableListOf<kotlinx.serialization.json.JsonElement>()
            for (entry in GitHubVerificationCatalog.select(catalog, query)) {
                val next = payload(selected + entry)
                if (TextTokenEstimate.estimate(next) <= limit) {
                    selected += entry
                    request = next
                }
            }
            GitHubClaimBatches.Request(request, ids.toSet())
        }
        val currentLimit = inputLimit()
        return requests.takeIf { batch -> batch.all { TextTokenEstimate.estimate(it.payload) <= currentLimit } }
    }

    private fun draftExcerpt(draft: String, requirements: List<String>): String {
        val terms = Regex("[A-Za-z0-9_]{4,}").findAll(requirements.joinToString(" ").lowercase()).map { it.value }.toSet()
        return draft.lineSequence().take(2048).filter(String::isNotBlank).map { it.take(900) }.withIndex()
            .sortedByDescending { line -> terms.count { term -> line.value.contains(term, ignoreCase = true) } }
            .take(10).sortedBy { it.index }.joinToString("\n") { it.value }.take(3600)
    }
}
