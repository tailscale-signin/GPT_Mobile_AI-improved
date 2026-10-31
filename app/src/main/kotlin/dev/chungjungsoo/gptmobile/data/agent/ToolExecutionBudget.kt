package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** One budget per user turn, shared by every bound tool, including the native engine bridge. */
class ToolExecutionBudget(
    private val limits: AgentRunLimits,
    private val evidenceBytesPerResult: Int? = null,
    private val statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository? = null,
    private val statisticsProfile: String = "",
    private val statisticsRoot: String = "",
    private val combined: CombinedExecutionContext? = null,
    private val combinedBudgets: CombinedTurnBudgetStore? = null,
    private val failureMessage: (Exception) -> String = { "Tool execution failed. Check the connection diagnostics before repeating an action." }
) {
    private val terminalToolFailures = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val unknownOutcomes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val calls = AtomicInteger()
    private val attempts = AtomicInteger()
    private val invocationIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val completed = AtomicInteger()
    private val remainingBytes = AtomicInteger(limits.maxToolOutputBytes)
    private val permits = Semaphore(limits.maxConcurrentTools.coerceAtLeast(1))
    private val executionLimit = ToolBudgetPolicy.executionLimit(limits)
    private val combinedMode = combined != null || limits.combined != null

    // GitHub delivers bounded saved-view pages; a cumulative excerpt allowance
    // must not stop traversal after three 8 KiB pages. The call ceiling,
    // per-result bound and provider replay/context admission still apply.
    private val separateResultAllowance = limits.researchAcquisition || combinedMode || limits.githubTask && limits.maxToolOutputBytes > 0

    fun canExecute(): Boolean = (separateResultAllowance || remainingBytes.get() > 0) && !callBudgetIsExhausted()
    fun remainingOutputBytes(): Int = if (separateResultAllowance) limits.maxToolOutputBytes.coerceAtLeast(0) else remainingBytes.get().coerceAtLeast(0)

    fun bind(
        tool: AgentTool,
        onFinished: suspend (String, Boolean) -> Unit = { _, _ -> },
        statisticsIdentity: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity.of(tool.definition.name),
        authorize: suspend (String, JsonObject) -> Boolean = { _, _ -> true }
    ): AgentTool = bindPrepared(tool.withChildBudget { child -> bind(child) }, onFinished, statisticsIdentity, authorize)

    private fun bindPrepared(
        tool: AgentTool,
        onFinished: suspend (String, Boolean) -> Unit = { _, _ -> },
        statisticsIdentity: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity.of(tool.definition.name),
        authorize: suspend (String, JsonObject) -> Boolean = { _, _ -> true }
    ): AgentTool = object : PreparableAgentTool, OwnedAgentTool {
        override val definition = tool.definition
        override val managesExecutionBudget = true
        override val coordinatesChildWork = tool.coordinatesChildWork
        override val executionOwner = (tool as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()

        override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
            fun failure(message: String) = ToolResultEnvelope.error(callId, "BUDGET_EXHAUSTED", message)
            AgentContractValidation.admission(definition, callId)?.let { rejected -> return { withBudgetState(rejected) } }
            val inherited = currentCoroutineContext()[CombinedToolDispatchScope]
            val activeRole = inherited?.context ?: currentCoroutineContext()[CombinedRunScope]?.context
            val boundContext = combined ?: limits.combined
            require(activeRole == null || boundContext == null || activeRole.rootBudgetId == boundContext.rootBudgetId) { "A tool cannot move between accepted Combined turns." }
            val executionContext = activeRole ?: boundContext
            val physicalIdentity = "$executionOwner:$callId"
            val alreadyBudgeted = inherited?.context?.rootBudgetId == executionContext?.rootBudgetId && inherited?.identity == physicalIdentity
            val observedLimit = if (executionContext == null) limits.maxObservedToolCalls.coerceIn(1, 1024) else limits.maxObservedToolCalls.coerceAtLeast(0)
            if ((!limits.researchAcquisition && !alreadyBudgeted && attempts.incrementAndGet() > observedLimit) || callId.length > 256 || boundedJsonBytes(arguments, 16 * 1024) > 16 * 1024) {
                return { withBudgetState(ToolResultEnvelope.error(callId.take(256), "TOOL_BATCH_OVERSIZE", "Observed tool attempt or argument limit reached. No action dispatched.")) }
            }
            val invocation = "$executionOwner:${tool.definition.name}:$callId"
            if (!invocationIds.add(invocation)) return { withBudgetState(ToolResultEnvelope.error(callId, "DUPLICATE_INVOCATION", "This invocation ID was already admitted. No action repeated.")) }
            val signature = "$executionOwner:${tool.definition.name}:" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(arguments)
            val replayableRead = AgentToolCapability.USER_DECISION in tool.definition.capabilities ||
                dev.chungjungsoo.gptmobile.data.research.ResearchTimeouts.isAcademicRead(tool.definition.name) ||
                dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(tool.definition.name, arguments)
            if (signature in unknownOutcomes) return { withBudgetState(ToolResultEnvelope.error(callId, "RECONCILIATION_REQUIRED", "Previous dispatch outcome is unknown. Reconcile it before repeating this action.")) }
            if ((failures[signature]?.get() ?: 0) >= 2) return { withBudgetState(ToolResultEnvelope.error(callId, "REPEATED_FAILURE", "An unchanged call failed twice. Stop repeating it; use retained evidence or correct the request.")) }
            terminalToolFailures[tool.definition.name]?.let { code ->
                return { withBudgetState(ToolResultEnvelope.error(callId, code, "This tool's permission or authentication failed earlier in this turn. Review its grants or credentials before starting a new turn; changing arguments will not retry it.")) }
            }
            val invalid = ToolArgumentValidator.errors(arguments, tool.definition.inputSchema)
            if (invalid.isNotEmpty()) {
                val message = ToolArgumentValidator.repairMessage(tool.definition.name, invalid, tool.definition.inputSchema)
                return { withBudgetState(ToolResultEnvelope.error(callId, "INVALID_ARGUMENTS", message)) }
            }
            if (!alreadyBudgeted && !tryAcquireCall(enforceResearchLimit = executionContext != null)) {
                val message = toolCallBudgetMessage()
                AppLogRecorder.record(
                    "ToolBudget",
                    "Tool-call limit blocked execution · tool=${tool.definition.name} · call=$callId · used=${calls.get()} · executableLimit=$executionLimit · configured=${limits.maxToolCalls} · reserved=${limits.finalResponseToolCallReserve}",
                    "W"
                )
                return {
                    withBudgetState(
                        failure(message).copy(
                            traceContent = ToolResultContent.Text(message),
                            toolCallBudgetExhausted = true
                        )
                    )
                }
            }
            if (!separateResultAllowance && remainingBytes.get() <= 0) {
                val message = outputBudgetMessage()
                AppLogRecorder.record(
                    "ToolBudget",
                    "Tool-result byte budget blocked execution · tool=${tool.definition.name} · call=$callId · configuredBytes=${limits.maxToolOutputBytes}",
                    "W"
                )
                return {
                    withBudgetState(
                        failure(message).copy(
                            traceContent = ToolResultContent.Text(message),
                            outputBudgetExhausted = true
                        )
                    )
                }
            }
            val authorized = try {
                authorize(callId, arguments)
            } catch (rejected: dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected) {
                if (ToolResultEnvelope.isAuthorizationRejection(rejected.code)) terminalToolFailures[tool.definition.name] = rejected.code
                return { bounded(ToolResultEnvelope.error(callId, rejected.code, rejected.message.orEmpty())) }
            }
            if (!authorized) {
                terminalToolFailures[tool.definition.name] = "AUTH_REQUIRED"
                return { bounded(ToolResultEnvelope.error(callId, "AUTH_REQUIRED", "Tool permission was denied or revoked. Review this tool's grants before retrying.")) }
            }
            val dispatched = java.util.concurrent.atomic.AtomicBoolean()
            return execution@{
                if (!dispatched.compareAndSet(false, true)) return@execution withBudgetState(ToolResultEnvelope.error(callId, "DUPLICATE_INVOCATION", "This prepared tool call was already dispatched. No action was repeated."))
                var success = false
                var statisticsId: String? = null
                var startedNanos = 0L
                var executionOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.UNKNOWN
                var deliveryOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.FAILED
                var reason: String? = null
                var shared = false
                var producer: String? = null
                var sharedBudget: CombinedTurnBudget? = null
                var reservationId: String? = null
                var combinedResult: AgentToolResult? = null
                var physicalToolStarted = false
                var assignedWorkId: String? = null
                var physicalContext = executionContext
                val workUnits = CombinedToolWork.units(arguments)
                suspend fun invokeObserved(): AgentToolResult {
                    // Admission and semaphore waiting do not count as uses.
                    currentCoroutineContext().ensureActive()
                    if (executionContext != null && !alreadyBudgeted) {
                        val store = combinedBudgets ?: CombinedTurnBudgetStore.installedOrNull()
                            ?: throw CombinedBudgetExceeded("Combined budget store is unavailable; no tool was dispatched.")
                        val budget = store.open(executionContext)
                        if (budget.snapshot().let { it.cancelled || it.closed }) throw CombinedBudgetExceeded("Combined turn is closed; no tool was dispatched.")
                        assignedWorkId = budget.assignEvidenceWork(executionContext, arguments.toString(), CombinedToolWork.isInspection(tool.definition))
                        if (assignedWorkId == null && tool.coordinatesChildWork && AgentToolCapability.WEB_SEARCH !in tool.definition.capabilities && CombinedWorkInventory.urls(arguments.toString()).isEmpty()) {
                            assignedWorkId = budget.assignedContext(executionContext).workItemId
                        }
                        physicalContext = assignedWorkId?.let { executionContext.copy(workItemId = it) } ?: executionContext
                        sharedBudget = budget
                        if (!tool.coordinatesChildWork) {
                            val attemptId = "tool:${executionContext.role}:${executionContext.profileUid}:$statisticsRoot:$physicalIdentity"
                            val effectFingerprint = if (replayableRead || CombinedToolWork.isInspection(tool.definition) || AgentToolCapability.WEB_SEARCH in tool.definition.capabilities) null else combinedDigest(statisticsIdentity.key + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(arguments))
                            when (val admission = budget.reserve(requireNotNull(physicalContext), attemptId, CombinedResourceQuota(toolOperations = workUnits), effectFingerprint = effectFingerprint)) {
                                is CombinedBudgetAdmission.Denied -> return failure(admission.reason).copy(toolCallBudgetExhausted = true)
                                is CombinedBudgetAdmission.Granted -> {
                                    sharedBudget = budget
                                    reservationId = attemptId
                                }
                            }
                        }
                    }
                    val attributedProfile = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsProfile]?.uid ?: statisticsProfile
                    statisticsId = statistics?.begin(statisticsIdentity, attributedProfile, statisticsRoot, executionOwner.name, callId)
                    startedNanos = System.nanoTime()
                    val recorder = statistics
                    val recordedId = statisticsId
                    val sharedContext = physicalContext?.let { CombinedToolDispatchScope(it, physicalIdentity) } ?: kotlin.coroutines.EmptyCoroutineContext
                    val observed = withContext(sharedContext) {
                        currentCoroutineContext().ensureActive()
                        physicalToolStarted = true
                        if (recorder != null && recordedId != null) withContext(dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsContext(recorder, recordedId, statisticsIdentity.key, attributedProfile, statisticsRoot)) { tool.execute(callId, arguments) } else tool.execute(callId, arguments)
                    }
                    // A receipt with another call ID cannot establish this call's outcome,
                    // even if that unrelated receipt claims it was not dispatched.
                    val result = AgentContractValidation.receipt(callId, observed)
                    currentCoroutineContext()[ToolReceiptObserver]?.observe?.invoke(ProviderEvent.ToolCall(callId, tool.definition.name, arguments), result)
                    return result.also { raw ->
                        combinedResult = raw
                        if (!raw.dispatched) {
                            statistics?.rejectedBeforeDispatch(statisticsId)
                            statisticsId = null
                        }
                        shared = raw.sharedResult
                        producer = raw.statisticsProducerId
                        executionOutcome = when {
                            raw.errorCode in ToolResultEnvelope.unknownOutcomeCodes -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.UNKNOWN
                            raw.isError -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
                            else -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.SUCCESS
                        }
                        reason = if (raw.isError) raw.errorCode ?: "TOOL_ERROR" else null
                    }
                }
                try {
                    val result = if (tool.managesExecutionBudget) {
                        // Orchestrators own their deadline; their children use this same budget.
                        // Holding a permit here would deadlock nested calls at concurrency = 1.
                        invokeObserved()
                    } else {
                        permits.withPermit {
                            if (!canExecute() && remainingBytes.get() <= 0) return@withPermit failure(outputBudgetMessage())
                            withContext(ToolOutputAllowance(minOf(16 * 1024, remainingOutputBytes() / 4))) {
                                withTimeoutOrNull(if (limits.researchAcquisition) dev.chungjungsoo.gptmobile.data.research.ResearchTimeouts.operationMillis(tool.definition.name, limits.toolTimeoutMillis) else limits.toolTimeoutMillis) { invokeObserved() }
                            }
                                ?: if (replayableRead
                                ) {
                                    ToolResultEnvelope.error(callId, "READ_DEADLINE", "Read deadline reached; completed evidence is retained. Retry this read with a new call ID or narrow its scope; write actions are never replayed automatically.", dispatched = true)
                                } else {
                                    ToolResultEnvelope.error(callId, "TIMEOUT_OUTCOME_UNKNOWN", "Tool timed out after dispatch. Reconcile the outcome before repeating an action.", dispatched = true)
                                }
                        }
                    }
                    if (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes) reason = result.errorCode
                    if (result.errorCode == "READ_DEADLINE") {
                        combinedResult = result
                        reason = result.errorCode
                        executionOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
                    }
                    val boundedResult = bounded(
                        result,
                        preserveSuccessfulHandoff = tool.definition.name == DELEGATION_TOOL_NAME,
                        observationLimitBytes = if (tool.definition.name in ACADEMIC_ACQUISITION_TOOLS) 256 * 1024 else 64 * 1024
                    )
                    val normalizedResult = if (
                        tool.definition.name == DELEGATION_TOOL_NAME &&
                        boundedResult.isError &&
                        boundedResult.hasSuccessfulDelegationMarker()
                    ) {
                        AppLogRecorder.record(
                            "Delegation",
                            "Corrected contradictory execution-budget error after successful delegated handoff · call=$callId",
                            "W"
                        )
                        boundedResult.copy(isError = false)
                    } else {
                        boundedResult
                    }
                    if (normalizedResult.isError) {
                        val code = normalizedResult.errorCode
                        if (ToolResultEnvelope.isAuthorizationRejection(code)) {
                            terminalToolFailures[tool.definition.name] = requireNotNull(code)
                        } else if (tool.definition.name !in setOf("read_url", "deep_research_read_paper", "firecrawl_scrape", "tavily_extract", "web_fetch_exa") &&
                            Regex("(?i)HTTP\\s*401|status(?:\\s+code)?[\\s:=]+401|invalid api key|authentication failed").containsMatchIn(normalizedResult.content.toString())
                        ) {
                            terminalToolFailures[tool.definition.name] = "AUTH_FAILED"
                        }
                    }
                    if (normalizedResult.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead) unknownOutcomes += signature
                    if (normalizedResult.isError && normalizedResult.dispatched && normalizedResult.errorCode !in setOf("AUTH_REQUIRED", "BUDGET_EXHAUSTED")) failures.computeIfAbsent(signature) { AtomicInteger() }.incrementAndGet()
                    deliveryOutcome = if (normalizedResult.outputBudgetExhausted || normalizedResult.content != result.content || (result.retainedContent != null && result.retainedContent != result.content)) dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.TRUNCATED else dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.DELIVERED
                    success = !normalizedResult.isError
                    return@execution normalizedResult
                } catch (cancellation: CancellationException) {
                    if (!replayableRead) unknownOutcomes += signature
                    val cancellationContext = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.delegation.DelegationCancellationContext]
                    val origin = if (cancellation is kotlinx.coroutines.TimeoutCancellationException) "OPERATION_DEADLINE" else cancellationContext?.record?.origin?.name ?: "PARENT_CANCELLED"
                    reason = "CANCELED_$origin"
                    executionOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.CANCELED
                    AppLogRecorder.record(
                        "Tool",
                        "TOOL_CANCELED · origin=$origin · rootWorkId=${cancellationContext?.rootWorkId ?: "unknown"} · generationId=${cancellationContext?.generationId ?: "unknown"} · completedCalls=${completed.get()} · outcomeKnown=false · tool=${tool.definition.name} · call=$callId",
                        "W"
                    )
                    throw cancellation
                } catch (error: CombinedBudgetExceeded) {
                    reason = "COMBINED_BUDGET_EXHAUSTED"
                    return@execution failure(error.reason).copy(toolCallBudgetExhausted = true)
                } catch (rejected: dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected) {
                    // A deterministic refusal proves the child did not dispatch. It is
                    // neither an unknown write nor a provider reliability failure.
                    reason = rejected.code
                    physicalToolStarted = false
                    statistics?.rejectedBeforeDispatch(statisticsId)
                    statisticsId = null
                    if (ToolResultEnvelope.isAuthorizationRejection(rejected.code)) terminalToolFailures[tool.definition.name] = rejected.code
                    val result = bounded(ToolResultEnvelope.error(callId, rejected.code, rejected.message.orEmpty()))
                    combinedResult = result
                    return@execution result
                } catch (error: Exception) {
                    val readOnly = replayableRead
                    reason = if (readOnly) "READ_FAILED" else "DISPATCH_OUTCOME_UNKNOWN"
                    if (readOnly) executionOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
                    deliveryOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.DELIVERED
                    failures.computeIfAbsent(signature) { AtomicInteger() }.incrementAndGet()
                    if (!readOnly) unknownOutcomes += signature
                    val boundedResult = bounded(ToolResultEnvelope.error(callId, if (readOnly) "READ_FAILED" else "DISPATCH_OUTCOME_UNKNOWN", failureMessage(error) + if (readOnly) " Completed evidence remains available; a bounded read retry may continue." else " Dispatch outcome is unknown; reconcile before repeating an action.", dispatched = true))
                    if (readOnly) combinedResult = boundedResult
                    success = !boundedResult.isError
                    return@execution boundedResult
                } finally {
                    val receipt = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDeliveryReceipt]
                    if (receipt != null && statisticsId != null && statistics != null && deliveryOutcome == dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.DELIVERED) {
                        receipt.pending(statisticsId!!, statistics)
                        deliveryOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.PENDING
                    }
                    withContext(NonCancellable) {
                        val budget = sharedBudget
                        val attemptId = reservationId
                        val raw = combinedResult
                        if (budget != null && attemptId != null) {
                            if (!physicalToolStarted || raw != null && !raw.dispatched) {
                                budget.releaseNotDispatched(attemptId)
                            } else if (raw != null && raw.errorCode !in ToolResultEnvelope.unknownOutcomeCodes) {
                                budget.settle(attemptId, CombinedUsage(CombinedResourceQuota(toolOperations = workUnits)))
                            } else {
                                budget.unknown(attemptId, reason ?: "Tool completion unknown; work reservation retained.")
                            }
                        }
                        if (budget != null && raw != null && raw.dispatched && raw.errorCode !in ToolResultEnvelope.unknownOutcomeCodes) {
                            val item = assignedWorkId
                            var madeProgress = false
                            if (!raw.isError && item != null) {
                                if (AgentToolCapability.WEB_SEARCH in tool.definition.capabilities) budget.recordDiscovery(item, raw.content.toString())
                                if (CombinedToolWork.isInspection(tool.definition)) {
                                    // Citation links in a returned article were not themselves inspected.
                                    // An identifier-based paper read may supply its one canonical URL.
                                    val payload = (raw.content as? ToolResultContent.Json)?.value as? kotlinx.serialization.json.JsonObject
                                    val status = (payload?.get("retrievalStatus") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    val inspected = if (tool.definition.name == "deep_research_read_paper" && status !in setOf("read", "partially_read")) {
                                        ""
                                    } else if (CombinedWorkInventory.urls(arguments.toString()).isNotEmpty()) {
                                        arguments.toString()
                                    } else {
                                        (payload?.get("canonicalUrl") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                            ?: (payload?.get("sourceUrl") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                                    }
                                    madeProgress = budget.recordInspection(item, inspected, executionContext?.workItemId == item)
                                }
                            }
                            budget.completedAcquisitionBatch(madeProgress)
                        }
                        statistics?.complete(
                            statisticsId,
                            executionOutcome,
                            deliveryOutcome,
                            reason,
                            startedNanos.takeIf { it > 0 }?.let { (System.nanoTime() - it) / 1_000_000 },
                            shared,
                            producer
                        )
                        onFinished(callId, success)
                    }
                }
            }
        }
    }

    private fun tryAcquireCall(enforceResearchLimit: Boolean = false): Boolean {
        if (limits.researchAcquisition && !enforceResearchLimit || executionLimit == Int.MAX_VALUE) {
            calls.incrementAndGet()
            return true
        }
        while (true) {
            val current = calls.get()
            if (current >= executionLimit.coerceAtLeast(0)) return false
            if (calls.compareAndSet(current, current + 1)) return true
        }
    }

    private fun bounded(
        rawResult: AgentToolResult,
        preserveSuccessfulHandoff: Boolean = false,
        observationLimitBytes: Int = 64 * 1024
    ): AgentToolResult {
        // Academic acquisition must see the bounded typed page before model compaction.
        // The ordinary 64-KiB envelope replaces large pages with an excerpt wrapper,
        // hiding papers/nextCursor from the coordinator. Keep up to 256 KiB only for
        // these read-only tools; model excerpts, redacted traces and run caps stay bounded.
        val result = ToolObservationRetention.bound(rawResult, maxBytes = observationLimitBytes)
        val text = when (val value = result.content) {
            is ToolResultContent.Text -> value.text
            is ToolResultContent.Json -> value.value.toString()
            is ToolResultContent.ResourceLinks -> value.links.joinToString("\n") { it.uri }
        }
        val checkpoint = if (completed.incrementAndGet() % ToolProgressTracker.INTERVAL == 0 && GitHubReplayRecovery.envelope(result.content) == null) {
            "\n\n" + ToolProgressTracker.SUMMARY_INSTRUCTION
        } else {
            ""
        }
        val size = (text + checkpoint).toByteArray(Charsets.UTF_8).size
        // Debit the admitted excerpt, rather than allowing a single raw JSON/page
        // payload to consume the entire turn's research budget. Keep full results
        // separately for UI and recovery, as before.
        val resultAllowance = if (evidenceBytesPerResult != null) {
            limits.maxToolOutputBytes
        } else if (preserveSuccessfulHandoff) {
            Int.MAX_VALUE
        } else {
            minOf(16 * 1024, limits.maxToolOutputBytes)
        }
        val chargedBytes = minOf(size, resultAllowance)
        val sharedAvailable = if (separateResultAllowance) remainingOutputBytes() else remainingBytes.getAndUpdate { (it.toLong() - chargedBytes).coerceAtLeast(0).toInt() }.coerceAtLeast(0)
        val handoffReserve = if (
            preserveSuccessfulHandoff &&
            !result.isError &&
            size > sharedAvailable
        ) {
            minOf(DELEGATION_HANDOFF_RESERVE_BYTES, size - sharedAvailable)
        } else {
            0
        }
        val payloadAvailable = (minOf(sharedAvailable, resultAllowance).toLong() + handoffReserve).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val available = minOf(payloadAvailable, evidenceBytesPerResult ?: Int.MAX_VALUE)

        // Delegation is an orchestrator: its nested search/read calls may legitimately
        // consume the shared raw-result budget before the compact final handoff exists.
        // Preserve that compact handoff in a small emergency reserve rather than
        // replacing successful research with a misleading budget error.
        if (handoffReserve > 0) {
            AppLogRecorder.record(
                "ToolBudget",
                "Preserving delegated handoff after nested result budget use · call=${result.callId} · resultBytes=$size · sharedAvailable=$sharedAvailable · reserveBytes=$handoffReserve",
                "W"
            )
        }

        // Control messages are bounded separately: providers reject empty error results,
        // and a zero payload allowance must still let the model finish the turn.
        if (available == 0) {
            val message = outputBudgetMessage()
            return withBudgetState(
                result.copy(
                    content = ToolResultContent.Text(message),
                    retainedContent = result.retainedContent ?: result.content,
                    traceContent = ToolResultContent.Text(message),
                    isError = true,
                    outputBudgetExhausted = true,
                    toolCallBudgetExhausted = result.toolCallBudgetExhausted || callBudgetIsExhausted()
                )
            )
        }
        val checkpointBytes = checkpoint.toByteArray(Charsets.UTF_8).size
        val bounded = if (checkpointBytes <= available) {
            truncateUtf8(text, available - checkpointBytes) + checkpoint
        } else {
            truncateUtf8(text, available)
        }
        val safeText = bounded.ifBlank {
            if (size > available) {
                outputBudgetMessage()
            } else if (result.isError) {
                "Tool failed without error details."
            } else {
                "Tool returned no content."
            }
        }
        val changed = size > available || checkpoint.isNotEmpty() || bounded.isBlank()
        val trace = if (size > available) {
            // Respect tools that deliberately supply a redacted trace; otherwise show
            // the useful search/page excerpt as well as the truncation notice.
            val excerpt = result.traceContent?.let { value ->
                when (value) {
                    is ToolResultContent.Text -> value.text
                    is ToolResultContent.Json -> value.value.toString()
                    is ToolResultContent.ResourceLinks -> value.links.joinToString("\n") { it.uri }
                }
            } ?: safeText
            ToolResultContent.Text(truncateUtf8(excerpt, available) + "\n\n[Result truncated to the run's output budget.]")
        } else {
            result.traceContent
        }
        return withBudgetState(
            result.copy(
                content = if (changed && bounded.isBlank()) {
                    ToolResultContent.Text(safeText)
                } else if (changed) {
                    GitHubReplayRecovery.compact(result.content, available / 4) ?: ToolResultEnvelope.compact(result.content, available)
                } else {
                    result.content
                },
                retainedContent = result.retainedContent ?: result.content.takeIf { changed },
                traceContent = trace,
                outputBudgetExhausted = result.outputBudgetExhausted || !separateResultAllowance && remainingBytes.get() <= 0,
                toolCallBudgetExhausted = result.toolCallBudgetExhausted || callBudgetIsExhausted()
            )
        )
    }

    private fun withBudgetState(result: AgentToolResult): AgentToolResult {
        val configuredCalls = limits.maxToolCalls.takeUnless { limits.researchAcquisition && !combinedMode || it == Int.MAX_VALUE }
        val callLimit = executionLimit.takeUnless { limits.researchAcquisition && !combinedMode || it == Int.MAX_VALUE }
        val resultByteLimit = limits.maxToolOutputBytes.takeUnless { separateResultAllowance || it == Int.MAX_VALUE }
        val resultBytesUsed = resultByteLimit?.let { limit ->
            (limit.toLong() - remainingBytes.get().toLong()).coerceIn(0L, limit.toLong()).toInt()
        }
        return result.copy(
            toolCallBudgetUsed = calls.get(),
            toolCallBudgetLimit = callLimit,
            toolCallBudgetConfigured = configuredCalls,
            toolCallBudgetReserved = configuredCalls?.let { limits.finalResponseToolCallReserve.coerceAtLeast(0) },
            toolResultBudgetUsedBytes = resultBytesUsed,
            toolResultBudgetLimitBytes = resultByteLimit
        )
    }

    private fun AgentToolResult.hasSuccessfulDelegationMarker(): Boolean =
        (content as? ToolResultContent.Text)?.text?.trimStart()?.let { text ->
            text.startsWith("<!-- delegation:local -->") || text.startsWith("<!-- delegation:remote -->")
        } == true
    private fun callBudgetIsExhausted(): Boolean =
        (!limits.researchAcquisition || combinedMode) && executionLimit != Int.MAX_VALUE && calls.get() >= executionLimit

    private fun toolCallBudgetMessage(): String {
        val reserve = limits.finalResponseToolCallReserve.coerceAtLeast(0)
        val configured = limits.maxToolCalls.coerceAtLeast(0)
        return "Tool-call limit reached: ${calls.get()}/$executionLimit executable calls used " +
            "($configured configured, $reserve reserved for finalization). " +
            "Use the successful results already available. Increase Maximum Tool Calls on the active AI profile if more research is required."
    }

    private fun outputBudgetMessage(): String {
        val configured = limits.maxToolOutputBytes.coerceAtLeast(0)
        return "Tool-result byte budget exhausted: $configured/$configured bytes used. " +
            "Use the successful results already available; completed delegated research should still be summarized."
    }

    private companion object {
        const val DELEGATION_TOOL_NAME = "delegate_to_model"
        const val DELEGATION_HANDOFF_RESERVE_BYTES = 64 * 1024
        val ACADEMIC_ACQUISITION_TOOLS = setOf("deep_research_search_papers", "deep_research_read_paper")
    }
}

internal const val OUTPUT_BUDGET_EXHAUSTED = "Tool-result byte budget exhausted. Answer using the results already available; do not call more tools."

internal fun truncateUtf8(text: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var end = 0
    var used = 0
    while (end < text.length) {
        val point = text.codePointAt(end)
        val bytes = when {
            point < 0x80 -> 1
            point < 0x800 -> 2
            point < 0x10000 -> 3
            else -> 4
        }
        if (used + bytes > maxBytes) break
        used += bytes
        end += Character.charCount(point)
    }
    return text.substring(0, end)
}

/** Reaches page readers through measured/authorized wrappers without altering tool arguments. */
internal class ToolOutputAllowance(val bytes: Int) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<ToolOutputAllowance>
}
