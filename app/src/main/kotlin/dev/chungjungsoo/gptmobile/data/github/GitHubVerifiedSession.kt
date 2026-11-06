package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    private val remainingToolCalls: () -> Int? = { null }
) : AgentProviderSession by delegate {
    private var lastChecklist: JsonObject? = null
    private class ProgressCheckpointFailure(cause: Exception, val originalFailure: ProviderEvent.Failed?) : Exception(cause)

    private suspend fun retainProgress(tools: List<AgentToolDefinition>, failure: ProviderEvent.Failed? = null) {
        try {
            withContext(computationDispatcher) {
                evidence.retainProgress(task, authorizedTools.ifEmpty { tools }, failure?.let { it.errorCode ?: "MODEL_GENERATION_FAILED" }, failure)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val accessFailure = generateSequence<Throwable>(error) { it.cause }.firstOrNull {
                it is dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException ||
                    it is dev.chungjungsoo.gptmobile.data.context.ProviderWaitAuthorityChanged ||
                    it is dev.chungjungsoo.gptmobile.data.agent.SavedEvidenceAccessDenied
            }
            if (accessFailure != null) throw accessFailure
            throw ProgressCheckpointFailure(error, failure)
        }
    }

    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
        val intent = dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract.from(GitHubTaskContinuity.request(task))
        if (intent.conversational) {
            emit(ProviderEvent.TextDelta(intent.acknowledgement()))
            emit(ProviderEvent.Completed)
            return@flow
        }
        exchanges.forEach { exchange -> exchange.results.forEach { result -> exchange.calls.firstOrNull { it.callId == result.callId }?.let { evidence.observe(it, result) } } }
        if (!evidence.complete()) {
            emitAll(publish(null, failureCode = "GITHUB_EVIDENCE_RETENTION_INCOMPLETE"))
            return@flow
        }
        if (GitHubTaskContinuity.diagnostic(task)) {
            validatePublication()
            val (receipts, _) = evidence.snapshot()
            emit(ProviderEvent.TextDelta(GitHubTaskContinuity.explain(evidence.diagnostic(), receipts)))
            emit(ProviderEvent.Completed)
            return@flow
        }
        if (isGatewayIntegrationTask(task)) {
            validatePublication()
            val report = gatewayDiagnostic?.invoke() ?: "Gateway integration: unavailable. Configure an engineering gateway connection in GitHub settings."
            validatePublication()
            emit(ProviderEvent.TextDelta(report))
            emit(ProviderEvent.Completed)
            return@flow
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
        if (GitHubTestChecklist.integration(task) && (tools.isNotEmpty() || evidence.snapshot().first.values.any { it["trusted"] == kotlinx.serialization.json.JsonPrimitive(true) })) {
            val receipts = evidence.snapshot().first
            val (checkpoint, planned, missing) = withContext(computationDispatcher) {
                Triple(
                    GitHubTestChecklist.checkpoint(task, receipts, authorizedTools, remainingToolCalls()),
                    GitHubTestChecklist.nextCall(task, tools, receipts, remainingToolCalls()),
                    authorizedTools.filter { definition -> definition.name.startsWith("github_") && tools.none { it.name == definition.name } && receipts.values.none { it["tool"] == kotlinx.serialization.json.JsonPrimitive(definition.name) } }.take(12)
                )
            }
            val discovery = tools.firstOrNull { it.name == "discover_available_tools" }
            val discovered = receipts.values.any { it["tool"] == kotlinx.serialization.json.JsonPrimitive("discover_available_tools") }
            val discoveryArgs = if (!discovered) JsonObject(emptyMap()) else buildJsonObject { put("names", JsonArray(missing.map { kotlinx.serialization.json.JsonPrimitive(it.name) })) }
            val discoveryAlreadyTried = receipts.values.any { it["tool"] == kotlinx.serialization.json.JsonPrimitive("discover_available_tools") && it["arguments"] == discoveryArgs }
            val acquisitionAllowed = GitHubChecklistBudget.remaining(receipts, remainingToolCalls())?.let { it > GitHubChecklistBudget.VERIFICATION_RESERVE } != false
            val next = if (acquisitionAllowed && discovery != null && (!discovered || planned == null && missing.isNotEmpty()) && !discoveryAlreadyTried) ProviderEvent.ToolCall("checklist-discovery-" + GitHubEvidenceBook.digest(discoveryArgs.toString()).take(20), discovery.name, discoveryArgs) else planned
            if (checkpoint != lastChecklist) {
                if (receipts.isNotEmpty()) withContext(computationDispatcher) { evidence.retainChecklist(checkpoint) }
                lastChecklist = checkpoint
                if (receipts.isNotEmpty()) {
                    progress(GitHubTestChecklist.progressSummary(checkpoint, next))
                }
            }
            if (next != null) {
                lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.CONTINUATION_SELECTED)
                emit(next.copy(callId = next.callId + "-${receipts.size}"))
                emit(ProviderEvent.Completed)
                return@flow
            }
            // Coverage fields come exclusively from the cumulative ledger. No model draft
            // or answer rewrite can supply missing executions or unlock a blocked dependency.
            emitAll(publish(null))
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
        val draft = StringBuilder()
        var calls = false
        var completed = false
        var failed = false
        var providerFailure: ProviderEvent.Failed? = null
        delegate.streamRound(tools, exchanges).collect { event ->
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
        if (failed || !completed) {
            // The runner owns retries and protocol repair. Do not turn a transient
            // failure into completion before that policy has had a chance to run.
            if (!calls && providerFailure?.errorCode?.startsWith("GITHUB_REVIEW_") == true) {
                val candidate = privateReviewCandidate()?.takeIf { it.isNotBlank() }
                val (receipts, _) = evidence.snapshot()
                if (candidate != null && receipts.values.any { it["trusted"] == kotlinx.serialization.json.JsonPrimitive(true) }) {
                    // The failed review cannot publish prose. Independently extract
                    // receipt-backed claims from the private candidate instead.
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
        emitAll(publish(draft.toString()))
    }.catch { error ->
        // Flow.catch preserves collector failures and cancellation. A failed
        // progress checkpoint stops work before any further provider/tool action.
        if (error !is ProgressCheckpointFailure) throw error
        val original = error.originalFailure
        if (original != null) {
            emit(original.copy(failureDetails = JsonObject(original.failureDetails.orEmpty() + ("acceptance_checkpoint" to kotlinx.serialization.json.JsonPrimitive("UNAVAILABLE")))))
        } else {
            val dispatched = evidence.snapshot().first.values.any { it["dispatched"] == kotlinx.serialization.json.JsonPrimitive(true) }
            emit(
                ProviderEvent.Failed(
                    "The GitHub acceptance checkpoint could not be retained. No completion is claimed. Restore local storage and reconcile recorded operations before resuming.",
                    errorCode = "GITHUB_EVIDENCE_RETENTION_INCOMPLETE",
                    taskOutcome = if (dispatched) "PARTIAL" else "BLOCKED"
                )
            )
        }
    }

    override fun retainedFailureResponse(failure: ProviderEvent.Failed): Flow<ProviderEvent> = flow {
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
                                "Saved operations are retained. Retry resumes only the bounded review recovery; it does not repeat a commit.\n\nRecorded failure: `${failure.errorCode}`."
                        )
                    )
                }
                emit(failure)
            } else {
                emitAll(publish(null, failureCode = failure.errorCode, originalFailure = failure))
            }
        }
    }

    private fun publish(draft: String?, failureCode: String? = null, originalFailure: ProviderEvent.Failed? = null): Flow<ProviderEvent> = flow {
        val verificationStarted = System.nanoTime()
        val publication = try {
            validatePublication()
            val (receipts, epoch) = evidence.snapshot()
            val status = if (GitHubStatusRequest.matches(task)) {
                withContext(computationDispatcher) { GitHubEvidencePolicy.status(receipts, now(), epoch, task) }
            } else {
                null
            }
            val coverage = if (GitHubTaskCoverage.matches(task)) {
                withContext(computationDispatcher) {
                    GitHubEvidencePolicy.testing(receipts, now(), epoch, task, authorizedTools)
                }
            } else {
                null
            }
            val request = if (draft == null || status != null || coverage != null) {
                null
            } else {
                progress("Verifying factual claims against exact tool receipts")
                withContext(computationDispatcher) { verificationRequest(draft, receipts) }
            }
            val (renderedText, report) = status ?: coverage ?: if (request == null) partial(receipts, epoch, failureCode ?: "GITHUB_EXTRACTION_UNAVAILABLE") else extract(request, receipts, epoch)
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
            val oversizedWithoutEvidence = draft != null && status == null && coverage == null && request == null && receipts.isEmpty()
            check(!oversizedWithoutEvidence) { "No retained GitHub evidence is available for publication" }
            val retained = JsonObject(
                report + buildJsonObject {
                    progressDigest?.let { put("checklist_progress_digest", it) }
                    put("progress_state", if (stalled) "BLOCKED_UNCHANGED" else "ADVANCED_OR_FIRST_REPORT")
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
                        GitHubTaskLifecycle.failureMetadata(failure).forEach { (key, value) -> put(key, value) }
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
            val execution = GitHubExecutionLedger.attach(GitHubAcceptanceLedger.attach(retained), receipts)
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
            val cause = originalFailure?.let { "**Task interrupted:** ${it.message}\n\n" }.orEmpty()
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
            Publication(cause + reconciliationNotice + text + failures + retentionNotice, effectivePartial, effectiveReport)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val policy = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException>().firstOrNull()
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
                    emit(ProviderEvent.TextDelta("The task was interrupted before verification finished. Completed operations remain saved; no completion is claimed.\n\nOriginal failure: ${originalFailure.message}\n\nVerification also could not finish: ${failure.message.orEmpty().take(400)}"))
                    emit(originalFailure)
                } else {
                    emit(ProviderEvent.Failed("GitHub answer withheld: ${failure.message.orEmpty().take(400)}. Not available / Not measured. Retained evidence can be inspected or refreshed.", errorCode = "GITHUB_EVIDENCE_REQUIRED"))
                }
            }
            return@flow
        }
        // Collector exceptions must propagate unchanged; they are not formatter failures.
        val (text, partial, report) = publication
        emit(ProviderEvent.TextDelta(text))
        evidence.retainVerdict(JsonObject(report + ("response_delivered" to kotlinx.serialization.json.JsonPrimitive(true))))
        onPublication(report)
        val extractionCode = (report["failure_code"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeUnless { it == "null" }
        if (partial) {
            lifecycle(dev.chungjungsoo.gptmobile.data.diagnostics.RunDiagnosticKind.VERIFICATION_REJECTED)
            emit(
                ProviderEvent.Failed(
                    originalFailure?.message ?: "Partial response delivered. The requested task remains incomplete; saved receipts and the task checkpoint are available for continuation.",
                    errorCode = failureCode ?: extractionCode ?: (report["verification_failure"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "GITHUB_TASK_INCOMPLETE",
                    transportComplete = true,
                    taskOutcome = if (originalFailure?.taskOutcome == "BLOCKED") "BLOCKED" else "PARTIAL",
                    failureDetails = originalFailure?.failureDetails
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

    private suspend fun extract(request: String, receipts: Map<String, JsonObject>, epoch: Long): Pair<String, JsonObject> = try {
        withTimeoutOrNull(90_000) {
            val response = StringBuilder()
            var verifiedComplete = false
            openVerifier(request).streamRound(emptyList(), emptyList()).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        check(response.length.toLong() + event.text.length <= 131_072) { "Claim extraction exceeded its output bound" }
                        response.append(event.text)
                    }
                    is ProviderEvent.Completed -> verifiedComplete = true
                    is ProviderEvent.Failed -> throw ExtractionFailed(event)
                    is ProviderEvent.ToolCall, is ProviderEvent.ToolResult -> error("Evidence formatting attempted an operation")
                    else -> Unit
                }
            }
            check(verifiedComplete) { "Claim extraction was interrupted" }
            withContext(computationDispatcher) {
                val answer = Json.parseToJsonElement(response.toString().trim()) as? JsonObject ?: error("Claim extraction was not structured")
                GitHubEvidencePolicy.render(answer, receipts, now(), epoch, task)
            }
        } ?: partial(receipts, epoch, "GITHUB_EXTRACTION_TIMEOUT")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: ExtractionFailed) {
        val (text, report) = partial(receipts, epoch, failed.failure.errorCode ?: "GITHUB_EXTRACTION_FAILED")
        text to JsonObject(
            report + (
                "verification_failure" to buildJsonObject {
                    put("code", failed.failure.errorCode.orEmpty())
                    put("message", failed.failure.message)
                    put("failed_stage", "claim_extraction")
                    failed.failure.failureDetails?.forEach { (key, value) -> put(key, value) }
                }
                )
        )
    } catch (_: Exception) {
        // Permission, ledger and persistence failures remain outside this boundary.
        partial(receipts, epoch, "GITHUB_EXTRACTION_INVALID_RESPONSE")
    }

    private class ExtractionFailed(val failure: ProviderEvent.Failed) : IllegalStateException(failure.message)

    private fun verificationRequest(draft: String, receipts: Map<String, JsonObject>): String? {
        val hardLimit = inputLimit()
        fun payload(catalog: List<kotlinx.serialization.json.JsonElement>) = buildJsonObject {
            put("task", task)
            put(
                "requirements",
                JsonArray(
                    GitHubRequestCompletion.requirements(task).map { requirement ->
                        buildJsonObject {
                            put("id", GitHubRequestCompletion.id(requirement))
                            put("text", requirement)
                        }
                    }
                )
            )
            put("candidate", draft)
            put("catalog", JsonArray(catalog))
            put("retained_receipts", receipts.size)
            put("omitted_from_context", receipts.size - catalog.size)
            put("coverage", "Full originals remain in the encrypted ledger. Omitted source is not proof; unresolved claims must remain Not measured.")
        }.toString()
        var request = payload(emptyList())
        val baseTokens = TextTokenEstimate.estimate(request)
        if (baseTokens > hardLimit) return null
        val limit = minOf(hardLimit, maxOf(6_000, baseTokens + 2_048))
        val catalog = GitHubVerificationCatalog.select(kotlinx.serialization.json.JsonArray(GitHubEvidencePolicy.catalog(receipts).reversed()), "$task\n$draft")
        val selected = mutableListOf<kotlinx.serialization.json.JsonElement>()
        // A large receipt must not prevent later, smaller evidence from being considered.
        for (entry in catalog) {
            val candidate = payload(selected + entry)
            if (TextTokenEstimate.estimate(candidate) <= limit) {
                selected += entry
                request = candidate
            }
        }
        return request.takeIf { TextTokenEstimate.estimate(it) <= inputLimit() }
    }
}
