package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.GitHubProviderResumeContext
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.captureProviderInvocation
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
    private val executionCallLimit: Int? = null,
    private val currentRequest: String = task,
    private val openExecutionContinuation: (suspend (String) -> AgentProviderSession)? = null,
    private val resumeClaimExtraction: Boolean = false,
    private val verificationOnlyRequested: Boolean = false,
    private val awaitOwnedDescendants: suspend (Long) -> Boolean = { true },
    private val independentPatchReview: (suspend (JsonObject) -> AgentProviderSession?)? = null,
    measuredRoutingTokens: suspend () -> Int? = { null },
    private val workspaceHead: String? = null,
    private val continuationBudget: (() -> GitHubContinuationBudget)? = null,
    private val sourceDeliveryRecords: suspend () -> List<String> = { emptyList() },
    private val verificationBudgetChanged: (Int, Int) -> Unit = { _, _ -> }
) : AgentProviderSession by delegate {
    override val handlesToolResultRecovery: Boolean = GitHubTestChecklist.integration(task) ||
        GitHubTaskContinuity.reportOnly(currentRequest) ||
        GitHubTaskContinuity.diagnostic(currentRequest) ||
        GitHubLookupRoute.request(task)?.let { it.route == GitHubLookupRoute.COMMITS && !it.exhaustive && (it.requestedCount ?: 10) <= 100 } == true
    private val ciDiagnosis = GitHubCiObservationReport.matches(task)
    private var readOnlyEvidenceRecovery = false
    private var reservedVerificationReads = false
    private var lastChecklist: JsonObject? = null
    private var initialReceiptIds: Set<String>? = null
    private var initiallyVerifiedComplete = false
    private val readProgress = GitHubReadRoundProgress(detectFailedReads = evidence.nextActionMode == GitHubNextActionMode.ACTIVE)
    private var replannedReader: AgentProviderSession? = null
    private var replanBoundary: String? = null
    private var completedDecisionMillis = 0L
    private val capabilityRequest = GitHubCapabilityRequest.from(currentRequest)
    private val lookup = GitHubLookupRoute.request(task)
    private val issueIntake = GitHubIssueRemediation.requested(task)
    private var initialLookupIssued = false
    private var executionCorrectionAttempted = false
    private var correctionProgress: Set<String> = emptySet()
    private var progressCorrections = 0
    private var transportWaited = false
    private var refreshDraft: String? = null
    private var providerStepResumed = false
    private var statusPlanTools: List<AgentToolDefinition> = emptyList()
    private var observedAdvertisedToolCount: Int? = null
    private var issueWork: GitHubIssueRemediation.Context? = null
    private var verificationOnlyAttempted = false
    private val evidenceRoutes = GitHubEvidenceRoutePolicy(measuredRunTokens = measuredRoutingTokens)
    private var lastReusedProof: Pair<String, Long>? = null
    private class ProgressCheckpointFailure(cause: Exception, val originalFailure: ProviderEvent.Failed?) : Exception(cause)

    private fun failureCauses(error: Throwable): List<Throwable> = buildList {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && size < 16 && seen.add(current)) {
            add(current)
            current = if (size < 16) current.cause else null
        }
    }

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

    override suspend fun completeFromReceipts(exchanges: List<AgentToolExchange>): Flow<ProviderEvent>? {
        if ((!ciDiagnosis && lookup == null && !issueIntake) ||
            verificationOnlyRequested ||
            GitHubTaskContinuity.diagnostic(currentRequest) ||
            GitHubTaskContinuity.reportOnly(currentRequest)
        ) {
            return null
        }
        exchanges.forEach { exchange ->
            exchange.results.forEach { result ->
                exchange.calls.firstOrNull { it.callId == result.callId }?.let { evidence.observe(it, result) }
            }
        }
        if (!evidence.complete() || evidence.taskAdmissionFailure() != null) return null
        val (receipts, epoch) = evidence.snapshot()
        if (GitHubPendingApproval.snapshot(receipts).isNotEmpty() ||
            receipts.values.any {
                GitHubExecutionLedger.receiptState(it) in setOf(GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME, GitHubExecutionLedger.ReceiptState.DISPATCHED)
            }
        ) {
            return null
        }
        val observed = (if (ciDiagnosis) renderCiObservation(receipts, epoch) else renderLookupObservation(receipts, epoch)) ?: return null
        if (observed.second["completion_verified"] != JsonPrimitive(true)) return null
        // Publication repeats the proof against the final receipt snapshot and current
        // authority. No replay body or verifier model is required for this response.
        val entry = when {
            ciDiagnosis -> GitHubAcceptancePublicationEvidence.Entry.CI_OBSERVATION
            issueIntake -> GitHubAcceptancePublicationEvidence.Entry.ISSUE_INTAKE
            observed.second["schema"] == JsonPrimitive("github.lookup-report.v1") -> GitHubAcceptancePublicationEvidence.Entry.LOOKUP_OBSERVATION
            else -> GitHubAcceptancePublicationEvidence.Entry.STATUS_SNAPSHOT
        }
        return publish(null, entry)
    }

    private suspend fun renderCiObservation(receipts: Map<String, JsonObject>, epoch: Long, checkSnapshotOnly: Boolean = false): Pair<String, JsonObject>? {
        val target = evidence.acceptedHeadSha(workspaceHead)
        val repository = (evidence.acceptedPromptContract()?.get("repository") as? JsonPrimitive)?.contentOrNull
        return withContext(computationDispatcher) {
            GitHubCiObservationReport.render(task, receipts, now(), epoch, checkSnapshotOnly, target, repository)
        }
    }

    private suspend fun renderLookupObservation(receipts: Map<String, JsonObject>, epoch: Long): Pair<String, JsonObject>? {
        if (lookup == null && !issueIntake) return null
        val contract = evidence.acceptedPromptContract() ?: return null
        val repository = (contract["repository"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (GitHubTaskContinuity.request(evidence.acceptedTaskRequest().orEmpty()) != GitHubTaskContinuity.request(task)) return null
        if (issueIntake) {
            val intake = evidence.issueRemediation(task) ?: return null
            return withContext(computationDispatcher) { GitHubIssueRemediation.emptyIntakeReport(task, intake, contract, receipts, now(), epoch) }
        }
        val branch = (contract["accepted_branch"] as? JsonPrimitive)?.contentOrNull
        return withContext(computationDispatcher) {
            if (Regex("(?i)\\bsummari[sz]e\\b").containsMatchIn(GitHubTaskContinuity.request(task))) {
                null
            } else {
                val at = now()
                GitHubLookupReport.render(task, receipts, at, epoch, repository, branch)
                    ?: GitHubLookupObservation.render(task, receipts, repository, branch, at, epoch)
            }
        }
    }

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        if (kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.agent.SavedPageDeliveryRound] != null) {
            validatePublication()
            val denied = evidence.taskAdmissionFailure()
            when {
                denied != null -> emit(denied)
                handlesToolResultRecovery || verificationOnlyRequested || delegate.handlesToolsInternally || tools.isNotEmpty() ->
                    emit(dev.chungjungsoo.gptmobile.data.agent.SavedPageDeliveryRound.blocked("This session cannot admit a private saved-page read.", "provider_admission"))
                else -> emitAll(delegate.streamRound(emptyList(), exchanges))
            }
            return@flow
        }
        observedAdvertisedToolCount = null
        if (GitHubStatusRequest.matches(task)) statusPlanTools = tools
        val intent = dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract.from(GitHubTaskContinuity.request(task))
        if (intent.conversational) {
            emit(ProviderEvent.TextDelta(intent.acknowledgement(), dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
            emit(ProviderEvent.Completed)
            return@flow
        }
        exchanges.forEach { exchange -> exchange.results.forEach { result -> exchange.calls.firstOrNull { it.callId == result.callId }?.let { evidence.observe(it, result) } } }
        if (!evidence.complete()) {
            emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.EVIDENCE_RETENTION_INCOMPLETE, failureCode = "GITHUB_EVIDENCE_RETENTION_INCOMPLETE"))
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
        val admissionFailure = evidence.taskAdmissionFailure()
        if (admissionFailure != null) {
            val deadlineReached = admissionFailure.failureDetails?.get("stop_reason") == JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")
            emitAll(
                publish(
                    null,
                    if (deadlineReached) GitHubAcceptancePublicationEvidence.Entry.TASK_DEADLINE else GitHubAcceptancePublicationEvidence.Entry.RETAINED_FAILURE,
                    failureCode = admissionFailure.errorCode,
                    originalFailure = if (deadlineReached) observeExpiredDeadline(admissionFailure) else admissionFailure
                )
            )
            return@flow
        }
        refreshVerificationCriteria(evidence.diagnostic())
        completeFromReceipts(emptyList())?.let { completed ->
            emitAll(completed)
            return@flow
        }
        if (verificationOnlyRequested) {
            // This explicit control cannot fall through to generation, discovery,
            // approval polling or tool acquisition even when its saved draft is absent.
            validatePublication()
            val previous = evidence.diagnostic()
            val effective = previous?.let(GitHubEffectiveRecovery::resolve)
            val receipts = evidence.snapshot().first
            val unsettled = receipts.values.any {
                GitHubExecutionLedger.receiptState(it) in setOf(
                    GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME,
                    GitHubExecutionLedger.ReceiptState.DISPATCHED,
                    GitHubExecutionLedger.ReceiptState.WAITING_FOR_APPROVAL
                )
            }
            val draft = evidence.retainedClaimExtraction()?.get("draft")
                ?.let { it as? JsonPrimitive }?.content?.takeIf(String::isNotBlank)
            if (verificationOnlyAttempted || effective?.verificationOnly != true || unsettled) {
                evidence.retainedAnswer(includeEarlierFindings = true)?.let { (body, _) ->
                    emit(ProviderEvent.TextDelta(body, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.RETAINED_CONTENT)))
                }
                emit(
                    ProviderEvent.Failed(
                        "This saved checkpoint does not permit another verification-only pass. Review its retained recovery details before continuing.",
                        errorCode = "GITHUB_TASK_INCOMPLETE",
                        taskOutcome = "PARTIAL",
                        failureDetails = buildJsonObject {
                            put("failed_stage", "acceptance_verification")
                            put("dispatched", false)
                            put("resume_allowed", false)
                            put("retryable", false)
                            put("request_replay_allowed", false)
                            effective?.let { put("effective_recovery", it.json()) }
                        }
                    )
                )
                return@flow
            }
            verificationOnlyAttempted = true
            progress("Verifying the saved result once; completed operations remain retained")
            // Empty continuation tools prohibit source refresh or execution repair.
            // A stale receipt or another verifier failure remains a partial result.
            emitAll(
                publish(
                    draft,
                    GitHubAcceptancePublicationEvidence.Entry.SAVED_CLAIM_EXTRACTION,
                    verificationRestart = MAX_VERIFICATION_RESTARTS
                )
            )
            return@flow
        }
        capabilityRequest?.let { request ->
            validatePublication()
            val receipts = evidence.snapshot().first
            val contracts = authorizedTools.ifEmpty { tools }
            val catalog = tools.filter { it.name == GitHubAuthorizedCatalog.TOOL && it.modelVisible && evidence.trustsTool(it.name) }
            if (request.scope != GitHubCapabilityRequest.Scope.FEATURES &&
                !delegate.handlesToolsInternally &&
                !resumeClaimExtraction &&
                catalog.isNotEmpty() &&
                remainingToolCalls()?.let { it > 0 } == true
            ) {
                GitHubAuthorizedCatalog.next(catalog, receipts, contracts)?.takeUnless { next ->
                    exchanges.any { exchange -> exchange.calls.any { it.callId == next.callId && it.name == next.name } }
                }?.let { page ->
                    // A blank discovery can fetch cold MCP schemas. Interface questions read the local catalog only.
                    val local = if (page.arguments.isEmpty()) page.copy(arguments = buildJsonObject { put("catalog_only", true) }) else page
                    if (dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(local.arguments, catalog.first().inputSchema).isEmpty()) {
                        emit(local)
                        emit(ProviderEvent.Completed)
                        return@flow
                    }
                }
            }
            val snapshot = GitHubAuthorizedCatalog.snapshot(receipts, contracts)
            val answer = GitHubCapabilityAnswer.render(request, tools, contracts, snapshot)
            emit(ProviderEvent.TextDelta(answer, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
            emit(ProviderEvent.Completed)
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
                val approval = pendingApprovals.first() as JsonObject
                fun approvalText(key: String) = (approval[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val taskId = approvalText("engineering_task_id")
                val cardId = approvalText("card_id")
                val branch = approvalText("branch")
                val patch = approvalText("patch_digest").take(12)
                val text = buildString {
                    append("## Waiting for approval\n\n")
                    append("Resolve the existing approval card")
                    if (cardId.isNotBlank()) append(" `$cardId`")
                    append(" before continuing. No commit has been published.\n\n")
                    if (taskId.isNotBlank()) append("Task: `$taskId`\n")
                    if (branch.isNotBlank()) append("Branch: `$branch`\n")
                    if (patch.isNotBlank()) append("Patch: `$patch`\n")
                    append("Choose Approve & Commit, Reject, or Keep fixture on that card. Generic Continue is paused while approval is pending.\n\n")
                    append(body)
                }
                val delivered = evidence.retainVerdict(
                    JsonObject(
                        verdict + buildJsonObject {
                            put("task_outcome", "WAITING_FOR_APPROVAL")
                            put("task_state", "WAITING_FOR_APPROVAL")
                            put("stop_code", "WAITING_FOR_APPROVAL")
                            put("approval_state", "WAITING_FOR_APPROVAL")
                            put("blocking_condition", "WAITING_FOR_APPROVAL")
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
                emit(ProviderEvent.Failed("Review the saved change to continue.", errorCode = "WAITING_FOR_APPROVAL", transportComplete = true, taskOutcome = "WAITING_FOR_APPROVAL"))
            } else {
                emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.APPROVAL_CHECKPOINT))
            }
            return@flow
        }
        if (!providerStepResumed && currentCoroutineContext()[GitHubProviderResumeContext] != null) {
            providerStepResumed = true
            retainProgress(tools)
            deliverDraftRound(collectDraft(delegate, tools, exchanges), tools, exchanges)
            return@flow
        }
        if (recoverGraphIndex(tools)) return@flow
        if (GitHubTestChecklist.integration(task)) {
            val receipts = evidence.snapshot().first
            val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls())
            if (remaining == null || remaining > GitHubChecklistBudget.cleanupReserve(receipts)) {
                GitHubSelfTestGraph.statusForSavedIndex(tools, receipts.values.toList(), resumeRequestKey)?.let { call ->
                    validatePublication()
                    progress("Checking the saved repository indexing job; independent checks can continue")
                    emit(call)
                    emit(ProviderEvent.Completed)
                    return@flow
                }
            }
        }
        refreshDraft?.let { draft ->
            refreshDraft = null
            emitAll(publish(draft, GitHubAcceptancePublicationEvidence.Entry.SAVED_CLAIM_EXTRACTION, continuationTools = tools, continuationExchanges = exchanges))
            return@flow
        }
        if (resumeCommitSha == null && (resumeClaimExtraction || GitHubTaskContinuity.control(currentRequest))) {
            val saved = evidence.retainedClaimExtraction()
            val draft = (saved?.get("draft") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            if (draft != null) {
                val previous = evidence.diagnostic()
                val resumeAcceptance = saved["pending"] == JsonPrimitive(false) &&
                    GitHubTaskContinuity.control(currentRequest) &&
                    (previous?.get("execution_ledger") as? JsonObject)?.get("recovery") == JsonPrimitive("RESUME_UNFINISHED_WORK") &&
                    previous["verification_failure"] in setOf(JsonPrimitive("GITHUB_TASK_INCOMPLETE"), JsonPrimitive("GITHUB_EVIDENCE_REQUIRED"))
                emitAll(publish(draft, GitHubAcceptancePublicationEvidence.Entry.SAVED_CLAIM_EXTRACTION, continuationTools = tools, continuationExchanges = exchanges, resumeAcceptance = resumeAcceptance))
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
        if (!GitHubTestChecklist.integration(task) && remainingToolCalls()?.let { it > 0 } != false) {
            val recorded = evidence.snapshot().first.values.toList()
            if (recorded.any { it["error_code"] == JsonPrimitive("GITHUB_PR_HEAD_CHANGED") }) {
                val recovery = GitHubSelfTestPullReads.recoverReview(tools, recorded)
                if (recovery != null) {
                    progress("Refreshing the changed pull request revision before continuing the read")
                    emit(recovery)
                    emit(ProviderEvent.Completed)
                    return@flow
                }
            }
        }
        runRepositoryWorkflow(tools, exchanges)
    }.catch { error ->
        handleCheckpointFailure(error)
    }

    private suspend fun FlowCollector<ProviderEvent>.runRepositoryWorkflow(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) {
        if (ciDiagnosis) {
            val (receipts, epoch) = evidence.snapshot()
            val at = now()
            val target = evidence.acceptedHeadSha(workspaceHead)
            val repository = (evidence.acceptedPromptContract()?.get("repository") as? JsonPrimitive)?.contentOrNull
            val selected = GitHubStatusEvidence.selected(receipts, repository).filterValues { row ->
                row["trusted"] == JsonPrimitive(true) &&
                    row["verification_eligible"] != JsonPrimitive(false) &&
                    row["epoch"] == JsonPrimitive(epoch)
            }
            val head = selected.values.lastOrNull {
                it["head_observation"] == JsonPrimitive(true) && (target == null || it["source_head_sha"] == JsonPrimitive(target))
            }?.get("source_head_sha")?.let(GitHubInventoryQuery::commitSha)?.content
            val observed = renderCiObservation(receipts, epoch)
            val unresolvedRun = head?.let {
                val inventory = GitHubCiRunInventory.snapshot(selected, it, at)
                GitHubObservedWorkflowRuns.current(GitHubCiRunInventory.currentObservations(selected, inventory).values, JsonPrimitive(it))
            }
                .orEmpty().any { it["status"] != JsonPrimitive("completed") || it["conclusion"] !in setOf(JsonPrimitive("success"), JsonPrimitive("skipped"), JsonPrimitive("neutral")) }
            if (head != null && !unresolvedRun && observed?.second?.get("observation_state") != JsonPrimitive("WAITING_FOR_CI") && remainingToolCalls()?.let { it > 0 } != false) {
                GitHubCiRunInventory.nextCall(tools, selected, head, at)?.let { next ->
                    progress("Completing the workflow inventory for the selected commit")
                    emit(next)
                    emit(ProviderEvent.Completed)
                    return
                }
            }
            if (observed != null && !pendingPackagingRepair(observed.second)) {
                val report = observed.second
                if (report["observation_state"] == JsonPrimitive("WAITING_FOR_CI") && report["run_id"] == null && remainingToolCalls()?.let { it > 0 } != false) {
                    val lookup = tools.singleOrNull { it.modelVisible && JsonPrimitive(it.name) == report["run_lookup_tool"] }
                    val args = buildJsonObject {
                        put("action", "runs")
                        put("commit_sha", report.getValue("head_sha"))
                        put("per_page", 20)
                    }
                    if (lookup != null && dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, lookup.inputSchema).isEmpty()) {
                        val id = "ci-running-workflow-" + GitHubEvidenceBook.digest("${evidence.checkpointBinding()}|${lookup.name}|$args").take(24)
                        if (receipts.values.none { it["call_id"] == JsonPrimitive(id) || it["tool"] == JsonPrimitive(lookup.name) && it["arguments"] == args }) {
                            progress("Locating the active workflow at the observed commit")
                            emit(ProviderEvent.ToolCall(id, lookup.name, args))
                            emit(ProviderEvent.Completed)
                            return
                        }
                    }
                }
                if (report["observation_state"] == JsonPrimitive("WAITING_FOR_CI") && report["run_id"] != null && report["background_monitoring_registered"] != JsonPrimitive(true)) {
                    val watch = tools.singleOrNull { it.modelVisible && JsonPrimitive(it.name) == report["watch_tool"] }
                    val args = buildJsonObject {
                        put("run_id", report.getValue("run_id"))
                        put("all_checks", true)
                        put("commit_sha", report.getValue("head_sha"))
                        if ((watch?.inputSchema?.get("properties") as? JsonObject)?.containsKey("run_attempt") == true) report["run_attempt"]?.let { put("run_attempt", it) }
                    }
                    val previous = receipts.values.filter { it["tool"] == report["watch_tool"] && it["arguments"] == args }
                    val reattach = previous.isNotEmpty() && previous.all(GitHubVerificationRecovery::watchPlanningChange)
                    if (watch != null &&
                        remainingToolCalls()?.let { it > 0 } != false &&
                        dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator.errors(args, watch.inputSchema).isEmpty() &&
                        (previous.isEmpty() || reattach)
                    ) {
                        val id = (if (reattach) "ci-watch-reattach-" else "ci-watch-") + GitHubEvidenceBook.digest("${evidence.checkpointBinding()}|$args").take(24)
                        if (receipts.values.none { it["call_id"] == JsonPrimitive(id) }) {
                            if (reattach) validatePublication()
                            progress("Registering background monitoring for all checks at the observed commit")
                            emit(ProviderEvent.ToolCall(id, watch.name, args))
                            emit(ProviderEvent.Completed)
                            return
                        }
                    }
                }
                // Complete this bounded factual diagnosis directly from retained
                // evidence; another model request cannot discover a missing failure.
                emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.CI_OBSERVATION, continuationTools = tools, continuationExchanges = exchanges))
                return
            }
        }
        if (GitHubTestChecklist.integration(task)) {
            val receipts = evidence.snapshot().first
            val fixturePreflight = withContext(computationDispatcher) {
                evidence.classifyBeforeAcquisition(task, authorizedTools, receipts, evidence.fixtureApprovalPreflight())
            }
            val planningRemaining = remainingToolCalls()
            val planningBudget = evidence.stageBudget(receipts, planningRemaining, GitHubSelfTestFixtures.minimumCalls(authorizedTools))
            val (checkpoint, schedule) = withContext(computationDispatcher) {
                val schedule = GitHubTestChecklist.schedule(task, authorizedTools, tools, receipts, planningRemaining, fixturePreflight)
                JsonObject(
                    GitHubTestChecklist.checkpoint(task, receipts, authorizedTools, planningRemaining, fixturePreflight, planningBudget) +
                        ("execution_plan" to schedule.snapshot)
                ) to schedule
            }
            val discovery = tools.firstOrNull { it.name == GitHubAuthorizedCatalog.TOOL && it.modelVisible }
            val catalogNext = GitHubAuthorizedCatalog.next(tools, receipts, authorizedTools)
            val catalogComplete = GitHubAuthorizedCatalog.snapshot(receipts, authorizedTools)["complete"] == kotlinx.serialization.json.JsonPrimitive(true)
            val next = schedule.next
            if (checkpoint != lastChecklist) {
                val firstCheckpoint = lastChecklist == null
                if (receipts.isNotEmpty()) withContext(computationDispatcher) { evidence.retainChecklist(checkpoint) }
                lastChecklist = checkpoint
                val prerequisites = if (firstCheckpoint) " " + GitHubIntegrationPreparation.preflightSummary(task, fixturePreflight) else ""
                if (receipts.isEmpty()) {
                    val limit = planningBudget["effective_call_limit"] ?: planningRemaining
                    progress("Starting GitHub integration checks" + (limit?.let { " with $it tool calls available and ${GitHubChecklistBudget.VERIFICATION_RESERVE} reserved for verification" } ?: "") + ".$prerequisites")
                } else {
                    progress(GitHubTestChecklist.progressSummary(checkpoint, next) + prerequisites)
                }
            }
            if (next != null) {
                val retryAt = GitHubTransportRecovery.retryNotBefore(receipts.values)
                val wait = retryAt?.minus(now())?.coerceAtLeast(0) ?: 0L
                if (wait > 0 && next.name != "discover_available_tools" && "view_cursor" !in next.arguments) {
                    val stop = GitHubTransportRecovery.waitBlocker(wait, transportWaited, evidence.remainingTaskMillis())
                    if (stop != null) {
                        emitAll(
                            publish(
                                null,
                                GitHubAcceptancePublicationEvidence.Entry.INTEGRATION_COOLDOWN,
                                originalFailure = ProviderEvent.Failed(
                                    "GitHub transport is cooling down. Resume the saved task after the retry time.",
                                    errorCode = "GITHUB_CIRCUIT_OPEN",
                                    taskOutcome = "BLOCKED",
                                    failureDetails = buildJsonObject {
                                        put("failed_stage", "tool_continuation")
                                        put("retry_after_ms", wait)
                                        put("retry_not_before_ms", retryAt)
                                        put("github_cooldown_stop", stop)
                                        put("dispatched", false)
                                    }
                                )
                            )
                        )
                        return
                    }
                    transportWaited = true
                    progress("Waiting for GitHub transport before resuming the saved check")
                    delay(wait)
                    validatePublication()
                }
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                emit(next.copy(callId = next.callId + "-${receipts.size}"))
                emit(ProviderEvent.Completed)
                return
            }
            // The host may have finished while the independent audit ran. One
            // final read can unlock its source queries before saving a partial
            // report, without polling continuously or repeating the index write.
            val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls())
            if (remaining == null || remaining > GitHubChecklistBudget.cleanupReserve(receipts)) {
                GitHubSelfTestGraph.statusForSavedIndex(tools, receipts.values.toList(), "$resumeRequestKey:after-independent-reads")?.let { call ->
                    validatePublication()
                    progress("Checking whether saved indexing finished during the GitHub audit")
                    emit(call)
                    emit(ProviderEvent.Completed)
                    return
                }
            }
            // Coverage fields come exclusively from the cumulative ledger. No model draft
            // or answer rewrite can supply missing executions or unlock a blocked dependency.
            val acceptanceComplete = (checkpoint["requirements"] as? JsonArray)?.isNotEmpty() == true &&
                (checkpoint["remaining_requirements"] as? JsonArray)?.isEmpty() == true
            val catalogObservation = when {
                receipts.isNotEmpty() || catalogComplete || catalogNext != null -> null
                discovery == null -> "CATALOG_TOOL_NOT_EXPOSED"
                else -> "CATALOG_INITIAL_REQUEST_UNAVAILABLE"
            }
            val executionBlocker = when {
                acceptanceComplete -> null
                remainingToolCalls() == 0 -> "TOOL_BUDGET_EXHAUSTED"
                catalogObservation != null -> "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE"
                tools.none { it.modelVisible && (it.name.startsWith("github_") || it.name.isGatewayRepositoryTool()) } &&
                    (discovery == null || catalogComplete && authorizedTools.none { it.modelVisible && (it.name.startsWith("github_") || it.name.isGatewayRepositoryTool()) }) -> "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE"
                else -> null
            }
            val capabilityReadiness = GitHubCapabilityReadiness.observe(tools, authorizedTools, catalogObservation)
            if (executionBlocker == null) {
                emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.INTEGRATION_CHECKPOINT))
            } else {
                emitAll(
                    publish(
                        null,
                        GitHubAcceptancePublicationEvidence.Entry.INTEGRATION_BLOCKED,
                        failureCode = executionBlocker,
                        originalFailure = ProviderEvent.Failed(
                            if (executionBlocker == "TOOL_BUDGET_EXHAUSTED") {
                                "The accepted tool allowance is exhausted. Saved operation, catalog and fixture receipts are retained. Review the remaining work and explicitly authorize a new allowance before continuing execution."
                            } else {
                                GitHubCapabilityReadiness.guidance((capabilityReadiness["github_prerequisite_observation"] as? JsonPrimitive)?.content)
                            },
                            errorCode = executionBlocker,
                            taskOutcome = "BLOCKED",
                            failureDetails = buildJsonObject {
                                put("failed_stage", "tool_admission")
                                put("stop_reason", executionBlocker)
                                if (executionBlocker == "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE") {
                                    capabilityReadiness.forEach { (key, value) -> put(key, value) }
                                    put("failure_origin", "APPLICATION")
                                    put("recovery_action", "RESTORE_AUTHORIZED_CONFIGURATION")
                                    put("resume_from_checkpoint", true)
                                    put("permission_bypass_allowed", false)
                                }
                                put("dispatched", false)
                                put("retryable", false)
                            }
                        )
                    )
                )
            }
            return
        }
        if ((lookup != null || issueIntake) && !delegate.handlesToolsInternally && !resumeClaimExtraction && !initialLookupIssued) {
            firstRepositoryObservation(tools, exchanges)?.let { next ->
                validatePublication()
                initialLookupIssued = true
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                emit(next)
                emit(ProviderEvent.Completed)
                return
            }
        }
        if (GitHubStatusRequest.matches(task) && tools.isNotEmpty()) {
            val (receipts, epoch) = evidence.snapshot()
            val acceptedRepository = (evidence.acceptedPromptContract()?.get("repository") as? JsonPrimitive)?.contentOrNull
            val next = if (remainingToolCalls()?.let { it > 0 } != false) GitHubStatusRequest.nextCall(tools, receipts, now(), epoch, task, acceptedRepository) else null
            if (next != null) {
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                emit(next)
                emit(ProviderEvent.Completed)
                return
            }
            if (receipts.isNotEmpty()) {
                emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.STATUS_COLLECTION))
                return
            }
        }
        if (GitHubStatusRequest.matches(task)) {
            val (receipts, _) = evidence.snapshot()
            val hasStatusSnapshot = receipts.values.any { receipt ->
                (receipt["arguments"] as? JsonObject)?.get("action") == kotlinx.serialization.json.JsonPrimitive("status")
            }
            if (hasStatusSnapshot) {
                emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.STATUS_SNAPSHOT))
                return
            }
        }
        if (tools.isEmpty() && GitHubTaskCoverage.matches(task) && evidence.snapshot().first.values.any { it["trusted"] == kotlinx.serialization.json.JsonPrimitive(true) }) {
            // Tool coverage is computed from receipts; another prose review cannot
            // establish test execution that the receipts do not contain.
            emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.COVERAGE_CHECKPOINT))
            return
        }
        if (GitHubReviewComparison.matches(task)) {
            val comparisonTools = if (remainingToolCalls()?.let { it > 0 } != false) tools else emptyList()
            val comparison = withContext(computationDispatcher) {
                val receipts = evidence.snapshot().first
                val sourceAllowed = GitHubChecklistBudget.acquisitionAllowed(receipts, remainingToolCalls(), GitHubChecklistBudget.ordinaryVerificationReserve(executionCallLimit))
                GitHubReviewComparison.plan(task, comparisonTools, receipts, authorizedTools, acquireSource = sourceAllowed)
            }
            comparison?.nextCall?.let { next ->
                progress(
                    when {
                        next.name == "github_decide" -> "Waiting for your review baseline"
                        comparison.checkpoint["comparison_verified"] == JsonPrimitive(true) -> "Reading changed source at the saved review revisions"
                        else -> "Resolving exact review comparison revisions"
                    }
                )
                emit(next)
                emit(ProviderEvent.Completed)
                return
            }
            if (comparison != null && comparison.checkpoint["comparison_verified"] != JsonPrimitive(true)) {
                // A missing baseline is a prerequisite, not permission for the model
                // to choose an arbitrary branch. Keep this gate outside the reviewed
                // provider so neither its compatibility probe nor source work starts.
                val blocked = GitHubReviewComparison.blockedFailure(comparison)
                emitAll(
                    publish(
                        null,
                        GitHubAcceptancePublicationEvidence.Entry.REVIEW_COMPARISON_BLOCKED,
                        failureCode = blocked.errorCode,
                        originalFailure = blocked
                    )
                )
                return
            }
        }
        runEvidenceRound(tools, exchanges)
    }

    private suspend fun refreshVerificationCriteria(report: JsonObject?): Int =
        GitHubChecklistBudget.pendingCriteria(if (report == null) evidence.acceptedPromptContract() else null, report).also {
            verificationBudgetChanged(it, GitHubChecklistBudget.cleanupReserve(evidence.snapshot().first))
        }

    private suspend fun FlowCollector<ProviderEvent>.runEvidenceRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) {
        val checkpoint = evidence.diagnostic()
        val remainingCriteria = refreshVerificationCriteria(checkpoint)
        val reserve = GitHubChecklistBudget.ordinaryVerificationReserve(executionCallLimit, remainingCriteria)
        val verificationRead = dev.chungjungsoo.gptmobile.data.agent.GitHubNextActionPolicy.verificationOnly(checkpoint) &&
            GitHubExecutionContinuation.hasAcquisitionBudget(remainingToolCalls(), evidence.remainingTaskMillis(), checkpoint)
        reservedVerificationReads = verificationRead
        if (verificationRead) readOnlyEvidenceRecovery = true
        val reservedReceipts = evidence.snapshot().first
        if (!verificationRead && !GitHubChecklistBudget.acquisitionAllowed(reservedReceipts, remainingToolCalls(), reserve)) {
            val remaining = remainingToolCalls()
            if (!delegate.handlesToolsInternally && remaining != null && evidence.remainingTaskMillis() != 0L) {
                val verification = GitHubVerificationReserve.next(
                    tools.filter { evidence.trustsTool(it.name) },
                    reservedReceipts,
                    remaining,
                    GitHubChecklistBudget.cleanupReserve(reservedReceipts)
                )
                if (verification != null) {
                    validatePublication()
                    lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                    progress("Using the remaining verification allowance to check test jobs at the published commit")
                    emit(verification)
                    emit(ProviderEvent.Completed)
                    return
                }
            }
            val savedDraft = (evidence.retainedClaimExtraction()?.get("draft") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            if (savedDraft != null) {
                emitAll(publish(savedDraft, GitHubAcceptancePublicationEvidence.Entry.SAVED_CLAIM_EXTRACTION, continuationTools = tools, continuationExchanges = exchanges))
                return
            }
            val verificationDraft = GitHubVerificationReserve.candidate(reservedReceipts)
            if (verificationDraft != null) {
                emitAll(publish(verificationDraft, GitHubAcceptancePublicationEvidence.Entry.RETAINED_OBSERVATIONS))
                return
            }
            if (GitHubRequestCompletion.informational(task) && remainingToolCalls()?.let { it > 0 } == true && evidence.remainingTaskMillis() != 0L) {
                val receipts = evidence.snapshot().first
                val candidate = withContext(computationDispatcher) { GitHubPartialReport.fromReceipts(receipts) }
                if (candidate.isNotBlank() &&
                    GitHubPendingApproval.snapshot(receipts).isEmpty() &&
                    receipts.values.none { GitHubExecutionLedger.receiptState(it) == GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME }
                ) {
                    emitAll(publish(candidate, GitHubAcceptancePublicationEvidence.Entry.RETAINED_OBSERVATIONS))
                    return
                }
            }
            // The normal provider path must honor the same reserve as the checklist.
            // Publish the immutable receipt snapshot while verification capacity remains.
            // Resume uses the durable task; this does not renew its authorized allowance.
            emitAll(
                publish(
                    null,
                    GitHubAcceptancePublicationEvidence.Entry.RETAINED_FAILURE,
                    failureCode = "VERIFICATION_RESERVE_REACHED"
                )
            )
            return
        }
        val latest = exchanges.lastOrNull()
        val patchCall = latest?.calls?.lastOrNull { it.name.substringBefore("__") == "github_stage_patch" }
        var selectedReviewer: AgentProviderSession? = null
        var reviewerConsidered = false
        if (evidence.nextActionMode == GitHubNextActionMode.ACTIVE) {
            val report = evidence.diagnostic()
            val decision = dev.chungjungsoo.gptmobile.data.agent.GitHubNextActionPolicy.activeDecision(report)
            val selected = dev.chungjungsoo.gptmobile.data.agent.GitHubNextActionPolicy.selectedCriterion(GitHubAcceptanceWork.outstanding(report), decision)
            if (report != null &&
                selected != null &&
                decision?.get("action") in setOf(JsonPrimitive("REUSE_EVIDENCE"), JsonPrimitive("ACQUIRE")) &&
                !GitHubAcceptanceWork.deferred(selected)
            ) {
                val (receipts, epoch) = evidence.snapshot()
                val key = (decision?.get("decision_key") as? JsonPrimitive)?.content
                val linked = (selected["receipt_ids"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>().map { it.content }
                val producer = ((selected["criterion_contract"] as? JsonObject)?.get("evidence_producer") as? JsonPrimitive)?.content
                val reuse = key != null && (key to epoch) != lastReusedProof && decision?.get("action") == JsonPrimitive("REUSE_EVIDENCE")
                if (producer in setOf("SOURCE_OBSERVATION", "DEFECT_ANALYSIS", "DIFF_REVIEW")) {
                    val criterion = (selected["id"] as? JsonPrimitive)?.content.orEmpty()
                    val head = evidence.recoveryReadHead(workspaceHead.orEmpty())
                    val allowed = authorizedTools.filter { it.modelVisible }.mapTo(mutableSetOf()) { it.name }
                    val cached = if (reuse && linked.any { GitHubEvidenceRoutePolicy.cachedRange(receipts[it], epoch, head, allowed) }) {
                        (evidence.retainedClaimExtraction()?.get("draft") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
                    } else {
                        null
                    }
                    val stale = GitHubExecutionContinuation.staleReceiptIds(report).filter { id ->
                        val receipt = receipts[id]
                        val name = (receipt?.get("tool") as? JsonPrimitive)?.content
                        val args = receipt?.get("arguments") as? JsonObject
                        val native = if (id in linked && name != null && args != null) GitHubOperationTool.nativeArguments(name, args) else null
                        GitHubEvidenceRoutePolicy.relevantLookup(requireNotNull(producer), (native?.get("action") as? JsonPrimitive)?.content)
                    }
                    val live = GitHubExecutionContinuation.staleRead(stale, tools, receipts, resumeRequestKey)
                        ?: evidence.nextPinnedSearchRead(linked, tools, authorizedTools)
                    val stage = patchCall?.let { call -> receipts.values.singleOrNull { it["call_id"] == JsonPrimitive(call.callId) } }
                    val routes = buildSet {
                        if (cached != null) add(GitHubEvidenceRoutePolicy.Route.CACHED_RANGE)
                        if (live != null) add(GitHubEvidenceRoutePolicy.Route.LIVE_LOOKUP)
                        if (stage != null && independentPatchReview != null && tools.isNotEmpty()) add(GitHubEvidenceRoutePolicy.Route.REVIEWER)
                    }
                    reviewerConsidered = true
                    for (route in evidenceRoutes.choose(requireNotNull(producer), routes)) {
                        validatePublication()
                        when (route) {
                            GitHubEvidenceRoutePolicy.Route.CACHED_RANGE -> {
                                lastReusedProof = requireNotNull(key) to epoch
                                evidenceRoutes.started(producer, route, criterion)
                                emitAll(publish(requireNotNull(cached), GitHubAcceptancePublicationEvidence.Entry.SAVED_CLAIM_EXTRACTION, continuationTools = tools, continuationExchanges = exchanges))
                                return
                            }
                            GitHubEvidenceRoutePolicy.Route.LIVE_LOOKUP -> {
                                evidenceRoutes.started(producer, route, criterion)
                                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                                emit(requireNotNull(live))
                                emit(ProviderEvent.Completed)
                                return
                            }
                            GitHubEvidenceRoutePolicy.Route.REVIEWER -> {
                                selectedReviewer = evidenceRoutes.review(producer, criterion) { independentPatchReview?.invoke(requireNotNull(stage)) } ?: continue
                                break
                            }
                        }
                    }
                } else if (reuse && linked.any { receipts[it]?.get("verification_eligible") == JsonPrimitive(true) }) {
                    val savedDraft = (evidence.retainedClaimExtraction()?.get("draft") as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
                    if (savedDraft != null) {
                        lastReusedProof = requireNotNull(key) to epoch
                        emitAll(publish(savedDraft, GitHubAcceptancePublicationEvidence.Entry.SAVED_CLAIM_EXTRACTION, continuationTools = tools, continuationExchanges = exchanges))
                        return
                    }
                }
            }
        }
        if (selectedReviewer == null &&
            latest != null &&
            GitHubExecutionContinuation.requestsInstructionRead(task) &&
            latest.calls.any { it.name.githubOperationFamily() == "github_search" || it.callId.startsWith("pinned-source-schema-") }
        ) {
            val receipts = evidence.snapshot().first
            val issued = receipts.filterValues { row ->
                val args = row["arguments"] as? JsonObject
                row["tool"]?.let { (it as? JsonPrimitive)?.content?.githubOperationFamily() } == "github_search" &&
                    args != null &&
                    GitHubExecutionContinuation.instructionLookup(args) &&
                    !GitHubExecutionContinuation.requiresExhaustiveSearch(task, args)
            }
            val candidate = if (issued.isEmpty()) null else evidence.nextPinnedSearchRead(issued.keys.toList().asReversed(), tools, authorizedTools)
            if (candidate != null && receipts.values.none { GitHubExecutionLedger.receiptState(it) == GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME }) {
                validatePublication()
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                progress(if (candidate.name == GitHubAuthorizedCatalog.TOOL) "Loading the source reader for the repository instructions" else "Reading the repository instructions at the commit returned by search")
                emit(candidate)
                emit(ProviderEvent.Completed)
                return
            }
        }
        if (selectedReviewer == null && openExecutionContinuation != null && replannedReader == null && latest != null && readProgress.replan(latest, completedDecisionMillis)) {
            val saved = evidence.snapshot().first
            val prompt = GitHubExecutionContinuation.prompt(task, currentRequest, saved, evidence.diagnostic(), budget = continuationBudget?.invoke(), additionalInstruction = "\n" + GitHubExecutionContinuation.replanInstruction(task, currentRequest))
            progress("Reusing the saved findings and checking the remaining evidence")
            replannedReader = openExecutionContinuation.invoke(prompt)
            replanBoundary = latest.calls.first().callId
        }
        val patchSession = selectedReviewer ?: if (!reviewerConsidered && independentPatchReview != null && patchCall != null && tools.isNotEmpty()) {
            val stage = evidence.snapshot().first.values.singleOrNull { it["call_id"] == JsonPrimitive(patchCall.callId) }
            stage?.let { independentPatchReview.invoke(it) }
        } else {
            null
        }
        val boundary = replanBoundary
        val first = if (boundary == null) -1 else exchanges.indexOfFirst { exchange -> exchange.calls.any { it.callId == boundary } }
        val readerContext = if (first < 0) exchanges else exchanges.subList(first, exchanges.size)
        val decisionStarted = System.nanoTime()
        val roundSession = patchSession ?: replannedReader ?: delegate
        var round = collectDraft(roundSession, tools, readerContext)
        completedDecisionMillis = if (round.completed && !round.failed && round.calls) (System.nanoTime() - decisionStarted) / 1_000_000 else 0
        val retained = evidence.snapshot().first
        val missingReviewEvidence = canRecoverRepositoryEvidence(roundSession, round, tools, retained)
        if (!initiallyVerifiedComplete &&
            !executionCorrectionAttempted &&
            openExecutionContinuation != null &&
            tools.isNotEmpty() &&
            (missingReviewEvidence || round.completed && !round.failed && !round.calls && round.draft.isNotBlank()) &&
            retained.keys == initialReceiptIds &&
            (GitHubRequestCompletion.requiresRepositoryEvidence(task) || ciDiagnosis) &&
            remainingToolCalls()?.let { it > 0 } != false
        ) {
            if (missingReviewEvidence) {
                validatePublication()
                evidence.taskAdmissionFailure()?.let { denied ->
                    emit(denied)
                    return
                }
            }
            executionCorrectionAttempted = true
            readOnlyEvidenceRecovery = verificationRead || missingReviewEvidence || ciDiagnosis || GitHubRequestCompletion.requiresRepositoryEvidence(task) && GitHubRequestCompletion.informational(task)
            progress("The response proposed work without new execution evidence. Resuming the accepted task once with its tools and receipts")
            val prompt = GitHubExecutionContinuation.prompt(task, currentRequest, retained, evidence.diagnostic(), readOnlyRefresh = readOnlyEvidenceRecovery, budget = continuationBudget?.invoke())
            round = collectDraft(openExecutionContinuation.invoke(prompt), tools, exchanges)
            if (!round.calls && round.completed && !round.failed) {
                // If neither attempt started work, establish the selected repository through its
                // ordinary read tool. The next provider round receives the real tool result.
                firstRepositoryObservation(tools, exchanges)?.let { next ->
                    if (lookup != null || issueIntake) initialLookupIssued = true
                    lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                    emit(next)
                    emit(ProviderEvent.Completed)
                    return
                }
                // A correction can resume operations, but its raw prose has not passed
                // the selected review pipeline. Publish only the application ledger.
                emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.PROSE_ONLY_CORRECTION, failureCode = "GITHUB_TASK_INCOMPLETE"))
                return
            }
        }
        deliverDraftRound(round, tools, exchanges)
    }

    private suspend fun firstRepositoryObservation(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): ProviderEvent.ToolCall? {
        if (exchanges.any { exchange -> exchange.calls.any { it.name != GitHubAuthorizedCatalog.TOOL } } ||
            evidence.snapshot().first.isNotEmpty() ||
            remainingToolCalls()?.let { it > GitHubChecklistBudget.ordinaryVerificationReserve(executionCallLimit) } != true ||
            evidence.remainingTaskMillis() == 0L ||
            GitHubRepositoryLifecycle.intent(task) != null ||
            evidence.taskAdmissionFailure() != null
        ) {
            return null
        }
        val contract = evidence.acceptedPromptContract() ?: return null
        val repository = (contract["repository"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val trusted = tools.filter { it.modelVisible && evidence.trustsTool(it.name) }
        if (lookup != null || issueIntake) {
            val accepted = evidence.acceptedTaskRequest() ?: return null
            val request = GitHubTaskContinuity.request(task)
            if (initialLookupIssued || delegate.handlesToolsInternally || resumeClaimExtraction || GitHubTaskContinuity.request(accepted) != request || GitHubTaskContinuity.request(currentRequest) != request) return null
            val route = lookup ?: GitHubLookupRoute.Request(
                GitHubLookupRoute.ISSUES,
                number = null,
                state = "open",
                pageSize = 100,
                newestFirst = false,
                filesOnly = false,
                patches = false,
                exhaustive = true
            ).takeIf { GitHubIssueRemediation.issueOnlyObligation(request, repository) } ?: return null
            return route.firstRead(trusted, repository, (contract["accepted_branch"] as? JsonPrimitive)?.contentOrNull, verifyLive = issueIntake)
        }
        val contexts = trusted.filter { GitHubOperationTool.operationName(it.name) == "github_context" }
        if (contexts.isEmpty() || contexts.any { !it.name.githubReadFacadeMatches(contexts.first().name) }) return null
        return GitHubStatusRequest.nextCall(contexts, emptyMap(), now(), acceptedRepository = repository)
    }

    private suspend fun FlowCollector<ProviderEvent>.refreshStaleClaim(draft: String, report: JsonObject, receipts: Map<String, JsonObject>, staleIds: List<String>, tools: List<AgentToolDefinition>): Boolean {
        if (staleIds.isEmpty() ||
            report["task_outcome"] in setOf(JsonPrimitive("BLOCKED"), JsonPrimitive("CONFIGURATION_INVALIDATED")) ||
            (report["execution_ledger"] as? JsonObject)?.get("unresolved_remote_outcomes") != JsonPrimitive(0) ||
            GitHubPendingApproval.snapshot(receipts).isNotEmpty() ||
            evidence.remainingTaskMillis() == 0L
        ) {
            return false
        }
        val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls())
        if (remaining != null && remaining <= GitHubChecklistBudget.cleanupReserve(receipts)) return false
        val live = GitHubExecutionContinuation.staleRead(staleIds, tools, receipts, resumeRequestKey)
        val call = live ?: if (GitHubRequestCompletion.requiresRepositoryEvidence(task) &&
            GitHubExecutionContinuation.canResume(report, receipts, remaining, evidence.remainingTaskMillis()) &&
            (report["stage_budget"] as? JsonObject)?.get("acquisition_allowed") == JsonPrimitive(true)
        ) {
            evidence.nextPinnedSearchRead(staleIds, tools, authorizedTools)
        } else {
            null
        }
        if (call == null) return false
        validatePublication()
        evidence.retainVerdict(JsonObject(report + ("response_delivered" to JsonPrimitive(false))))
        // Fresh transport can reverify the saved answer. A pinned read advances
        // the task independently; the old search observation remains stale.
        if (live != null || call.name == GitHubAuthorizedCatalog.TOOL) refreshDraft = draft
        progress(
            when {
                live != null -> "Refreshing the expired GitHub observation before publishing the saved answer"
                call.name == GitHubAuthorizedCatalog.TOOL -> "Loading the authorized reader for the pinned source"
                else -> "Continuing from the source path and commit returned by search"
            }
        )
        lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
        emit(call)
        emit(ProviderEvent.Completed)
        return true
    }

    private suspend fun FlowCollector<ProviderEvent>.recoverGraphIndex(tools: List<AgentToolDefinition>): Boolean {
        if (!GitHubTestChecklist.integration(task) && !GitHubTaskContinuity.control(currentRequest)) return false
        val receipts = evidence.snapshot().first
        val pending = GitHubExecutionLedger.pendingGraphIndex(receipts) ?: return false
        val remaining = GitHubChecklistBudget.remaining(receipts, remainingToolCalls())
        val cleanup = GitHubChecklistBudget.cleanupReserve(receipts)
        if (remaining != null && remaining in 1..cleanup && GitHubTestChecklist.integration(task)) return false
        val call = GitHubSelfTestGraph.statusForPendingIndex(pending, tools, receipts.values, resumeRequestKey)
        if (call != null && (remaining == null || remaining > cleanup)) {
            validatePublication()
            progress("Checking the original Gateway index attempt for the selected repository revision")
            emit(call)
            emit(ProviderEvent.Completed)
        } else {
            // The earlier graph write remains unconfirmed, but native reads do
            // not share its index state. Continue the authorized audit after its
            // one bounded reconciliation read; retain the original obligation.
            // A fixture mutation or another graph operation cannot use this path.
            if (GitHubTestChecklist.integration(task) && (remaining == null || remaining > cleanup)) {
                val independent = withContext(computationDispatcher) {
                    GitHubTestChecklist.nextIndependentRead(task, tools, receipts, remainingToolCalls())
                        ?.takeIf { "view_cursor" in it.arguments || (GitHubTransportRecovery.retryNotBefore(receipts.values) ?: 0L) <= now() }
                }
                if (independent != null) {
                    validatePublication()
                    progress("Continuing independent GitHub reads while the saved graph outcome remains unconfirmed")
                    emit(independent.copy(callId = independent.callId + "-${receipts.size}"))
                    emit(ProviderEvent.Completed)
                    return true
                }
            }
            emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.INTEGRATION_CHECKPOINT))
        }
        return true
    }

    private suspend fun FlowCollector<ProviderEvent>.deliverDraftRound(round: DraftRound, tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) {
        val (draft, calls, completed, failed, providerFailure) = round
        if (failed || !completed) {
            // The runner owns retries and protocol repair. Do not turn a transient
            // failure into completion before that policy has had a chance to run.
            // Failure prose is published by retainedFailureResponse after the
            // runner's recovery decision. Publishing it here as well duplicates
            // the same terminal report when that failure reaches the runner.
            emit(providerFailure ?: ProviderEvent.Failed("GitHub response ended before verification. Receipts remain available.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
            return
        }
        if (calls || (draft.isBlank() && tools.isNotEmpty())) {
            emit(ProviderEvent.Completed)
            return
        }
        emitAll(publish(draft, GitHubAcceptancePublicationEvidence.Entry.COMPLETED_DRAFT, continuationTools = tools, continuationExchanges = exchanges))
    }

    private data class DraftRound(val draft: String, val calls: Boolean, val completed: Boolean, val failed: Boolean, val failure: ProviderEvent.Failed?)

    private fun reviewOriginFailure(failure: ProviderEvent.Failed): Boolean =
        failure.errorCode?.startsWith("GITHUB_REVIEW_") == true ||
            failure.failureDetails?.get("review_stage") in setOf(JsonPrimitive("answer_review"), JsonPrimitive("review_compatibility"))

    private fun deliverRetainedReport(): Flow<ProviderEvent> = flow {
        validatePublication()
        val saved = evidence.retainedAnswer(includeEarlierFindings = true)
        if (saved == null) {
            emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.RETAINED_REPORT_UNAVAILABLE))
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
            emit(
                ProviderEvent.Failed(
                    "The saved report was delivered. Its unfinished task requirements remain recorded; no tool operation was repeated.",
                    errorCode = "GITHUB_TASK_INCOMPLETE",
                    transportComplete = true,
                    taskOutcome = "PARTIAL",
                    failureDetails = GitHubTaskLifecycle.acceptanceFailureDetails(delivered, retainedReport = true)
                )
            )
        }
    }

    private suspend fun canRecoverRepositoryEvidence(session: AgentProviderSession, round: DraftRound, tools: List<AgentToolDefinition>, receipts: Map<String, JsonObject>): Boolean {
        val failure = round.failure ?: return false
        if (round.calls ||
            round.completed ||
            !round.failed ||
            session.handlesToolsInternally ||
            !(ciDiagnosis || GitHubRequestCompletion.informational(task)) ||
            (session as? GitHubReviewedSession)?.consumeRepositoryEvidenceFailure(failure) != true
        ) {
            return false
        }
        val contract = evidence.acceptedPromptContract() ?: return false
        val acceptedCalls = (contract["effective_tool_call_limit"] as? JsonPrimitive)?.intOrNull ?: return false
        val remaining = remainingToolCalls() ?: return false
        return contract["request_digest"] == JsonPrimitive(GitHubEvidenceBook.digest(task)) &&
            (contract["repository"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true &&
            acceptedCalls > 0 &&
            remaining <= acceptedCalls &&
            remaining > GitHubChecklistBudget.ordinaryVerificationReserve(executionCallLimit) &&
            evidence.taskAdmissionFailure() == null &&
            readOnlyEvidenceTools(tools).isNotEmpty() &&
            receipts.values.none {
                GitHubExecutionLedger.receiptState(it) in setOf(
                    GitHubExecutionLedger.ReceiptState.UNKNOWN_OUTCOME,
                    GitHubExecutionLedger.ReceiptState.DISPATCHED,
                    GitHubExecutionLedger.ReceiptState.WAITING_FOR_APPROVAL
                )
            }
    }

    private fun readOnlyEvidenceTools(tools: List<AgentToolDefinition>): List<AgentToolDefinition> = tools.filter { tool ->
        tool.modelVisible &&
            evidence.trustsTool(tool.name) &&
            tool.name.githubOperationFamily() in setOf(
                GitHubAuthorizedCatalog.TOOL, "github", "github_context", "github_ci", "github_pr", "github_read",
                "github_search", "github_issue", "github_release", "github_repository", "repository_graph", "repository_workspace"
            )
    }

    private fun draftEvents(session: AgentProviderSession, tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> {
        if (!readOnlyEvidenceRecovery) return session.streamRound(tools, exchanges)
        val blocked = ProviderEvent.Failed(
            "This evidence recovery permits only client-dispatched repository reads. The requested operation was withheld.",
            errorCode = "GITHUB_OPERATION_BLOCKED",
            taskOutcome = "BLOCKED"
        )
        if (session.handlesToolsInternally) return flowOf(blocked)
        val readTools = readOnlyEvidenceTools(tools)
        return session.streamRound(readTools, exchanges).transformWhile { event ->
            val admitted = when (event) {
                is ProviderEvent.ToolResult -> false
                is ProviderEvent.ToolCall -> {
                    val tool = readTools.singleOrNull { it.name == event.name }
                    val args = event.arguments
                    tool != null &&
                        ToolArgumentValidator.errors(args, tool.inputSchema).isEmpty() &&
                        ("view_cursor" !in args || args.keys == setOf("view_cursor")) &&
                        (
                            tool.name == GitHubAuthorizedCatalog.TOOL ||
                                GitHubAuthorizationBoundary.isReplayableRead(tool.name, args) &&
                                !GitHubAuthorizationBoundary.isBranchSelection(tool.name, args) &&
                                !(tool.name == "github" && args["action"] == JsonPrimitive("build_index"))
                            )
                }
                else -> true
            }
            emit(if (admitted) event else blocked)
            admitted
        }
    }

    private suspend fun FlowCollector<ProviderEvent>.collectDraft(session: AgentProviderSession, tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): DraftRound {
        observedAdvertisedToolCount = null
        val draft = StringBuilder()
        var calls = false
        var completed = false
        var failed = false
        var providerFailure: ProviderEvent.Failed? = null
        draftEvents(session, tools, exchanges).collect { event ->
            when (event) {
                is ProviderEvent.RequestConfigured -> {
                    event.advertisedToolCount?.takeIf { it in 0..16_000_000 }?.let { observedAdvertisedToolCount = it }
                    emit(event)
                }
                is ProviderEvent.TextDelta -> {
                    check(draft.length.toLong() + event.text.length <= 1_048_576) { "GitHub verification draft exceeds capacity" }
                    draft.append(event.text)
                }
                is ProviderEvent.ToolCall -> {
                    val schema = tools.singleOrNull { it.name == event.name }?.inputSchema
                    val verificationCall = if (reservedVerificationReads &&
                        "view_cursor" !in event.arguments &&
                        (schema?.get("properties") as? JsonObject)?.containsKey("verify_live") == true &&
                        GitHubAuthorizationBoundary.isReplayableRead(event.name, event.arguments)
                    ) {
                        event.captureProviderInvocation().copy(arguments = JsonObject(event.arguments + ("verify_live" to JsonPrimitive(true))))
                    } else {
                        event
                    }
                    val repaired = evidence.repairSourceRead(verificationCall)
                    val contract = if (ciDiagnosis) evidence.acceptedPromptContract() else null
                    val target = if (ciDiagnosis) evidence.acceptedHeadSha(workspaceHead) else null
                    val repository = (contract?.get("repository") as? JsonPrimitive)?.contentOrNull
                    val scoped = if (target != null && repository != null) {
                        dev.chungjungsoo.gptmobile.data.agent.GitHubNextActionPolicy.scopedCiRead(
                            repaired,
                            target,
                            repository,
                            (contract?.get("accepted_branch") as? JsonPrimitive)?.contentOrNull,
                            evidence.snapshot().first
                        )
                    } else {
                        repaired
                    }
                    if (scoped == null) {
                        failed = true
                        if (providerFailure == null) {
                            providerFailure = ProviderEvent.Failed(
                                "The proposed CI read targets a different revision or an older attempt. The accepted diagnosis remains scoped to `$target`; historical failures require a separate request.",
                                errorCode = "GITHUB_CI_TARGET_MISMATCH",
                                taskOutcome = "PARTIAL",
                                failureDetails = buildJsonObject {
                                    put("failed_stage", "planning")
                                    put("dispatched", false)
                                    put("accepted_head_sha", target)
                                    put("request_replay_allowed", false)
                                }
                            )
                        }
                    } else if (!failed) {
                        calls = true
                        emit(scoped)
                    }
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
        val causes = failureCauses(error)
        val policy = causes
            .filterIsInstance<dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException>().firstOrNull()
        val persistence = causes.filterIsInstance<dev.chungjungsoo.gptmobile.data.workspace.WorkspaceEvidenceConflict>().firstOrNull()
        val original = (error as? ProgressCheckpointFailure)?.originalFailure
        if (original != null) {
            val checkpoint = buildJsonObject {
                put("acceptance_checkpoint", "UNAVAILABLE")
                if (policy != null) {
                    put("checkpoint_failure_code", "DISPATCH_POLICY_CHANGED")
                    put("resume_required", true)
                }
                persistence?.event()?.failureDetails?.let { put("persistence_failure", it) }
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
        if (persistence != null) {
            emit(persistence.event())
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

    private suspend fun observeExpiredDeadline(failure: ProviderEvent.Failed) =
        failure.copy(failureDetails = JsonObject(failure.failureDetails.orEmpty() + evidence.deadlineTiming()))

    override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = flow {
        val observed = if (failure.failureDetails?.get("stop_reason") == kotlinx.serialization.json.JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")) {
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
            observeExpiredDeadline(failure)
        } else {
            failure
        }
        if (observed.failureDetails?.get("tools_withheld_for_finalization") == JsonPrimitive(true) && GitHubTestChecklist.integration(task)) {
            val receipts = evidence.snapshot().first
            val remaining = remainingToolCalls()
            val checkpoint = withContext(computationDispatcher) {
                val preflight = evidence.classifyBeforeAcquisition(task, authorizedTools, receipts, evidence.fixtureApprovalPreflight())
                GitHubTestChecklist.checkpoint(task, receipts, authorizedTools, remaining, preflight)
            }
            val interruption = dev.chungjungsoo.gptmobile.data.agent.GitHubIntegrationFinalization.failureForCheckpoint(observed, checkpoint)
            // The last allowed read may have finished the accepted work. Normal
            // publication still checks authority, retention, approval and uncertainty.
            emitAll(
                publish(
                    null,
                    GitHubAcceptancePublicationEvidence.Entry.INTEGRATION_CHECKPOINT,
                    failureCode = interruption?.errorCode,
                    originalFailure = interruption
                )
            )
            return@flow
        }
        if (failure.errorCode == "DISPATCH_POLICY_CHANGED" || failure.message.startsWith("DISPATCH_POLICY_CHANGED:")) {
            emit(failure.copy(errorCode = "DISPATCH_POLICY_CHANGED", taskOutcome = "CONFIGURATION_INVALIDATED"))
        } else if (!reviewOriginFailure(failure) && dev.chungjungsoo.gptmobile.data.agent.GitHubTaskRecovery.isConnectionUnavailable(failure.message)) {
            // A failed connection is not a claim-verification failure. Keep its actionable cause.
            val findings = evidence.retainedFindings() ?: evidence.retainedAnswer()?.takeIf { it.second["fallback"] == JsonPrimitive("receipt_only") }?.first
            if (findings != null) {
                validatePublication()
                emit(ProviderEvent.TextDelta("## Retained findings\n\n$findings\n\n**Current interruption:** ${failure.message}", dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.RETAINED_CONTENT)))
            }
            emit(failure.copy(errorCode = failure.errorCode ?: "LOCAL_SERVICE_UNREACHABLE"))
        } else {
            val (receipts, _) = evidence.snapshot()
            if (receipts.values.none { it["trusted"] == kotlinx.serialization.json.JsonPrimitive(true) }) {
                // No GitHub work was observed. A provider denial is a failed request,
                // not partial task verification, and must retain its recovery metadata.
                if (reviewOriginFailure(failure)) {
                    validatePublication()
                    emit(
                        ProviderEvent.TextDelta(
                            "The answer review could not finish. The draft remains unverified, and no GitHub completion is claimed. " +
                                "Choose Resume review to recheck the review configuration. Saved operations are retained. Explicit resumption revalidates the review configuration and reuses receipts; completed operations are not repeated.\n\nRecorded failure: `${failure.errorCode}`.",
                            dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, failure.invocationId)
                        )
                    )
                }
                emit(observed)
            } else {
                emitAll(publish(null, GitHubAcceptancePublicationEvidence.Entry.RETAINED_FAILURE, failureCode = observed.errorCode, originalFailure = observed))
            }
        }
    }

    private fun publish(
        draft: String?,
        entry: GitHubAcceptancePublicationEvidence.Entry,
        failureCode: String? = null,
        originalFailure: ProviderEvent.Failed? = null,
        continuationTools: List<AgentToolDefinition> = emptyList(),
        continuationExchanges: List<AgentToolExchange> = emptyList(),
        verificationRestart: Int = 0,
        resumeAcceptance: Boolean = false
    ): Flow<ProviderEvent> = flow {
        val verificationStarted = System.nanoTime()
        var verificationPhase = GitHubVerificationFault.Phase.DESCENDANTS
        suspend fun restartPublication(): Boolean {
            if (verificationRestart >= MAX_VERIFICATION_RESTARTS) return false
            validatePublication()
            progress("New GitHub operation evidence arrived; verifying the updated receipts")
            emitAll(
                publish(
                    draft = draft,
                    entry = entry,
                    failureCode = failureCode,
                    originalFailure = originalFailure,
                    continuationTools = continuationTools,
                    continuationExchanges = continuationExchanges,
                    verificationRestart = verificationRestart + 1,
                    resumeAcceptance = resumeAcceptance
                )
            )
            return true
        }
        val publication = try {
            // A delegated provider invocation owns all of its child tool work. Do
            // not terminalize the parent while that invocation can still append a
            // fixture cleanup or reconciliation receipt to this accepted run.
            val descendantsSettled = awaitOwnedDescendants(OWNED_DESCENDANT_WAIT_MILLIS)
            val publicationFailureCode = if (descendantsSettled) failureCode else "GITHUB_OWNED_DESCENDANTS_PENDING"
            verificationPhase = GitHubVerificationFault.Phase.AUTHORITY
            validatePublication()
            verificationPhase = GitHubVerificationFault.Phase.SNAPSHOT
            val (receipts, epoch) = evidence.snapshot()
            issueWork = evidence.issueRemediation(task)
            val stopped = originalFailure != null || remainingToolCalls() == 0
            verificationPhase = GitHubVerificationFault.Phase.RECEIPT_RENDERING
            val ciObservation = renderCiObservation(receipts, epoch) ?: if (stopped) renderCiObservation(receipts, epoch, checkSnapshotOnly = true) else null
            val lookupObservation = renderLookupObservation(receipts, epoch)
            val status = if (GitHubStatusRequest.matches(task)) {
                val acceptedContract = evidence.acceptedPromptContract()
                val acceptedCriteria = (acceptedContract?.get("acceptance_criteria") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val acceptedRepository = (acceptedContract?.get("repository") as? JsonPrimitive)?.contentOrNull
                val budget = GitHubStatusRequest.budget(evidence.stageBudget(receipts, remainingToolCalls(), 0))
                withContext(computationDispatcher) {
                    GitHubEvidencePolicy.status(receipts, now(), epoch, task, acceptedCriteria, acceptedRepository)?.let { (text, report) ->
                        val checkpoint = GitHubStatusRequest.checkpoint(statusPlanTools.ifEmpty { authorizedTools }, receipts, now(), epoch, task, acceptedRepository, report, budget)
                        val stop = checkpoint.getValue("stop_reason") as JsonObject
                        val notice = if (stop["code"] == JsonPrimitive("TOOL_BUDGET_EXHAUSTED")) "\n\n**Collection stopped:** ${(stop["detail"] as JsonPrimitive).content}" else ""
                        (text + notice) to JsonObject(report + mapOf("stage_budget" to budget, "status_checkpoint" to checkpoint, "stop_reason" to stop))
                    }
                }
            } else {
                null
            }
            val fixturePreflight = if (GitHubTestChecklist.integration(task)) evidence.fixtureApprovalPreflight() else null
            val coverage = if (GitHubTaskCoverage.matches(task)) {
                val reportingRemaining = remainingToolCalls()
                val baseBudget = evidence.stageBudget(receipts, reportingRemaining, GitHubSelfTestFixtures.minimumCalls(authorizedTools))
                val reportingBudget = if (originalFailure?.failureDetails?.get("stop_reason") == JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")) {
                    GitHubChecklistBudget.afterDeadline(baseBudget)
                } else {
                    baseBudget
                }
                withContext(computationDispatcher) {
                    val accepted = evidence.acceptedPromptContract()
                    dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace.measure("github_integration_report") {
                        GitHubEvidencePolicy.testing(receipts, now(), epoch, task, authorizedTools, reportingRemaining, reportingBudget, accepted, fixturePreflight, lastChecklist?.get("execution_plan") as? JsonObject)
                    }
                }
            } else {
                null
            }
            verificationPhase = GitHubVerificationFault.Phase.CLAIM_PLANNING
            val verifyPackagingDraft = pendingPackagingRepair(ciObservation?.second) && !draft.isNullOrBlank()
            val verificationPlan = if (draft == null || status != null || coverage != null || lookupObservation != null || ciObservation != null && !verifyPackagingDraft) {
                null
            } else {
                progress("Verifying factual claims against exact tool receipts")
                withContext(computationDispatcher) { verificationRequests(draft, receipts) }
            }
            val requests = (verificationPlan as? VerificationPlan.Ready)?.requests
            verificationPhase = GitHubVerificationFault.Phase.CLAIM_EXTRACTION
            val extraction = requests?.let { extract(requireNotNull(draft), it, receipts, epoch, verifyPackagingDraft) }
            verificationPhase = GitHubVerificationFault.Phase.ACCEPTANCE_RENDERING
            val packagingRepair = if (verifyPackagingDraft) verifiedPackagingRepair(ciObservation, extraction) else null
            val selectedCiObservation = ciObservation.takeIf { packagingRepair == null }?.let { observed ->
                if (verifyPackagingDraft && extraction?.failure != null) {
                    val failureFields = setOf("verification_failure", "extraction_batches_completed", "extraction_batches_total", "resume_required")
                    observed.first to JsonObject(observed.second + extraction.rendered.second.filterKeys { it in failureFields })
                } else {
                    observed
                }
            }
            val rendered = status ?: coverage ?: packagingRepair ?: selectedCiObservation ?: lookupObservation ?: extraction?.rendered ?: partial(receipts, epoch, publicationFailureCode ?: "GITHUB_EXTRACTION_UNAVAILABLE")
            // Claim extraction and deterministic rendering can be slower than an
            // owned tool receipt reaching the evidence book. Never publish a
            // verdict for an older epoch after new repository evidence arrives.
            // Restart below from the new durable receipts instead of cancelling
            // that work or falsely completing against a stale ledger.
            val latest = evidence.snapshot()
            val evidenceAdvanced = latest.second != epoch || latest.first.keys != receipts.keys
            if (evidenceAdvanced && restartPublication()) {
                // Restart from the exact newer epoch in this same collection. A
                // late owned cleanup must not force a second user-visible run, and
                // the stale renderer is discarded before any verdict is retained.
                return@flow
            }
            val publicationReceipts = if (evidenceAdvanced) latest.first else receipts
            val publicationEpoch = if (evidenceAdvanced) latest.second else epoch
            val (renderedText, report) = if (evidenceAdvanced) {
                val (updatedText, updatedReport) = partial(publicationReceipts, publicationEpoch, "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION", fixturePreflight)
                updatedText to JsonObject(
                    updatedReport + buildJsonObject {
                        put("verification_failure", "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION")
                        put("verification_epoch_started", epoch)
                        put("verification_epoch_observed", publicationEpoch)
                        put("verification_receipts_started", receipts.size)
                        put("verification_receipts_observed", publicationReceipts.size)
                        put("resume_required", true)
                        put("completion_verified", false)
                        put("task_outcome", "PARTIAL")
                    }
                )
            } else {
                withContext(computationDispatcher) { GitHubClaimSourceDelivery.attach(rendered, sourceDeliveryRecords) }
            }
            val (checklist, progressDigest) = withContext(computationDispatcher) {
                val checklist = (report["test_checklist"] as? JsonObject)?.let { recorded ->
                    val plan = GitHubChecklistSchedule.current(lastChecklist?.get("execution_plan") as? JsonObject, publicationReceipts)
                    if (plan == null) recorded else JsonObject(recorded + ("execution_plan" to plan))
                }
                checklist to checklist?.let { GitHubTestChecklist.progressDigest(task, publicationReceipts, authorizedTools, fixturePreflight) }
            }
            val previous = evidence.diagnostic()
            val previousProgressDigest = GitHubEvidenceBook.publishedChecklistDigest(previous)
            val stalled = progressDigest != null && previousProgressDigest == kotlinx.serialization.json.JsonPrimitive(progressDigest)
            val savedFindings = if (originalFailure != null && extraction == null && coverage == null) evidence.retainedFindings() else null
            val compactReview = report["compact_review_checkpoint"] == JsonPrimitive(true)
            val retainedFindingsDelivered = !compactReview && savedFindings != null && savedFindings != renderedText && savedFindings.length.toLong() + renderedText.length < 1_000_000
            val text = if (retainedFindingsDelivered) {
                "## Retained findings\n\n$savedFindings\n\n## Current checkpoint\n\n$renderedText"
            } else if (compactReview && savedFindings != null) {
                "$renderedText\n\nSend “Show the last useful report” to read the earlier findings."
            } else if (stalled && originalFailure == null) {
                val blockers = (checklist?.get("capabilities") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it["blocker"] != null }
                "No new test evidence or changed blockers since the saved checkpoint. ${receipts.size} receipts retained. " +
                    "Send “Show the last useful report” to reopen earlier findings. Remaining dependencies:\n\n" + blockers.distinctBy { it["blocker"] }.joinToString("\n") { "- ${(it["blocker"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()}" }
            } else {
                renderedText
            }
            verificationPhase = GitHubVerificationFault.Phase.PUBLICATION
            validatePublication()
            val retention = evidence.retentionStatus()
            val retentionComplete = retention["evidence_retention_complete"] == kotlinx.serialization.json.JsonPrimitive(true)
            val publicationBudget = report["stage_budget"] as? JsonObject ?: checklist?.get("stage_budget") as? JsonObject
                ?: evidence.stageBudget(publicationReceipts, remainingToolCalls(), GitHubSelfTestFixtures.minimumCalls(authorizedTools), report)
            val resources = if (checklist == null && GitHubRequestCompletion.requiresRepositoryEvidence(task) && report["completion_verified"] != JsonPrimitive(true)) {
                GitHubAnswerPresentation.resources(publicationBudget, authorizedTools, continuationTools)
            } else {
                null
            }
            val partial = !retentionComplete || originalFailure != null || publicationFailureCode != null || report["task_outcome"] != kotlinx.serialization.json.JsonPrimitive("COMPLETED") || report["completion_verified"] != kotlinx.serialization.json.JsonPrimitive(true)
            val oversizedWithoutEvidence = draft != null && status == null && coverage == null && ciObservation == null && lookupObservation == null && requests == null && receipts.isEmpty()
            check(!oversizedWithoutEvidence) { "No retained GitHub evidence is available for publication" }
            val trustFailures = evidence.claimTrustFailures(report, publicationReceipts)
            val retained = JsonObject(
                report + buildJsonObject {
                    progressDigest?.let { put("checklist_progress_digest", it) }
                    put("verification_restarts", verificationRestart)
                    put("stage_budget", publicationBudget)
                    resources?.let { put("execution_resources", it) }
                    put("owned_descendants_settled", descendantsSettled)
                    if (retainedFindingsDelivered) {
                        put("retained_findings_delivered", true)
                        put("retained_findings_scope", "SAME_ACCEPTED_TASK_EPOCH_AND_RECEIPT_SET")
                        put("retained_findings_sha256", GitHubEvidenceBook.digest(requireNotNull(savedFindings)))
                    }
                    put("progress_state", if (stalled) "BLOCKED_UNCHANGED" else "ADVANCED_OR_FIRST_REPORT")
                    if (checklist != null) {
                        put("test_checklist", checklist)
                        val budgetRemaining = ((checklist["stage_budget"] as? JsonObject)?.get("remaining_calls") as? JsonPrimitive)?.intOrNull
                        val stop = if (originalFailure != null || publicationFailureCode != null) {
                            buildJsonObject {
                                put("code", originalFailure?.errorCode ?: publicationFailureCode ?: "RECORDED_EXECUTION_FAILURE")
                                put("detail", originalFailure?.message ?: "The recorded failure interrupted this verification stage.")
                                put("source", "RECORDED_EXECUTION_FAILURE")
                                budgetRemaining?.let { put("remaining_tool_calls", it) }
                            }
                        } else {
                            GitHubIntegrationReport.stopReason(task, publicationReceipts, authorizedTools, budgetRemaining, checklist)
                        }
                        put("stop_reason", stop)
                    }
                    retention.forEach { (key, value) -> put(key, value) }
                    put("partial", partial)
                    val watchOwnsPolling = report["background_monitoring_registered"] == JsonPrimitive(true) &&
                        retentionComplete &&
                        originalFailure == null &&
                        publicationFailureCode == null &&
                        descendantsSettled &&
                        trustFailures.isEmpty()
                    put("resume_required", partial && !watchOwnsPolling)
                    if (partial) {
                        put("completion_verified", false)
                        val blockedOutcomes = setOf("BLOCKED", "WAITING_FOR_APPROVAL")
                        val outcome = originalFailure?.taskOutcome?.takeIf { it in blockedOutcomes }
                            ?: (report["task_outcome"] as? JsonPrimitive)?.content?.takeIf { it in blockedOutcomes }
                            ?: "PARTIAL"
                        put("task_outcome", outcome)
                    }
                    put("model_round_finished", draft != null || originalFailure?.failureDetails?.get("draft_retained") == JsonPrimitive(true))
                    if (originalFailure?.let(::reviewOriginFailure) == true) {
                        put("review_candidate_retained", privateReviewCandidate()?.isNotBlank() == true || originalFailure.failureDetails?.get("draft_retained") == JsonPrimitive(true))
                        put("delivery_mode", "DETERMINISTIC_RECEIPT_PARTIAL")
                        put("additional_verification_model_dispatched", false)
                    }
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
                                failure.failureDetails?.get("recovery_action")?.let { put("recovery_action", it) }
                                if (failure.failureDetails?.get("stop_reason") == JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")) {
                                    (checklist?.get("pagination_resume") as? JsonObject)?.get("next_call")?.let { put("next_call_after_renewal", it) }
                                }
                                remainingToolCalls()?.let { put("remaining_tool_calls", it) }
                            }
                        )
                        put(
                            "original_failure",
                            buildJsonObject {
                                put("code", failure.errorCode.orEmpty())
                                failure.invocationId?.let { put("invocation_id", it) }
                                put("message", failure.message)
                                put("transport_complete", failure.transportComplete)
                                failure.failureDetails?.forEach { (key, value) -> put(key, value) }
                            }
                        )
                    }
                    if (partial && "verification_failure" !in report) {
                        put(
                            "verification_failure",
                            publicationFailureCode ?: if (!retentionComplete) {
                                "GITHUB_EVIDENCE_RETENTION_INCOMPLETE"
                            } else if (coverage != null || report["claims_verified"] == kotlinx.serialization.json.JsonPrimitive(true)) {
                                "GITHUB_TASK_INCOMPLETE"
                            } else {
                                "GITHUB_EVIDENCE_REQUIRED"
                            }
                        )
                    }
                    val publicationSource = when {
                        evidenceAdvanced -> GitHubAcceptancePublicationEvidence.Source.EVIDENCE_ADVANCED
                        status != null -> GitHubAcceptancePublicationEvidence.Source.STATUS
                        coverage != null -> GitHubAcceptancePublicationEvidence.Source.COVERAGE
                        selectedCiObservation != null -> GitHubAcceptancePublicationEvidence.Source.CI_OBSERVATION
                        issueIntake && lookupObservation != null -> GitHubAcceptancePublicationEvidence.Source.ISSUE_INTAKE
                        lookupObservation?.second?.get("schema") == JsonPrimitive("github.lookup-report.v1") -> GitHubAcceptancePublicationEvidence.Source.LOOKUP_OBSERVATION
                        lookupObservation != null -> GitHubAcceptancePublicationEvidence.Source.STATUS
                        extraction != null -> GitHubAcceptancePublicationEvidence.Source.CLAIM_EXTRACTION
                        else -> GitHubAcceptancePublicationEvidence.Source.PARTIAL_FALLBACK
                    }
                    val rejectedPlan = verificationPlan as? VerificationPlan.Rejected
                    val extractionObservation = when {
                        evidenceAdvanced -> null
                        status != null || coverage != null || lookupObservation != null || ciObservation != null && !verifyPackagingDraft -> GitHubAcceptancePublicationEvidence.Extraction.NOT_NEEDED
                        draft == null -> GitHubAcceptancePublicationEvidence.Extraction.NO_DRAFT
                        rejectedPlan != null -> rejectedPlan.observation
                        extraction?.failure != null -> GitHubAcceptancePublicationEvidence.Extraction.FAILED
                        extraction != null -> GitHubAcceptancePublicationEvidence.Extraction.COMPLETED
                        else -> null
                    }
                    put(
                        GitHubAcceptancePublicationEvidence.KEY,
                        GitHubAcceptancePublicationEvidence.record(
                            publicationSource,
                            publicationReceipts.size,
                            draft,
                            extractionObservation,
                            verificationPlan?.plannedBatches,
                            rejectedPlan?.requiredTokens,
                            rejectedPlan?.inputLimitTokens,
                            entry,
                            trustFailures,
                            advertisedToolCount = observedAdvertisedToolCount
                        )
                    )
                }
            )
            val execution = evidence.withNextAction(GitHubExecutionLedger.attach(GitHubAcceptanceLedger.attach(retained, publicationReceipts), publicationReceipts))
            val executionLedger = execution["execution_ledger"] as? JsonObject
            val unresolved = executionLedger?.get("unresolved_remote_outcomes") as? kotlinx.serialization.json.JsonPrimitive
            val reconciliationRequired = unresolved?.content?.toIntOrNull()?.let { it > 0 } == true
            val effectivePartial = partial || executionLedger?.get("requested_task") != kotlinx.serialization.json.JsonPrimitive("VERIFIED_COMPLETE") || execution["task_outcome"] != kotlinx.serialization.json.JsonPrimitive("COMPLETED")
            val effectiveReport = JsonObject(
                execution + buildJsonObject {
                    put("response_delivered", false)
                    if (effectivePartial) {
                        put("partial", true)
                        put("resume_required", true)
                        put("completion_verified", false)
                        if (execution["task_outcome"] == kotlinx.serialization.json.JsonPrimitive("COMPLETED")) put("task_outcome", "PARTIAL")
                    }
                    if (reconciliationRequired && "verification_failure" !in execution) put("verification_failure", "RECONCILIATION_REQUIRED")
                }
            )
            if (evidence.retainVerdictIfCurrent(effectiveReport, publicationEpoch, publicationReceipts.keys) == null) {
                if (restartPublication()) return@flow
                emit(
                    ProviderEvent.Failed(
                        "GitHub evidence continued changing while acceptance was being verified. All operation receipts remain durable; resume verification after the owned work settles.",
                        errorCode = "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION",
                        taskOutcome = "PARTIAL"
                    )
                )
                return@flow
            }
            val cause = originalFailure?.let {
                val heading = if (it.failureDetails?.get("stop_reason") == kotlinx.serialization.json.JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")) "Time limit reached" else "Task paused"
                "**$heading.** ${it.message}\n\n"
            }.orEmpty()
            val packagingEvidence = if (GitHubCiObservationReport.matches(task) && effectivePartial) {
                GitHubCiSizeGate.retained(publicationReceipts).values.distinct().takeLast(3)
                    .filter { gate -> !text.contains(gate.getValue("job_id").toString()) || !text.contains(gate.getValue("over_budget_bytes").toString()) }
                    .joinToString("") { "\n\n" + GitHubCiSizeGate.describe(it) }
            } else {
                ""
            }
            val failures = if (GitHubTaskCoverage.boundedDiagnostic(task)) withContext(computationDispatcher) { GitHubFailureSummary.render(publicationReceipts) } else ""
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
            Publication(
                cause + reconciliationNotice + approvalNotice + GitHubEffectiveRecovery.publicationText(text, effectiveReport, publicationReceipts) + packagingEvidence + resources?.let { GitHubAnswerPresentation.resourceNotice(task, it) }.orEmpty() + failures + retentionNotice,
                effectivePartial,
                effectiveReport,
                publicationEpoch,
                publicationReceipts.keys,
                extraction?.failure?.takeUnless { evidenceAdvanced }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val causes = failureCauses(failure)
            causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            val persistence = causes.filterIsInstance<dev.chungjungsoo.gptmobile.data.workspace.WorkspaceEvidenceConflict>().firstOrNull()
            if (persistence != null) {
                // A local save failure must not hide an already uncertain remote
                // operation or be mislabeled as a provider/verification failure.
                val local = persistence.event()
                emit(originalFailure?.copy(failureDetails = JsonObject(originalFailure.failureDetails.orEmpty() + ("persistence_failure" to requireNotNull(local.failureDetails)))) ?: local)
                return@flow
            }
            causes.firstOrNull {
                it is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged ||
                    it is dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied
            }?.let { throw it }
            val policy = causes.filterIsInstance<dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException>().firstOrNull()
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
            }
            // Revalidate access before reading/publishing even the minimal saved
            // evidence report. Do not call the failed renderer a second time.
            try {
                validatePublication()
            } catch (refused: Exception) {
                failureCauses(refused).firstOrNull {
                    it is CancellationException ||
                        it is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException ||
                        it is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged ||
                        it is dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied
                }?.let { throw it }
                emit(GitHubVerificationFault.publicationDenied(refused, originalFailure))
                return@flow
            }
            val (saved, epoch) = evidence.snapshot()
            val fallback = GitHubVerificationFault.partial(failure, verificationPhase, saved, originalFailure)
            Publication(fallback.text, true, fallback.report, epoch, saved.keys, fallback.failure)
        }
        // Collector exceptions must propagate unchanged; they are not formatter failures.
        val (text, partial, rawReport) = publication
        val retained = evidence.snapshot().first
        val internalFault = rawReport["verification_internal_error"] == JsonPrimitive(true)
        if (!internalFault) evidenceRoutes.observed(rawReport)
        val report = if (internalFault) rawReport else JsonObject(rawReport + ("evidence_route_observations" to evidenceRoutes.diagnostics()))
        suspend fun advancedInternalFault(responseDelivered: Boolean): ProviderEvent.Failed {
            val (currentReceipts, currentEpoch) = evidence.snapshot()
            val failure = requireNotNull(publication.extractionFailure)
            return failure.copy(
                message = failure.message + if (responseDelivered) {
                    " More evidence arrived during delivery. The partial report describes its captured receipt set; it does not verify the newer evidence."
                } else {
                    " More evidence arrived before the partial report could be delivered. The older report was withheld; inspect the saved results and verification diagnostic before resuming."
                },
                failureDetails = JsonObject(
                    failure.failureDetails.orEmpty() + buildJsonObject {
                        put("checkpoint_failure_code", "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION")
                        put("verification_epoch_started", publication.epoch)
                        put("verification_epoch_observed", currentEpoch)
                        put("verification_receipts_started", publication.receiptIds.size)
                        put("verification_receipts_observed", currentReceipts.size)
                        put("response_delivered", responseDelivered)
                        put("completion_verified", false)
                    }
                )
            )
        }
        val currentProgress = if (internalFault) emptySet() else GitHubExecutionContinuation.progressKeys(retained)
        val staleReceiptIds = if (internalFault) emptyList() else GitHubExecutionContinuation.staleReceiptIds(report)
        if (partial &&
            draft != null &&
            originalFailure == null &&
            publication.extractionFailure == null &&
            refreshStaleClaim(draft, report, retained, staleReceiptIds, continuationTools)
        ) {
            return@flow
        }
        // The verifier can discover a missing proof after the model has already finished its first draft.
        if (partial &&
            entry == GitHubAcceptancePublicationEvidence.Entry.COMPLETED_DRAFT &&
            draft != null &&
            originalFailure == null &&
            publication.extractionFailure == null &&
            !delegate.handlesToolsInternally &&
            !executionCorrectionAttempted &&
            progressCorrections == 0 &&
            retained.isEmpty() &&
            GitHubExecutionContinuation.canResume(report, retained, remainingToolCalls(), evidence.remainingTaskMillis(), initialRead = true)
        ) {
            firstRepositoryObservation(continuationTools, continuationExchanges)?.let { next ->
                validatePublication()
                evidence.retainVerdict(JsonObject(report + ("response_delivered" to JsonPrimitive(false))))
                if (lookup != null || issueIntake) initialLookupIssued = true
                executionCorrectionAttempted = true
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                emit(next)
                emit(ProviderEvent.Completed)
                return@flow
            }
        }
        val newEvidenceProgress = (currentProgress - correctionProgress).isNotEmpty()
        // A new explicit continuation starts with the saved receipts already in correctionProgress.
        // Permit one attempt to collect the missing proof within the original acquisition budget.
        val ledgerRequestsWork = (report["execution_ledger"] as? JsonObject)?.get("recovery") == JsonPrimitive("RESUME_UNFINISHED_WORK")
        val verificationRead = dev.chungjungsoo.gptmobile.data.agent.GitHubNextActionPolicy.verificationOnly(report)
        val requestedCorrection = (resumeAcceptance || ledgerRequestsWork && !executionCorrectionAttempted) &&
            progressCorrections == 0 &&
            ((report["stage_budget"] as? JsonObject)?.get("acquisition_allowed") == JsonPrimitive(true) || verificationRead)
        val currentSessionReview = GitHubRequestCompletion.requiresLiveSessionReviewRefresh(task)
        if (partial &&
            (
                report["observation_state"] != JsonPrimitive("WAITING_FOR_CI") ||
                    dev.chungjungsoo.gptmobile.data.agent.GitHubNextActionPolicy.activeDecision(report)?.get("reason") == JsonPrimitive("INDEPENDENT_DURING_CI")
                ) &&
            originalFailure == null &&
            publication.extractionFailure == null &&
            openExecutionContinuation != null &&
            continuationTools.isNotEmpty() &&
            GitHubExecutionContinuation.hasAcquisitionBudget(remainingToolCalls(), evidence.remainingTaskMillis(), report) &&
            (newEvidenceProgress || requestedCorrection) &&
            (staleReceiptIds.isEmpty() || !currentSessionReview && ledgerRequestsWork) &&
            (GitHubRequestCompletion.requiresRepositoryEvidence(task) || currentSessionReview || ciDiagnosis) &&
            GitHubExecutionContinuation.canResume(
                report,
                retained,
                remainingToolCalls(),
                evidence.remainingTaskMillis(),
                allowVerificationResume = newEvidenceProgress
            )
        ) {
            // Keep the exact acceptance gaps durable before another selected-profile round.
            // The existing runner still owns tool admission, original budgets and mutation deduplication.
            evidence.retainVerdict(JsonObject(report + mapOf("response_delivered" to kotlinx.serialization.json.JsonPrimitive(false))))
            correctionProgress = currentProgress
            progressCorrections++
            validatePublication()
            refreshVerificationCriteria(report)
            reservedVerificationReads = verificationRead
            readOnlyEvidenceRecovery = verificationRead || ciDiagnosis || GitHubRequestCompletion.requiresRepositoryEvidence(task) && GitHubRequestCompletion.informational(task)
            progress("Continuing the first unfinished acceptance criterion from saved receipts")
            val correction = collectDraft(
                openExecutionContinuation.invoke(
                    GitHubExecutionContinuation.prompt(
                        task,
                        currentRequest,
                        retained,
                        report,
                        readOnlyRefresh = currentSessionReview || readOnlyEvidenceRecovery,
                        budget = continuationBudget?.invoke(),
                        additionalInstruction = if (verificationRead) " Only read the missing proof for the selected accepted criterion. These are reserved verification calls; do not edit, select a branch, create resources or repeat completed operations." else ""
                    )
                ),
                continuationTools,
                continuationExchanges
            )
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
        val prepared = try {
            evidence.retainAnswerIfCurrent(text, report, publication.epoch, publication.receiptIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ProgressCheckpointFailure(failure, originalFailure)
        }
        if (!prepared) {
            if (internalFault) {
                // A stale emergency snapshot cannot re-enter the failed normal
                // renderer. Preserve the typed fault without publishing old facts.
                emit(advancedInternalFault(responseDelivered = false))
                return@flow
            }
            if (restartPublication()) return@flow
            emit(
                ProviderEvent.Failed(
                    "GitHub evidence continued changing before the verified answer could be delivered. All operation receipts remain durable; resume verification after the owned work settles.",
                    errorCode = "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION",
                    taskOutcome = "PARTIAL"
                )
            )
            return@flow
        }
        emit(ProviderEvent.TextDelta(text, dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT)))
        val delivered = evidence.retainVerdictIfCurrent(
            value = JsonObject(
                report + buildJsonObject {
                    put("response_delivered", true)
                    put("response_sha256", GitHubEvidenceBook.digest(text))
                }
            ),
            expectedEpoch = publication.epoch,
            expectedReceiptIds = publication.receiptIds
        )
        if (delivered == null) {
            if (internalFault) {
                emit(advancedInternalFault(responseDelivered = true))
                return@flow
            }
            emit(
                ProviderEvent.Failed(
                    "GitHub evidence changed during answer delivery. Resume verification to include the new receipts.",
                    errorCode = "GITHUB_EVIDENCE_ADVANCED_DURING_VERIFICATION",
                    taskOutcome = "PARTIAL"
                )
            )
            return@flow
        }
        onPublication(delivered)
        val unresolved = ((delivered["execution_ledger"] as? JsonObject)?.get("unresolved_remote_outcomes") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()?.let { it > 0 } == true
        val extractionCode = (delivered["failure_code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeUnless { it == "null" }
        val waitingApproval = (delivered["execution_ledger"] as? JsonObject)?.get("recovery") == kotlinx.serialization.json.JsonPrimitive("WAITING_FOR_APPROVAL")
        // Only the extraction attempted by this publication owns its live failure
        // and invocation. Its claim checkpoint is resumed separately from primary
        // generation; it cannot authorize generic provider-wait replay.
        val currentFailure = publication.extractionFailure?.takeIf { internalFault } ?: originalFailure ?: publication.extractionFailure?.takeUnless { unresolved }
        val approvalIsPrimary = waitingApproval && currentFailure == null && !unresolved
        val monitoring = delivered["background_monitoring_registered"] == JsonPrimitive(true) &&
            currentFailure == null &&
            !unresolved &&
            !waitingApproval &&
            delivered["evidence_retention_complete"] == JsonPrimitive(true)
        if (monitoring) {
            // The interactive observation is delivered. The durable background watch owns further polls.
            emit(ProviderEvent.Completed)
            return@flow
        }
        if (partial || delivered["completion_verified"] != kotlinx.serialization.json.JsonPrimitive(true)) {
            lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.VERIFICATION_REJECTED)
            emit(
                ProviderEvent.Failed(
                    if (approvalIsPrimary) "Review the saved change to continue." else currentFailure?.message ?: GitHubExecutionContinuation.incompleteMessage(delivered, evidence.snapshot().first),
                    errorCode = if (unresolved) {
                        "RECONCILIATION_REQUIRED"
                    } else if (currentFailure != null) {
                        currentFailure.errorCode ?: failureCode ?: "GITHUB_TASK_INCOMPLETE"
                    } else if (approvalIsPrimary) {
                        "WAITING_FOR_APPROVAL"
                    } else {
                        failureCode ?: extractionCode ?: (delivered["verification_failure"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "GITHUB_TASK_INCOMPLETE"
                    },
                    transportComplete = currentFailure?.transportComplete ?: true,
                    providerLimit = originalFailure?.providerLimit?.takeIf { currentFailure === originalFailure }?.takeUnless { unresolved },
                    invocationId = currentFailure?.invocationId,
                    taskOutcome = when {
                        approvalIsPrimary || currentFailure?.taskOutcome == "BLOCKED" -> "BLOCKED"
                        // A deterministic prerequisite stop can block without a provider/tool failure.
                        currentFailure == null && !unresolved && delivered["task_outcome"] == JsonPrimitive("BLOCKED") -> "BLOCKED"
                        else -> "PARTIAL"
                    },
                    failureDetails = if (approvalIsPrimary) {
                        buildJsonObject {
                            put("failed_stage", "approval")
                            put("failure_domain", "APPROVAL")
                            put("dispatched", false)
                            put("user_input_required", true)
                            put("retryable", false)
                            put("pending_approvals", delivered["pending_approvals"] ?: (delivered["execution_ledger"] as? JsonObject)?.get("pending_approvals") ?: JsonArray(emptyList()))
                        }
                    } else if (currentFailure?.failureDetails?.get("stop_reason") == JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")) {
                        JsonObject(GitHubAcceptanceReportDiagnostics.fields(delivered, retainedReport = true) + currentFailure.failureDetails.orEmpty())
                    } else {
                        currentFailure?.failureDetails ?: GitHubTaskLifecycle.acceptanceFailureDetails(delivered, progressCorrections, retainedReport = publication.extractionFailure == null)
                    }
                )
            )
        } else {
            emit(ProviderEvent.Completed)
        }
    }

    private data class Publication(
        val text: String,
        val partial: Boolean,
        val report: JsonObject,
        val epoch: Long,
        val receiptIds: Set<String>,
        val extractionFailure: ProviderEvent.Failed? = null
    )

    private data class ClaimExtraction(
        val rendered: Pair<String, JsonObject>,
        val failure: ProviderEvent.Failed? = null,
        val repairRecommendationVerified: Boolean = false
    )

    private fun pendingPackagingRepair(report: JsonObject?): Boolean =
        report != null &&
            report["observation_state"] == JsonPrimitive("OBSERVED_PACKAGING_FAILURE") &&
            report["completion_verified"] != JsonPrimitive(true)

    private fun verifiedPackagingRepair(observation: Pair<String, JsonObject>?, extraction: ClaimExtraction?): Pair<String, JsonObject>? {
        if (!pendingPackagingRepair(observation?.second) || extraction == null || extraction.failure != null) return null
        val observed = requireNotNull(observation).second
        val gate = observed["apk_size_failure"] as? JsonObject ?: return null
        val (text, report) = extraction.rendered
        val repairId = JsonPrimitive(GitHubRequestCompletion.id(GitHubWorkflowPreset.selected("diagnose_ci").acceptance.last()))
        val repairPassed = (report["requirements"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .any { it["id"] == repairId && it["status"] == JsonPrimitive("PASSED") }
        if (!repairPassed || !extraction.repairRecommendationVerified) return null
        // A verified recommendation completes only answer coverage. Preserve the
        // actual failed package and never turn advice into a new build result.
        val observedFields = setOf(
            "observation_state",
            "completion_scope",
            "head_sha",
            "apk_size_failure",
            "evidence_receipt_ids",
            "full_history_verified",
            "build_health_verified",
            "new_test_execution_verified"
        )
        return (GitHubCiSizeGate.describe(gate) + "\n\n" + text) to JsonObject(report + observed.filterKeys { it in observedFields })
    }

    private suspend fun partial(receipts: Map<String, JsonObject>, epoch: Long, failureCode: String? = null, fixturePreflight: JsonObject? = null, inspectExtractorFailure: Boolean = false): Pair<String, JsonObject> = withContext(computationDispatcher) {
        val budget = evidence.stageBudget(receipts, remainingToolCalls(), GitHubSelfTestFixtures.minimumCalls(authorizedTools))
        val remaining = (budget["remaining_calls"] as? JsonPrimitive)?.intOrNull
        val preflight = fixturePreflight ?: if (GitHubTestChecklist.integration(task)) evidence.fixtureApprovalPreflight() else null
        GitHubEvidencePolicy.partial(receipts, now(), epoch, task, failureCode, authorizedTools, remaining, budget, evidence.acceptedPromptContract(), preflight, inspectExtractorFailure, issueWork = evidence.issueRemediation(task))
    }

    private suspend fun claimExtractionContext(receipts: Map<String, JsonObject>, epoch: Long): String = withContext(computationDispatcher) {
        val identity = receipts.entries.sortedBy { it.key }.joinToString("\n") { (id, receipt) ->
            "$id:${receipt["source_result_sha256"]}:${receipt["verification_eligible"]}:${receipt["epoch"]}:${receipt["observed_at_ms"]}"
        }
        GitHubEvidenceBook.digest("$task\n$epoch\n$identity")
    }

    private suspend fun extract(draft: String, requests: List<GitHubClaimBatches.Request>, receipts: Map<String, JsonObject>, epoch: Long, verifyPackagingDraft: Boolean): ClaimExtraction {
        val context = claimExtractionContext(receipts, epoch)
        val schemaHash = GitHubEvidenceBook.digest(GitHubClaimBatches.responseSchemaJson())
        val plan = withContext(computationDispatcher) {
            GitHubEvidenceBook.digest("github.claim-extraction.v2|$schemaHash|$epoch|" + requests.joinToString("\n") { GitHubEvidenceBook.digest(it.payload) })
        }
        val saved = evidence.retainedClaimExtraction()
        val savedBatches = (saved?.get("batches") as? JsonArray).orEmpty()
        val savedRequirementIds = (saved?.get("batch_requirement_ids") as? JsonArray).orEmpty().map { value ->
            (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.toSet()
        }
        val samePlan = saved != null && saved["context"] == JsonPrimitive(context) && saved["plan"] == JsonPrimitive(plan)
        val completed = MutableList<JsonObject?>(requests.size) { null }
        requests.forEachIndexed { index, request ->
            val priorIndex = if (samePlan) index else savedRequirementIds.indexOfFirst { it == request.requirementIds }
            val prior = savedBatches.getOrNull(priorIndex) as? JsonObject ?: return@forEachIndexed
            val validated = runCatching { GitHubClaimBatches.validate(prior, request) }.getOrNull() ?: return@forEachIndexed
            val stillValid = samePlan ||
                withContext(computationDispatcher) {
                    GitHubEvidencePolicy.canReuseExtractionBatch(validated, receipts, now(), epoch, task)
                }
            if (stillValid) completed[index] = validated
        }
        fun completedCount() = completed.count { it != null }
        val requirementIds = requests.map { it.requirementIds }
        var singleClaimBatch = (saved?.get("single_claim_batch") as? JsonPrimitive)?.intOrNull?.takeIf {
            samePlan && saved["pending"] == JsonPrimitive(true) && it in 1..requests.size && completed[it - 1] == null
        }
        suspend fun saveBatches(pending: Boolean) = evidence.retainClaimBatches(context, plan, draft, completed, requirementIds, pending, singleClaimBatch)
        val attempt = GitHubClaimExtraction.Attempt()
        suspend fun failed(
            code: String,
            providerFailure: ProviderEvent.Failed? = null,
            observations: JsonObject? = null,
            rejectedLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null
        ): ClaimExtraction {
            val inspectTimeout = code == "GITHUB_EXTRACTION_TIMEOUT" && providerFailure?.transportComplete == false
            val (text, report) = partial(receipts, epoch, code, inspectExtractorFailure = inspectTimeout)
            val retained = JsonObject(
                report + buildJsonObject {
                    put("extraction_batches_completed", completedCount())
                    put("extraction_batches_total", requests.size)
                    put("extraction_next_batch", completed.indexOfFirst { it == null }.takeIf { it >= 0 }?.plus(1) ?: requests.size + 1)
                    put("extraction_windows_total", (requests.size + GitHubClaimBatches.MAX_BATCHES - 1) / GitHubClaimBatches.MAX_BATCHES)
                    put("resume_required", true)
                    put(
                        "verification_failure",
                        buildJsonObject {
                            providerFailure?.failureDetails?.forEach { (key, value) -> put(key, value) }
                            observations?.forEach { (key, value) -> put(key, value) }
                            put("claim_extraction_schema_hash", schemaHash)
                            put("code", code)
                            put("failed_stage", "claim_extraction")
                            put(
                                "recovery_action",
                                if (rejectedLimit != null) {
                                    "RESUME_UNFINISHED_EXTRACTION_BATCHES_AFTER_RETRY_AT"
                                } else {
                                    when (code) {
                                        "GITHUB_EXTRACTION_OUTPUT_LIMIT" -> GitHubClaimExtraction.outputLimitRecovery(observations)
                                        "GITHUB_EXTRACTION_INVALID_RESPONSE" -> "RESUME_THIS_BATCH_WITH_STRICT_SCHEMA"
                                        "GITHUB_EXTRACTION_TIMEOUT" -> if (inspectTimeout) "INSPECT_THE_RECORDED_PROVIDER_FAILURE" else "RESUME_UNFINISHED_EXTRACTION_BATCHES"
                                        else -> "INSPECT_THE_RECORDED_PROVIDER_FAILURE"
                                    }
                                }
                            )
                            rejectedLimit?.let { limit ->
                                put("retry_at_ms", limit.retryAt)
                                put("provider_wait_no_wait_reason", "CLAIM_EXTRACTION_CHECKPOINT_REQUIRED")
                                put("provider_wait_decision_state", "NOT_SCHEDULED")
                                put("provider_wait_scheduler_state", "NOT_SCHEDULED")
                                put("provider_wait_persisted", false)
                            }
                            providerFailure?.invocationId?.let { put("invocation_id", it) }
                            providerFailure?.errorCode?.let { put("provider_error_code", it) }
                            providerFailure?.let { put("transport_complete", it.transportComplete) }
                            val nextBatch = completed.indexOfFirst { it == null }
                            if (nextBatch >= 0) put("batch", nextBatch + 1)
                            providerFailure?.message?.let { put("message", it) }
                        }
                    )
                }
            )
            val details = JsonObject(
                (retained.getValue("verification_failure") as JsonObject) + buildJsonObject {
                    // Claim recovery resumes this checkpoint. The underlying
                    // provider phase remains evidence, not a new primary stage.
                    providerFailure?.failureDetails?.get("failed_stage")?.let { put("provider_failed_stage", it) }
                    put("failed_stage", "claim_extraction")
                    put("review_stage", "claim_extraction")
                    put("reason_code", code)
                    put("logical_continuation_allowed", false)
                    put("automatic_retry_allowed", false)
                    put("resume_required", true)
                    put("extraction_batches_completed", completedCount())
                    put("extraction_batches_total", requests.size)
                }
            )
            val stageMessage = if (rejectedLimit != null) {
                "The provider rate limited claim verification. The repository observations and ${completedCount()} of ${requests.size} verified batches are saved. Resume only the unfinished extraction batches after the recorded retry time."
            } else {
                when (code) {
                    "GITHUB_EXTRACTION_OUTPUT_LIMIT" -> if (GitHubClaimExtraction.outputLimitRecovery(observations) == "UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST") {
                        "Claim verification reached its output limit even with one claim. Review the selected profile's reasoning setting and output allowance, then resume the unfinished batch. The repository observations and completed claim batches are saved."
                    } else {
                        "Claim verification reached its output limit. The repository observations and completed claim batches are saved."
                    }
                    "GITHUB_EXTRACTION_INVALID_RESPONSE" -> "Claim verification received invalid structured output. The repository observations and completed claim batches are saved."
                    "CONNECTION_ABORTED", "TRANSPORT_EOF", "INCOMPLETE_PROVIDER_STREAM" -> "The provider stream ended during claim verification. Saved ${completedCount()} of ${requests.size} verified batches and the original repository observations. Inspect the interrupted request before resuming the unfinished batch."
                    else -> null
                }
            }
            val failure = providerFailure?.copy(message = stageMessage ?: providerFailure.message, errorCode = code, failureDetails = details) ?: ProviderEvent.Failed(
                stageMessage ?: "Claim extraction did not finish. Saved evidence and unfinished batches remain available.",
                errorCode = code,
                transportComplete = code in setOf("GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_TIMEOUT"),
                taskOutcome = "PARTIAL",
                failureDetails = details
            )
            return ClaimExtraction(text to retained, failure)
        }
        return try {
            for (window in requests.indices.chunked(GitHubClaimBatches.MAX_BATCHES)) {
                if (window.all { completed[it] != null }) continue
                if (requests.size > GitHubClaimBatches.MAX_BATCHES) progress("Verifying evidence window ${window.first() / GitHubClaimBatches.MAX_BATCHES + 1} of ${(requests.size + GitHubClaimBatches.MAX_BATCHES - 1) / GitHubClaimBatches.MAX_BATCHES}")
                // Each bounded window gets a finite extraction allowance. The accepted
                // task deadline and publication authority are checked before every batch.
                val finished = withTimeoutOrNull(90_000) {
                    saveBatches(pending = true)
                    for (index in window) {
                        if (completed[index] != null) continue
                        validatePublication()
                        evidence.taskAdmissionFailure()?.let { throw GitHubClaimExtraction.Failure(it.errorCode ?: "GITHUB_TASK_INCOMPLETE", it) }
                        val request = requests[index]
                        val selected = if (singleClaimBatch == index + 1) GitHubClaimBatches.withClaimLimit(request, 1) else request
                        val result = extractBatch(selected, attempt) {
                            singleClaimBatch = index + 1
                            saveBatches(pending = true)
                        }
                        validatePublication()
                        completed[index] = result
                        singleClaimBatch = null
                        saveBatches(pending = true)
                    }
                    true
                }
                if (finished != true) return failed(attempt.terminalFailure?.errorCode ?: "GITHUB_EXTRACTION_TIMEOUT", attempt.failureAtDeadline())
            }
            withTimeoutOrNull(90_000) {
                val batches = completed.map { requireNotNull(it) }
                val result = withContext(computationDispatcher) {
                    val combined = GitHubClaimBatches.combine(batches)
                    val at = now()
                    val acceptedContract = evidence.acceptedPromptContract()
                    val rendered = GitHubEvidencePolicy.render(combined, receipts, at, epoch, task, acceptedContract = acceptedContract, issueWork = issueWork)
                    val repairId = GitHubRequestCompletion.id(GitHubWorkflowPreset.selected("diagnose_ci").acceptance.last())
                    val mapped = ((combined["requirement_claims"] as? JsonObject)?.get(repairId) as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet()
                    val recommendations = (combined["claims"] as? JsonArray).orEmpty().filterIndexed { index, claim ->
                        index in mapped && claim is JsonObject && claim["kind"] == JsonPrimitive("recommendation")
                    }
                    // Keep the exact requirement-to-claim mapping before rendering
                    // can omit optional claims. Verify only its recommendations by
                    // the same policy; sharing a receipt is not a claim binding.
                    val repairVerified = verifyPackagingDraft &&
                        recommendations.isNotEmpty() &&
                        (
                            GitHubEvidencePolicy.render(
                                buildJsonObject { put("claims", JsonArray(recommendations)) },
                                receipts,
                                at,
                                epoch,
                                task,
                                acceptedContract = acceptedContract,
                                issueWork = issueWork
                            ).second["claims"] as? JsonArray
                            ).orEmpty().filterIsInstance<JsonObject>()
                            .any { it["kind"] == JsonPrimitive("recommendation") && it["status"] == JsonPrimitive("INFERRED") }
                    val progressReport = JsonObject(
                        rendered.second + buildJsonObject {
                            put("extraction_batches_completed", completedCount())
                            put("extraction_batches_total", requests.size)
                            put("extraction_next_batch", requests.size + 1)
                            put("extraction_windows_total", (requests.size + GitHubClaimBatches.MAX_BATCHES - 1) / GitHubClaimBatches.MAX_BATCHES)
                        }
                    )
                    ClaimExtraction(rendered.first to progressReport, repairRecommendationVerified = repairVerified)
                }
                saveBatches(pending = false)
                result
            } ?: failed(attempt.terminalFailure?.errorCode ?: "GITHUB_EXTRACTION_TIMEOUT", attempt.failureAtDeadline())
        } catch (failure: GitHubClaimExtraction.Failure) {
            failed(failure.code, failure.providerFailure, failure.observations, GitHubClaimExtraction.rejectedDecision(failure))
        }
    }

    private suspend fun extractBatch(request: GitHubClaimBatches.Request, attempt: GitHubClaimExtraction.Attempt, saveOutputLimit: suspend () -> Unit): JsonObject {
        var current = request
        val rejectionRecovery = GitHubClaimExtraction.RejectionRecovery()
        while (true) {
            try {
                return GitHubClaimExtraction.collect(current, openVerifier, computationDispatcher, attempt)
            } catch (failure: GitHubClaimExtraction.Failure) {
                if (rejectionRecovery.retry(failure, now, validatePublication)) continue
                if (!failure.repairAllowed || failure.code !in setOf("GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE") || current.maxClaims <= 1) throw failure
                val outputLimited = failure.code == "GITHUB_EXTRACTION_OUTPUT_LIMIT"
                val limit = if (outputLimited) 1 else maxOf(1, current.maxClaims / 2)
                val reduced = GitHubClaimBatches.withClaimLimit(current, limit, invalidResponse = failure.code == "GITHUB_EXTRACTION_INVALID_RESPONSE")
                val available = inputLimit()
                current = if (TextTokenEstimate.estimate(reduced.payload) <= available) reduced else GitHubClaimBatches.withClaimLimit(request, limit)
                if (TextTokenEstimate.estimate(current.payload) > available) throw failure
                if (outputLimited) saveOutputLimit()
                validatePublication()
            }
        }
    }

    private sealed interface VerificationPlan {
        val plannedBatches: Int

        data class Ready(val requests: List<GitHubClaimBatches.Request>) : VerificationPlan {
            override val plannedBatches: Int get() = requests.size
        }

        data class Rejected(
            val observation: GitHubAcceptancePublicationEvidence.Extraction,
            override val plannedBatches: Int,
            val requiredTokens: Int? = null,
            val inputLimitTokens: Int? = null
        ) : VerificationPlan
    }

    private fun verificationRequests(draft: String, receipts: Map<String, JsonObject>): VerificationPlan {
        val hardLimit = inputLimit()
        val groups = GitHubIssueRemediation.verificationWindow(task, issueWork, receipts, now(), evidence.semanticSteps()).chunked(GitHubClaimBatches.REQUIREMENTS_PER_BATCH)
        val criterionContracts = GitHubAcceptanceContract.criteria(task, GitHubRequestCompletion.requirements(task, issueWork, evidence.semanticSteps())).associateBy { it.id }
        val catalog by lazy { JsonArray(GitHubEvidencePolicy.catalog(receipts).reversed()) }
        val review = GitHubReviewComparison.plan(task, emptyList(), receipts)?.checkpoint
        val mandatory = GitHubVerificationCatalog.mandatory(catalog, review)
        val requests = groups.mapIndexed { index, requirements ->
            // Never replay a history-sized saved report into the extractor. Completed
            // reads remain in the evidence ledger; this bounded excerpt is only context.
            var candidate = draftExcerpt(draft, requirements)
            val ids = requirements.map(GitHubRequestCompletion::id)
            fun payload(selected: List<kotlinx.serialization.json.JsonElement>, excerpt: String = candidate) = buildJsonObject {
                put("task", task)
                put("batch", index + 1)
                put("batches", groups.size)
                put("window", index / GitHubClaimBatches.MAX_BATCHES + 1)
                put("windows", (groups.size + GitHubClaimBatches.MAX_BATCHES - 1) / GitHubClaimBatches.MAX_BATCHES)
                put("claim_limit", GitHubClaimBatches.MAX_CLAIMS)
                put(
                    "requirements",
                    JsonArray(
                        requirements.zip(ids).map { (text, id) ->
                            buildJsonObject {
                                put("id", id)
                                put("text", text)
                                criterionContracts[id]?.let { put("criterion_contract", it.json()) }
                            }
                        }
                    )
                )
                put("candidate", excerpt)
                review?.let {
                    put("review_state", it)
                    put("review_state_policy", "The retained decision, exact revision pair and comparison coverage are authoritative task state. Completed comparison prerequisites are distinct from pending source review. Do not ask for an already selected baseline or describe retained revisions as unavailable.")
                }
                put("claim_selection", "Address the listed requirement IDs only. Prioritize the smallest set of evidenced claims needed to answer them.")
                put("catalog", JsonArray(selected))
                put("retained_receipts", receipts.size)
                put("omitted_from_context", receipts.size - selected.size)
                put("coverage", "Full originals remain in the encrypted ledger. Omitted source is not proof; unresolved claims must remain Not measured.")
            }.toString()
            var request = payload(mandatory)
            var baseTokens = TextTokenEstimate.estimate(request)
            if (baseTokens > hardLimit && candidate.isNotEmpty() && TextTokenEstimate.estimate(payload(mandatory, "")) <= hardLimit) {
                // Preserve the entire task and criterion scaffold; fit only the selected draft context.
                var low = 0
                var high = candidate.codePointCount(0, candidate.length)
                while (low < high) {
                    val count = (low + high + 1) / 2
                    val prefix = candidate.substring(0, candidate.offsetByCodePoints(0, count))
                    if (TextTokenEstimate.estimate(payload(mandatory, prefix)) <= hardLimit) low = count else high = count - 1
                }
                val fitted = candidate.substring(0, candidate.offsetByCodePoints(0, low))
                if (fitted.isNotBlank()) {
                    candidate = fitted
                    request = payload(mandatory)
                    baseTokens = TextTokenEstimate.estimate(request)
                }
            }
            if (baseTokens > hardLimit) return VerificationPlan.Rejected(GitHubAcceptancePublicationEvidence.Extraction.PLAN_INPUT_LIMIT, groups.size, baseTokens, hardLimit)
            val limit = minOf(hardLimit, maxOf(6_000, baseTokens + 2_048))
            val query = "$task\n${requirements.joinToString("\n")}\n$candidate"
            val selected = mandatory.toMutableList<kotlinx.serialization.json.JsonElement>()
            for (entry in GitHubVerificationCatalog.select(catalog, query)) {
                if (mandatory.any { it["receipt_id"] == entry["receipt_id"] }) continue
                val next = payload(selected + entry)
                if (TextTokenEstimate.estimate(next) <= limit) {
                    selected += entry
                    request = next
                }
            }
            GitHubClaimBatches.Request(request, ids.toSet())
        }
        val currentLimit = inputLimit()
        var rejection: VerificationPlan.Rejected? = null
        val admitted = requests.all { request ->
            val estimate = TextTokenEstimate.estimate(request.payload)
            (estimate <= currentLimit).also { fits ->
                if (!fits) rejection = VerificationPlan.Rejected(GitHubAcceptancePublicationEvidence.Extraction.PLAN_LIMIT_CHANGED, groups.size, estimate, currentLimit)
            }
        }
        return if (admitted) VerificationPlan.Ready(requests) else requireNotNull(rejection)
    }

    private fun draftExcerpt(draft: String, requirements: List<String>): String {
        val terms = Regex("[A-Za-z0-9_]{4,}").findAll(requirements.joinToString(" ").lowercase()).map { it.value }.toSet()
        return draft.lineSequence().take(2048).filter(String::isNotBlank).map { it.take(900) }.withIndex()
            .sortedByDescending { line -> terms.count { term -> line.value.contains(term, ignoreCase = true) } }
            .take(10).sortedBy { it.index }.joinToString("\n") { it.value }.take(3600)
    }

    private companion object {
        const val OWNED_DESCENDANT_WAIT_MILLIS = 30_000L
        const val MAX_VERIFICATION_RESTARTS = 2
    }
}
