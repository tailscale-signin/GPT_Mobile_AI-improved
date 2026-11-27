package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One budget per user turn, shared by every bound tool, including the native engine bridge. */
class ToolExecutionBudget(
    private val limits: AgentRunLimits,
    private val evidenceBytesPerResult: Int? = null,
    private val statistics: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository? = null,
    private val statisticsProfile: String = "",
    private val statisticsRoot: String = "",
    private val combined: CombinedExecutionContext? = null,
    private val combinedBudgets: CombinedTurnBudgetStore? = null,
    private val failureMessage: (Exception) -> String = { "Tool execution failed. Check the connection diagnostics before repeating an action." },
    initialCalls: Int = 0,
    private val durableCalls: ToolCallBudgetCheckpoint? = null
) {
    private val invalidatedAuthority = AtomicReference<AgentToolResult?>(null)
    private val terminalToolFailures = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val unknownOutcomes = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    private val calls = AtomicInteger(initialCalls.coerceAtLeast(0))
    private val attempts = AtomicInteger()
    private val invocationIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val argumentRepairs = ToolArgumentRepairBudget()
    private val semanticArgumentRejections = java.util.concurrent.ConcurrentHashMap<String, AgentToolResult>()
    private val completed = AtomicInteger()
    private val outputAdmissionLock = Any()
    private var outputAdmissionClosed = false
    private val outputByteLimit = AtomicInteger(limits.maxToolOutputBytes)
    private val remainingBytes = AtomicInteger(limits.maxToolOutputBytes)
    private val permits = Semaphore(limits.maxConcurrentTools.coerceAtLeast(1))
    private val executionLimit = ToolBudgetPolicy.executionLimit(limits)

    // The accepted objective is immutable for this budget. Protect its initial
    // proof allowance even when one parallel source batch crosses a round boundary.
    private data class VerificationAllocation(val proof: Int, val cleanup: Int)
    private val verificationAllocation by lazy {
        AtomicReference(
            VerificationAllocation(
                dev.chungjungsoo.gptmobile.data.github.GitHubChecklistBudget.ordinaryVerificationReserve(
                    executionLimit,
                    limits.githubTaskRequest?.let { dev.chungjungsoo.gptmobile.data.github.GitHubRequestCompletion.requirements(it).size } ?: 0
                ),
                0
            )
        )
    }
    private val combinedMode = combined != null || limits.combined != null

    internal fun restoreArgumentRepairs(receipts: Iterable<JsonObject>) = argumentRepairs.restore(receipts)

    // GitHub delivers bounded saved-view pages; a cumulative excerpt allowance
    // must not stop traversal after three 8 KiB pages. The call ceiling,
    // per-result bound and provider replay/context admission still apply.
    private val separateResultAllowance = limits.researchAcquisition || combinedMode || limits.githubTask && limits.maxToolOutputBytes > 0

    /** Only adjusts the stage allocation; every call still spends the same durable total allowance. */
    fun updateVerificationBudget(pendingCriteria: Int, cleanupReserve: Int) {
        if (limits.githubTask) {
            verificationAllocation.set(
                VerificationAllocation(
                    dev.chungjungsoo.gptmobile.data.github.GitHubChecklistBudget.ordinaryVerificationReserve(executionLimit, pendingCriteria),
                    cleanupReserve.coerceIn(0, executionLimit.coerceAtLeast(0))
                )
            )
        }
    }

    fun usedCalls(): Int = calls.get()

    fun remainingCalls(): Int? = if (limits.researchAcquisition && !combinedMode || executionLimit == Int.MAX_VALUE) null else (executionLimit - calls.get()).coerceAtLeast(0)

    fun canExecute(): Boolean = (separateResultAllowance || remainingBytes.get() > 0) && !callBudgetIsExhausted()
    fun remainingOutputBytes(): Int = if (separateResultAllowance) outputByteLimit.get().coerceAtLeast(0) else remainingBytes.get().coerceAtLeast(0)

    /** One read-only observation for admission and its receipt; never reserves or renews work. */
    data class Snapshot(
        val canExecute: Boolean,
        val remainingOutputBytes: Int,
        val callsUsed: Int,
        val callsLimit: Int?,
        val callsConfigured: Int?,
        val callsReserved: Int?,
        val resultBytesUsed: Int?,
        val resultBytesLimit: Int?
    ) {
        fun attach(result: AgentToolResult): AgentToolResult = result.copy(
            toolCallBudgetUsed = callsUsed,
            toolCallBudgetLimit = callsLimit,
            toolCallBudgetConfigured = callsConfigured,
            toolCallBudgetReserved = callsReserved,
            toolResultBudgetUsedBytes = resultBytesUsed,
            toolResultBudgetLimitBytes = resultBytesLimit
        )
    }

    fun snapshot(): Snapshot = synchronized(outputAdmissionLock) {
        val usedCalls = calls.get()
        val outputLimit = outputByteLimit.get()
        val remaining = remainingBytes.get()
        val configuredCalls = limits.maxToolCalls.takeUnless { limits.researchAcquisition && !combinedMode || it == Int.MAX_VALUE }
        val callLimit = executionLimit.takeUnless { limits.researchAcquisition && !combinedMode || it == Int.MAX_VALUE }
        val resultByteLimit = outputLimit.takeUnless { separateResultAllowance || it == Int.MAX_VALUE }
        Snapshot(
            canExecute = (separateResultAllowance || remaining > 0) && (callLimit == null || usedCalls < callLimit),
            remainingOutputBytes = (if (separateResultAllowance) outputLimit else remaining).coerceAtLeast(0),
            callsUsed = usedCalls,
            callsLimit = callLimit,
            callsConfigured = configuredCalls,
            callsReserved = configuredCalls?.let { limits.finalResponseToolCallReserve.coerceAtLeast(0) },
            resultBytesUsed = resultByteLimit?.let { limit -> (limit.toLong() - remaining.toLong()).coerceIn(0L, limit.toLong()).toInt() },
            resultBytesLimit = resultByteLimit
        )
    }

    /** Fund a newly admitted tool from its replanned context before any execution attempt. */
    internal fun admitPlannedOutputAllowance(maxBytes: Int): Boolean = synchronized(outputAdmissionLock) {
        if (maxBytes <= 0 || limits.maxToolOutputBytes != 0 || outputAdmissionClosed || separateResultAllowance || limits.githubTask) {
            false
        } else {
            // Keep this budget's identity, spent calls, authority failures and bound tools.
            // Closing admission here prevents repeated plans from replenishing consumed bytes.
            outputAdmissionClosed = true
            outputByteLimit.set(maxBytes)
            remainingBytes.set(maxBytes)
            true
        }
    }

    fun bind(
        tool: AgentTool,
        onFinished: suspend (String, Boolean) -> Unit = { _, _ -> },
        statisticsIdentity: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity.of(tool.definition.name),
        authorize: suspend (String, JsonObject) -> Boolean = { _, _ -> true }
    ): AgentTool = bindPrepared(tool.withChildBudget { child -> bind(child) }, onFinished, statisticsIdentity, authorize)

    internal fun bindPresented(tool: AgentTool, presented: AgentToolDefinition): AgentTool {
        require(presented.name == tool.definition.name)
        return bindPrepared(tool.withChildBudget { child -> bind(child) }, definitionOverride = presented)
    }

    private fun bindPrepared(
        tool: AgentTool,
        onFinished: suspend (String, Boolean) -> Unit = { _, _ -> },
        statisticsIdentity: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolIdentity.of(tool.definition.name),
        authorize: suspend (String, JsonObject) -> Boolean = { _, _ -> true },
        definitionOverride: AgentToolDefinition? = null
    ): AgentTool = object : PreparableAgentTool, OwnedAgentTool, ApprovedReadOnlyAgentTool, ProgressiveCatalogCarrier {
        init {
            dev.chungjungsoo.gptmobile.data.agent.LocalToolTransport.inherit(this, tool)
        }

        override val definition = definitionOverride ?: tool.definition
        override val progressiveCatalog get() = tool.argumentCatalog()
        override val searchValidationOwner get() = tool.searchValidationOwner
        override val approvedReadOnly = (tool as? ApprovedReadOnlyAgentTool)?.approvedReadOnly == true
        override val managesExecutionBudget = true
        override val coordinatesChildWork = tool.coordinatesChildWork
        override val executionOwner = (tool as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT

        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = prepareExecution(callId, arguments).invoke()

        override suspend fun prepareExecution(callId: String, arguments: JsonObject): suspend () -> AgentToolResult {
            val submittedArguments = arguments
            synchronized(outputAdmissionLock) { outputAdmissionClosed = true }
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
            // Native callbacks and restored calls reach this validator before the
            // operation tool. Normalize safe reads before both schema admission
            // and authorization; keep the original invocation in its journal.
            val arguments = GitHubRecoveryCalls.repairSourceRead(
                ProviderEvent.ToolCall(callId, definition.name, tool.argumentCatalog()?.canonicalArguments(arguments) ?: arguments),
                tool
            ).arguments
            val signature = "$executionOwner:${tool.definition.name}:" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(arguments)
            val replayableRead = ToolReplaySafety.isReplayableRead(tool.definition.name, arguments, tool) ||
                AgentToolCapability.USER_DECISION in tool.definition.capabilities
            if (!replayableRead) unknownOutcomes[signature]?.let { original -> return { withBudgetState(ToolReconciliationEvidence.blocked(callId, original)) } }
            invalidatedAuthority.get()?.let { prior -> return { withBudgetState(ToolFailureMetadata.authorityBlocked(callId, definition.name, prior)) } }
            if ((failures[signature]?.get() ?: 0) >= 2) return { withBudgetState(ToolResultEnvelope.error(callId, "REPEATED_FAILURE", "An unchanged call failed twice. Stop repeating it; use retained evidence or correct the request.")) }
            terminalToolFailures[tool.definition.name]?.let { code ->
                return { withBudgetState(ToolResultEnvelope.error(callId, code, "This tool's permission or authentication failed earlier in this turn. Review its grants or credentials before starting a new turn; changing arguments will not retry it.")) }
            }
            val invalid = ToolArgumentValidator.errors(arguments, definition.inputSchema).ifEmpty {
                if (definition.inputSchema == tool.definition.inputSchema) {
                    emptyList()
                } else {
                    ToolArgumentValidator.errors(arguments, tool.definition.inputSchema)
                }
            }
            if (invalid.isNotEmpty()) {
                val repairs = currentCoroutineContext()[ToolArgumentRepairScope]?.budget ?: argumentRepairs
                if (repairs !== argumentRepairs) repairs.restore(argumentRepairs.snapshot())
                val rejected = repairs.rejection(callId, definition, arguments, invalid, searchValidationOwner = tool.searchValidationOwner, catalog = tool.argumentCatalog())
                argumentRepairs.restore(listOfNotNull(rejected.executionReceipt))
                return { withBudgetState(rejected) }
            }
            semanticArgumentRejections[signature]?.let { previous ->
                return { withBudgetState(previous.copy(callId = callId, sharedResult = true, searchValidationEvidence = null)) }
            }
            if (!separateResultAllowance && remainingBytes.get() <= 0) {
                val message = outputBudgetMessage()
                AppLogRecorder.record(
                    "ToolBudget",
                    "Tool-result byte budget blocked execution · tool=${tool.definition.name} · call=$callId · configuredBytes=${outputByteLimit.get()}",
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
            val family = definition.name.substringBefore("__")
            val liveVerification = arguments["verify_live"] == JsonPrimitive(true) && (definition.inputSchema["properties"] as? JsonObject)?.containsKey("verify_live") == true
            // Reopening an issued local page is evidence consumption, not new
            // repository acquisition. The operation still validates the cursor,
            // owning scope and current grants, and spends the same durable call.
            val retainedPageRead = GitHubSavedReadProgress.cursor(arguments) != null &&
                (definition.inputSchema["properties"] as? JsonObject)?.containsKey("view_cursor") == true
            val inventoryRead = family in setOf("github_repository", "github_issue", "github_pr", "github_ci", "github_release", "github_commit") &&
                (arguments["action"] as? JsonPrimitive)?.content in setOf("list", "search", "branches", "tags", "runs", "jobs", "workflows", "commits", "releases")
            val sourceAcquisition = limits.githubTask && !liveVerification && !retainedPageRead && (family in setOf("github_read", "github_search") || inventoryRead)
            val allocation = verificationAllocation.get()
            val callCeiling = if (limits.githubTask && executionLimit != Int.MAX_VALUE) {
                when {
                    sourceAcquisition -> (executionLimit - allocation.proof - allocation.cleanup).coerceAtLeast(0)
                    liveVerification || retainedPageRead -> (executionLimit - allocation.cleanup).coerceAtLeast(0)
                    else -> executionLimit // Exact cleanup recipes still use the accepted total ceiling.
                }
            } else {
                executionLimit
            }
            if (!alreadyBudgeted && callBudgetIsExhausted()) {
                return { withBudgetState(failure(toolCallBudgetMessage()).copy(toolCallBudgetExhausted = true)) }
            }
            val authorized = try {
                authorize(callId, arguments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected) {
                authorityPrecheck(callId, definition.name, rejected)?.let { return { bounded(it) } }
                if (ToolResultEnvelope.isAuthorizationRejection(rejected.code)) terminalToolFailures[tool.definition.name] = rejected.code
                return { bounded(authorizationRefusal(callId, rejected)) }
            } catch (error: Exception) {
                authorityPrecheck(callId, definition.name, error)?.let { return { bounded(it) } }
                // An unexpected admission exception is not a failed tool
                // dispatch or proof of invalid model arguments. Fail locally,
                // preserve a content-free exception fingerprint, and retain
                // all tool-call allowance for an explicitly authorized retry.
                val typeHash = dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(error.javaClass.name)
                val rejected = ToolResultEnvelope.error(
                    callId,
                    "TOOL_PRECHECK_FAILED",
                    "The tool's authorization precheck could not complete. No tool was dispatched; inspect the selected connection and retry only after the precheck is available."
                ).copy(
                    executionReceipt = buildJsonObject {
                        put("failed_stage", "tool_precheck")
                        put("reason_code", "TOOL_PRECHECK_FAILED")
                        put("precheck_origin", "AUTHORIZATION_CALLBACK")
                        put("exception_type_hashes", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(typeHash))))
                        put("dispatched", false)
                        put("retryable", false)
                        put("requires_reconciliation", false)
                    }
                )
                return { withBudgetState(rejected) }
            }
            if (!authorized) {
                terminalToolFailures[tool.definition.name] = "AUTH_REQUIRED"
                return { bounded(ToolResultEnvelope.error(callId, "AUTH_REQUIRED", "Tool permission was denied or revoked. Review this tool's grants before retrying.")) }
            }
            val acquired = try {
                alreadyBudgeted || tryAcquireCall(enforceResearchLimit = executionContext != null, callLimit = callCeiling)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                authorityPrecheck(callId, definition.name, error)?.let { return { bounded(it) } }
                return { withBudgetState(ToolResultEnvelope.error(callId, "BUDGET_CHECKPOINT_UNAVAILABLE", "The durable tool allowance could not be saved. No action was dispatched; restore its checkpoint before resuming.")) }
            }
            if (!acquired) {
                if (callCeiling < executionLimit && calls.get() < executionLimit) {
                    return {
                        withBudgetState(
                            ToolResultEnvelope.error(
                                callId,
                                "VERIFICATION_RESERVE_REACHED",
                                if (allocation.cleanup > 0 && (liveVerification || retainedPageRead)) {
                                    "Verification reads stopped before spending the required fixture cleanup allowance. Retain the task checkpoint and complete its owned cleanup; no read was dispatched."
                                } else {
                                    "Source acquisition stopped before spending the verification and cleanup allowance. Retain the task checkpoint and verify completed work; no source request was dispatched."
                                }
                            )
                        )
                    }
                }
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
                val interruptionCall = ProviderEvent.ToolCall(callId, tool.definition.name, arguments)
                val samePhysicalOwner = alreadyBudgeted &&
                    currentCoroutineContext()[CombinedToolDispatchScope]?.let {
                        it.identity == physicalIdentity && it.context.rootBudgetId == executionContext?.rootBudgetId
                    } == true
                val phoneInterruption = currentCoroutineContext()[PhoneIndexInterruption]
                    ?.reuseFor(interruptionCall, samePhysicalOwner) ?: PhoneIndexInterruption(interruptionCall)
                val workUnits = CombinedToolWork.units(arguments)
                suspend fun invokeObserved(): AgentToolResult {
                    // Admission and semaphore waiting do not count as uses.
                    currentCoroutineContext().ensureActive()
                    invalidatedAuthority.get()?.let { return ToolFailureMetadata.authorityBlocked(callId, definition.name, it) }
                    // A prepared call may have waited behind another invocation.
                    // Validate before any physical tool work or shared reservation.
                    currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard]?.validate?.invoke()
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
                                is CombinedBudgetAdmission.Denied -> return ToolResultEnvelope.error(callId, "COMBINED_BUDGET_EXHAUSTED", "Combined work allowance exhausted: ${admission.reason}. Use retained evidence.").copy(combinedWorkBudgetExhausted = true)
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
                    val observed = withContext(sharedContext + ToolBudgetScope(this@ToolExecutionBudget) + phoneInterruption) {
                        currentCoroutineContext().ensureActive()
                        dev.chungjungsoo.gptmobile.data.agent.LocalToolTransport.requireExecution(tool)
                        physicalToolStarted = true
                        if (recorder != null && recordedId != null) withContext(dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsContext(recorder, recordedId, statisticsIdentity.key, attributedProfile, statisticsRoot)) { tool.execute(callId, arguments) } else tool.execute(callId, arguments)
                    }
                    // A receipt with another call ID cannot establish this call's outcome,
                    // even if that unrelated receipt claims it was not dispatched.
                    val receipt = AgentContractValidation.receipt(callId, observed)
                    val result = if (arguments != submittedArguments) {
                        ToolArgumentNormalization.retain(receipt, arguments, ToolArgumentNormalization.Kind.SCHEMA_CANONICALIZATION)
                    } else {
                        receipt
                    }
                    combinedResult = result
                    recordAuthorityFailure(result)
                    currentCoroutineContext()[ToolReceiptObserver]?.observe?.invoke(ProviderEvent.ToolCall(callId, tool.definition.name, submittedArguments), result)
                    return result.also { raw ->
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
                            withContext(ToolOutputAllowance(sourceExcerptAllowance(remainingOutputBytes()))) {
                                withTimeoutOrNull(if (limits.researchAcquisition) dev.chungjungsoo.gptmobile.data.research.ResearchTimeouts.operationMillis(tool.definition.name, limits.toolTimeoutMillis) else limits.toolTimeoutMillis) { invokeObserved() }
                            }
                                ?: phoneInterruption.close() ?: if (replayableRead
                            ) {
                                ToolResultEnvelope.error(callId, "READ_DEADLINE", "Read deadline reached; completed evidence is retained. Retry this read with a new call ID or narrow its scope; write actions are never replayed automatically.", dispatched = true)
                            } else {
                                ToolResultEnvelope.error(callId, "TIMEOUT_OUTCOME_UNKNOWN", "Tool timed out after dispatch. Reconcile the outcome before repeating an action.", dispatched = true)
                            }
                        }
                    }
                    if (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes) reason = result.errorCode
                    if (result.errorCode in setOf("READ_DEADLINE", PhoneIndexInterruption.CODE)) {
                        combinedResult = result
                        reason = result.errorCode
                        executionOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
                    }
                    val boundedResult = bounded(
                        result,
                        preserveSuccessfulHandoff = tool.definition.name == DELEGATION_TOOL_NAME,
                        shareSourceBudget = !separateResultAllowance &&
                            remainingCalls()?.let { it > 0 } == true &&
                            (tool.definition.name in setOf("read_url", "web_search") || tool.definition.capabilities.any { it == AgentToolCapability.WEB_SEARCH || it == AgentToolCapability.PAGE_READER }),
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
                    if (normalizedResult.isError && !normalizedResult.dispatched && normalizedResult.errorCode == "INVALID_ARGUMENTS") {
                        synchronized(semanticArgumentRejections) {
                            if (semanticArgumentRejections.size < 128) semanticArgumentRejections.putIfAbsent(signature, normalizedResult)
                        }
                    }
                    if (normalizedResult.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead) unknownOutcomes.putIfAbsent(signature, ToolReconciliationEvidence.observation(ProviderEvent.ToolCall(callId, tool.definition.name, arguments), normalizedResult))
                    if (normalizedResult.isError && normalizedResult.dispatched && normalizedResult.errorCode !in setOf("AUTH_REQUIRED", "BUDGET_EXHAUSTED")) failures.computeIfAbsent(signature) { AtomicInteger() }.incrementAndGet()
                    deliveryOutcome = if (normalizedResult.outputBudgetExhausted || normalizedResult.content != result.content || (result.retainedContent != null && result.retainedContent != result.content)) dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.TRUNCATED else dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.DELIVERED
                    success = !normalizedResult.isError
                    return@execution normalizedResult
                } catch (cancellation: CancellationException) {
                    val stoppedLocalIndex = phoneInterruption.close()
                    if (stoppedLocalIndex != null) combinedResult = stoppedLocalIndex
                    // A cancelled queue or authority check has no invocation to reconcile.
                    if (physicalToolStarted && !replayableRead && stoppedLocalIndex == null) {
                        val interrupted = ToolResultEnvelope.error(callId, "DISPATCH_OUTCOME_UNKNOWN", "Operation interrupted", dispatched = true)
                        unknownOutcomes.putIfAbsent(signature, ToolReconciliationEvidence.observation(interruptionCall, interrupted))
                    }
                    val cancellationContext = kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.delegation.DelegationCancellationContext]
                    val origin = if (cancellation is kotlinx.coroutines.TimeoutCancellationException) "OPERATION_DEADLINE" else cancellationContext?.record?.origin?.name ?: "PARENT_CANCELLED"
                    reason = "CANCELED_$origin"
                    executionOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.CANCELED
                    AppLogRecorder.record(
                        "Tool",
                        "TOOL_CANCELED · origin=$origin · rootWorkId=${cancellationContext?.rootWorkId ?: "unknown"} · generationId=${cancellationContext?.generationId ?: "unknown"} · completedCalls=${completed.get()} · outcomeKnown=${stoppedLocalIndex != null} · tool=${tool.definition.name} · call=$callId",
                        "W"
                    )
                    throw cancellation
                } catch (error: CombinedBudgetExceeded) {
                    reason = "COMBINED_BUDGET_EXHAUSTED"
                    return@execution ToolResultEnvelope.error(callId, "COMBINED_BUDGET_EXHAUSTED", "Combined work allowance exhausted: ${error.reason}. Use retained evidence.").copy(combinedWorkBudgetExhausted = true)
                } catch (error: Exception) {
                    if (error is dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected && ToolFailureMetadata.authorityCode(error) == null) {
                        // Ordinary deterministic permission refusals keep their local
                        // rejection contract. Authority invalidation follows the actual
                        // invocation/receipt boundary below, including observer failures.
                        reason = error.code
                        physicalToolStarted = false
                        statistics?.rejectedBeforeDispatch(statisticsId)
                        statisticsId = null
                        if (ToolResultEnvelope.isAuthorizationRejection(error.code)) terminalToolFailures[tool.definition.name] = error.code
                        val result = bounded(authorizationRefusal(callId, error))
                        combinedResult = result
                        return@execution result
                    }
                    if (!physicalToolStarted) {
                        authorityPrecheck(callId, definition.name, error)?.let { rejected ->
                            statistics?.rejectedBeforeDispatch(statisticsId)
                            statisticsId = null
                            reason = rejected.errorCode
                            combinedResult = rejected
                            deliveryOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.DELIVERED
                            return@execution bounded(rejected)
                        }
                    }
                    combinedResult?.let { observed -> ToolFailureMetadata.withAuthorityFailure(observed, error, "delivery") }?.let { retained ->
                        // The observation predates the failed authorization check.
                        // Its completed/unknown outcome must not be overwritten.
                        combinedResult = retained
                        recordAuthorityFailure(retained)
                        if (retained.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead) unknownOutcomes.putIfAbsent(signature, ToolReconciliationEvidence.observation(ProviderEvent.ToolCall(callId, tool.definition.name, arguments), retained))
                        reason = ToolFailureMetadata.authorityCode(retained)
                        executionOutcome = when {
                            retained.errorCode in ToolResultEnvelope.unknownOutcomeCodes -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.UNKNOWN
                            retained.isError -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
                            else -> dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.SUCCESS
                        }
                        deliveryOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.DELIVERED
                        success = !retained.isError
                        return@execution bounded(retained)
                    }
                    val readOnly = replayableRead
                    reason = if (readOnly) "READ_FAILED" else "DISPATCH_OUTCOME_UNKNOWN"
                    if (readOnly) executionOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolOutcome.FAILURE
                    deliveryOutcome = dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolDelivery.DELIVERED
                    failures.computeIfAbsent(signature) { AtomicInteger() }.incrementAndGet()
                    val readGuidance = if (ToolFailureMetadata.authorityCode(error) == null) " Completed evidence remains available; a bounded read retry may continue." else " Completed evidence remains available."
                    val failure = ToolResultEnvelope.error(callId, if (readOnly) ToolFailureMetadata.authorityCode(error) ?: "READ_FAILED" else "DISPATCH_OUTCOME_UNKNOWN", failureMessage(error) + if (readOnly) readGuidance else " Dispatch outcome is unknown; reconcile before repeating an action.", dispatched = true)
                    val observed = ToolFailureMetadata.withAuthorityFailure(failure, error, "tool_execution")?.let {
                        ToolFailureMetadata.receipt(it, definition.name, diagnosticCode = ToolFailureMetadata.authorityCode(error), replaySafe = readOnly)
                    } ?: failure
                    if (!readOnly) unknownOutcomes.putIfAbsent(signature, ToolReconciliationEvidence.observation(ProviderEvent.ToolCall(callId, tool.definition.name, arguments), observed))
                    recordAuthorityFailure(observed)
                    val boundedResult = bounded(observed)
                    if (readOnly) combinedResult = boundedResult
                    success = !boundedResult.isError
                    return@execution boundedResult
                } finally {
                    phoneInterruption.close()
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

    private fun authorizationRefusal(callId: String, failure: dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected): AgentToolResult {
        val result = ToolResultEnvelope.error(callId, failure.code, failure.message.orEmpty())
        if (failure !is dev.chungjungsoo.gptmobile.data.permissions.DuplicateToolAction) return result
        return result.copy(
            executionReceipt = buildJsonObject {
                put("schema", "gptmobile.duplicate-approval.v1")
                put("call_id", callId)
                put("previous_approval_state", failure.previousState)
                put("failed_stage", "tool_precheck")
            }
        )
    }

    private fun recordAuthorityFailure(result: AgentToolResult) {
        if (ToolFailureMetadata.authorityCode(result) != null) invalidatedAuthority.compareAndSet(null, result)
    }

    private fun authorityPrecheck(callId: String, tool: String, error: Throwable): AgentToolResult? =
        ToolFailureMetadata.authorityPrecheck(callId, tool, error)?.also(::recordAuthorityFailure)

    private suspend fun tryAcquireCall(enforceResearchLimit: Boolean = false, callLimit: Int = executionLimit): Boolean {
        durableCalls?.let { checkpoint ->
            val used = checkpoint.acquire(callLimit) ?: return false
            calls.getAndUpdate { maxOf(it, used) }
            return true
        }
        if (limits.researchAcquisition && !enforceResearchLimit || callLimit == Int.MAX_VALUE) {
            calls.getAndUpdate { if (it == Int.MAX_VALUE) it else it + 1 }
            return true
        }
        while (true) {
            val current = calls.get()
            if (current >= callLimit.coerceAtLeast(0)) return false
            if (calls.compareAndSet(current, current + 1)) return true
        }
    }

    private fun bounded(
        rawResult: AgentToolResult,
        preserveSuccessfulHandoff: Boolean = false,
        shareSourceBudget: Boolean = false,
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
        val resultAllowance = if (shareSourceBudget) {
            // Charge metadata and excerpts together. The reader's body cap alone
            // cannot leave room for another source when the URL envelope is large.
            sourceExcerptAllowance(outputByteLimit.get())
        } else if (evidenceBytesPerResult != null) {
            outputByteLimit.get()
        } else if (preserveSuccessfulHandoff) {
            Int.MAX_VALUE
        } else {
            minOf(16 * 1024, outputByteLimit.get())
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
            val receipt = result.executionReceipt
            val observedRead = !result.isError &&
                result.dispatched &&
                receipt?.get("schema") == JsonPrimitive("github.dispatch.v1") &&
                receipt["phase"] == JsonPrimitive("OBSERVED") &&
                receipt["parent_result_successful"] == JsonPrimitive(true) &&
                receipt["mutation_requests"] == JsonPrimitive(0)
            val summary = if (observedRead) {
                buildJsonObject {
                    put("schema", "github.evidence-summary.v1")
                    put("message", message)
                    put("result_delivery", "WITHHELD_BY_RESULT_BUDGET")
                }
            } else {
                null
            }
            return withBudgetState(
                result.copy(
                    content = summary?.let { ToolResultContent.Json(it) } ?: ToolResultContent.Text(message),
                    retainedContent = result.retainedContent ?: result.content,
                    traceContent = ToolResultContent.Text(message),
                    isError = !observedRead,
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

    private fun withBudgetState(result: AgentToolResult): AgentToolResult = snapshot().attach(result)

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
        val configured = outputByteLimit.get().coerceAtLeast(0)
        return "Tool-result byte budget exhausted: $configured/$configured bytes used. " +
            "Summarize the successful results already available. A further read needs a new allowance for tool-result bytes; increasing the call limit alone cannot admit it."
    }

    private companion object {
        // Below a useful excerpt, spend the remaining bytes on one source and report exhaustion.
        fun sourceExcerptAllowance(totalBytes: Int) = minOf(totalBytes, 16 * 1024, (totalBytes / 4).coerceAtLeast(256))

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
