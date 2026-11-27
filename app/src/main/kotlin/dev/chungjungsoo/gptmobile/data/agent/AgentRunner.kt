package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import dev.chungjungsoo.gptmobile.data.github.GitHubExecutionLimits
import dev.chungjungsoo.gptmobile.data.network.RequestByteBudget
import dev.chungjungsoo.gptmobile.data.network.RequestByteBudgetScope
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentRunLimits(
    val runTimeoutMillis: Long = Long.MAX_VALUE,
    val maxRounds: Int = Int.MAX_VALUE,
    val maxToolCalls: Int = 50,
    val maxConcurrentTools: Int = 6,
    val toolTimeoutMillis: Long = 45_000L,
    val maxToolOutputBytes: Int = 256 * 1024,
    val contextTokens: Int = Int.MAX_VALUE,
    val initialContextTokens: Int = 0,
    val finalResponseReserveTokens: Int = 2048,
    val finalResponseToolCallReserve: Int = 0,
    /** Maximum replay budget for prior tool exchanges sent on each provider round. */
    val maxReplayTokens: Int = 4_000,
    /** Per-result replay budget when the full evidence exceeds a provider round's allowance. */
    val maxReplayResultTokens: Int = 512,
    val maxObservedToolCalls: Int = 256,
    val maxToolCallsPerRound: Int = 32,
    val maxRetainedEvidenceTokens: Int = 16_000,
    val allowToolStormFinalization: Boolean = true,
    val maxGeneratedCharacters: Int = 0,
    val detectRepetition: Boolean = false,
    val researchAcquisition: Boolean = false,
    val githubTask: Boolean = false,
    /** Trusted accepted objective; retained tool output never supplies investigation intent. */
    val githubTaskRequest: String? = null,
    /** GitHub retains its full authorized inventory but advertises fitting schemas per round. */
    val githubMode: Boolean = false,
    /** Optional objective for reading a saved batch; this is not an end-to-end recovery target. */
    val checkpointRestoreObjective: CheckpointRestoreObjective? = null,
    val combined: CombinedExecutionContext? = null,
    /** Schemas actually charged in initialContextTokens, before later selection or recovery. */
    val initialGitHubSchemaTokens: Long? = null
) {
    companion object {
        const val DEFAULT_MAX_TOOL_CALLS: Int = 50

        fun defaultMaxConcurrentTools(): Int = 6

        fun defaultMaxToolOutputBytes(): Int = 256 * 1024
    }
}

private data class ReplayProjection(
    val exchanges: List<AgentToolExchange>,
    val rawTokens: Int,
    val compactedTokens: Int,
    val compactionMicros: Long
)

/**
 * Replay compaction is expected on long tool runs. Report exponential high-water
 * marks and genuinely slow projections instead of writing the same maintenance
 * event on every round and competing with UI/diagnostic persistence locks.
 */
internal class ReplayCompactionLogGate(
    private val slowProjectionMicros: Long = 50_000L
) {
    private var lastLoggedRawTokens = 0
    private var lastLoggedExchangeBucket = 0

    @Synchronized
    fun shouldLog(rawTokens: Int, exchangeCount: Int, compactionMicros: Long): Boolean {
        val bucket = Integer.highestOneBit(exchangeCount.coerceAtLeast(1))
        val first = lastLoggedRawTokens == 0
        val doubled = lastLoggedRawTokens > 0 && rawTokens.toLong() >= lastLoggedRawTokens.toLong() * 2L
        val newExchangeBucket = bucket > lastLoggedExchangeBucket
        val slow = compactionMicros >= slowProjectionMicros
        val log = first || doubled || newExchangeBucket || slow
        if (log) {
            lastLoggedRawTokens = maxOf(lastLoggedRawTokens, rawTokens)
            lastLoggedExchangeBucket = maxOf(lastLoggedExchangeBucket, bucket)
        }
        return log
    }
}

class AgentRunner(
    val limits: AgentRunLimits = AgentRunLimits(),
    private val checkpoint: ToolBatchCheckpoint? = null,
    private val healthCatalog: ProgressiveToolCatalog? = null,
    private val retryUndispatchedBudgetFailures: Boolean = false,
    private val onToolResult: (suspend (ProviderEvent.ToolCall, AgentToolResult) -> Unit)? = null,
    private val resumeToolBudget: Boolean = false,
    private val initialToolCallCountOverride: Int? = null,
    private val onToolResultCommitted: (suspend (ProviderEvent.ToolCall, AgentToolResult) -> Unit)? = null,
    private val onToolResultsRestored: (suspend (List<AgentToolExchange>) -> Unit)? = null,
    private val remainingTaskMillis: (suspend () -> Long?)? = null,
    private val replayDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val diagnosticRunId: String? = null,
    private val prepareToolSelection: (suspend () -> Unit)? = null,
    private val verificationOnlyRequested: Boolean = false,
    private val taskAdmissionFailure: (suspend () -> ProviderEvent.Failed?)? = null,
    private val replayPriorityCallIds: () -> Set<String> = { emptySet() }
) {
    private val replayRenderCache = ToolExchangeCompactor.RenderCache()
    private val replayCompactionLogGate = ReplayCompactionLogGate()
    private val effectiveReplayTokens = if (limits.contextTokens == Int.MAX_VALUE) {
        limits.maxReplayTokens
    } else {
        val promptCapacity = RequestAdmission.maximumEstimatedPrompt(limits.contextTokens.toLong() - limits.finalResponseReserveTokens)
        minOf(limits.maxReplayTokens.toLong(), (promptCapacity - limits.initialContextTokens.coerceAtLeast(0)).coerceAtLeast(0)).toInt()
    }

    /** Serialization, hashing, and replay projection can scan megabytes of evidence. */
    private suspend fun prepareReplay(
        exchanges: List<AgentToolExchange>,
        maxReplayTokens: Int,
        maxResultTokens: Int,
        consumedCallIds: Set<String> = emptySet(),
        deliveredResultDigests: Map<String, String> = emptyMap(),
        requiredSavedPageDigests: Map<String, String?> = emptyMap()
    ): ReplayProjection = withContext(replayDispatcher) {
        val rawTokens = ToolExchangeCompactor.estimateTokens(exchanges, replayRenderCache)
        val started = System.nanoTime()
        val consumed = ToolResultDeliveryProof.qualifiedConsumed(exchanges, consumedCallIds, deliveredResultDigests, replayRenderCache)
        val compacted = ToolExchangeCompactor.compact(
            exchanges,
            maxReplayTokens,
            if (rawTokens <= maxReplayTokens) maxReplayTokens else maxResultTokens,
            consumed,
            replayRenderCache,
            if (limits.githubTask) GitHubReplayRecovery.savedPageResultAllowances(exchanges, consumed, maxReplayTokens, maxResultTokens) else emptyMap(),
            taskRequest = limits.githubTaskRequest.takeIf { limits.githubTask },
            priorityCallIds = if (limits.githubTask) replayPriorityCallIds() else emptySet(),
            requiredSavedPageDigests = requiredSavedPageDigests
        )
        ReplayProjection(
            exchanges = compacted,
            rawTokens = rawTokens,
            compactedTokens = ToolExchangeCompactor.estimateTokens(compacted, replayRenderCache),
            compactionMicros = ((System.nanoTime() - started) / 1000).coerceAtLeast(0)
        )
    }

    fun run(session: AgentProviderSession, tools: List<AgentTool>, dynamicTools: () -> List<AgentTool> = { emptyList() }, selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition> = { it }): Flow<AgentRunEvent> = flow {
        val deliverySession = if (limits.githubTask && limits.combined == null) session.withSingleRetainedFailureResponse() else session
        emitAll(runWithRepairScope(deliverySession, tools, dynamicTools, selectDefinitions).flowOn(ToolArgumentRepairScope() + RequestByteBudgetScope() + AdaptiveBatchScope(limits.maxConcurrentTools)))
    }

    private fun runWithRepairScope(session: AgentProviderSession, tools: List<AgentTool>, dynamicTools: () -> List<AgentTool>, selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>): Flow<AgentRunEvent> = flow {
        if (verificationOnlyRequested && session.handlesToolsInternally) {
            // An opaque backend can execute before exposing a ToolCall event.
            // Verification must use a session whose tool dispatch remains local.
            emit(
                AgentRunEvent.Provider(
                    verificationOnlyFailure(
                        "This session executes tools internally and cannot verify saved results without starting work. Saved evidence and unfinished operations remain retained.",
                        false
                    )
                )
            )
            return@flow
        }
        taskAdmissionFailure?.invoke()?.let { failure ->
            // Admission may publish retained evidence, but cannot restore queued calls or finish their checkpoint.
            session.retainedFailureResponse(failure).collect { emit(AgentRunEvent.Provider(it)) }
            return@flow
        }
        val acceptedAtEntry = remainingTaskMillis?.invoke()
        if (minOf(limits.runTimeoutMillis, acceptedAtEntry ?: Long.MAX_VALUE) <= 0L) {
            // An expired accepted task may deliver retained evidence, but must not
            // restore queued calls or start the model loop. Delivery remains
            // cancellable and is not charged a fabricated five-second work budget.
            // Legacy callers can supply the accepted limit directly. When the task owns a
            // deadline clock, only that clock can expire the durable task.
            if (limits.githubTask && (remainingTaskMillis == null || acceptedAtEntry != null && acceptedAtEntry <= 0L)) {
                deliverExpiredTask(session, GitHubExecutionLimits.DeadlineCheck.BEFORE_TOOL_RESTORE)
            } else {
                emit(
                    AgentRunEvent.Provider(
                        ProviderEvent.Failed(
                            "The time limit for this run has ended. Completed work is saved. Check the run budget before continuing.",
                            errorCode = "RUN_DEADLINE_REACHED",
                            taskOutcome = "PARTIAL",
                            failureDetails = buildJsonObject {
                                put("failed_stage", "admission")
                                put("dispatched", false)
                                put("budget_exhausted", true)
                            }
                        )
                    )
                )
            }
            return@flow
        }
        if (tools.groupBy { it.definition.name }.any { it.value.size > 1 }) {
            emit(failed("AMBIGUOUS_TOOL_ALIAS", "Ambiguous tool aliases: resolve duplicate model-facing names before execution."))
            return@flow
        }
        val guardedSession = session.withGenerationSafety(GenerationSafety(limits.maxGeneratedCharacters, limits.detectRepetition))
        val toolByName = tools.associateBy { it.definition.name }
        var executableToolByName = toolByName
        var exposedDefinitions = tools.map { it.definition }
        val exchanges = mutableListOf<AgentToolExchange>()
        var rounds = 0
        if (!verificationOnlyRequested && retryUndispatchedBudgetFailures) checkpoint?.prepareExplicitBudgetRetry()
        val recovery = checkpoint?.recoverySnapshot(limits.checkpointRestoreObjective)
        // Reopening an undispatched refusal does not renew the accepted allowance.
        // Only a separate explicit grant may start a new bounded tranche.
        var toolCallCount = initialToolCallCountOverride?.coerceAtLeast(0) ?: if (resumeToolBudget) 0 else recovery?.dispatchedCount ?: 0
        var toolMayHaveExecuted = (recovery?.dispatchedCount ?: 0) > 0
        var retriedWithoutTools = false

        val acceptedRemainingMillis = remainingTaskMillis?.invoke()
        val runTimeoutMillis = minOf(limits.runTimeoutMillis, acceptedRemainingMillis ?: Long.MAX_VALUE)
        if (limits.githubTask && runTimeoutMillis <= 0L) {
            deliverExpiredTask(session, GitHubExecutionLimits.DeadlineCheck.BEFORE_MODEL_LOOP)
            return@flow
        }
        val finishedInTime = if (runTimeoutMillis < Long.MAX_VALUE) {
            withTimeoutOrNull(runTimeoutMillis) {
                executeLoop(guardedSession, toolByName, executableToolByName, exposedDefinitions, exchanges, rounds, toolCallCount, toolMayHaveExecuted, retriedWithoutTools, recovery, dynamicTools, selectDefinitions)
            }
        } else {
            executeLoop(guardedSession, toolByName, executableToolByName, exposedDefinitions, exchanges, rounds, toolCallCount, toolMayHaveExecuted, retriedWithoutTools, recovery, dynamicTools, selectDefinitions)
            true
        }

        if (finishedInTime == null) {
            if (limits.githubTask && acceptedRemainingMillis == runTimeoutMillis) {
                deliverExpiredTask(session, GitHubExecutionLimits.DeadlineCheck.MODEL_LOOP_TIMEOUT)
            } else if (limits.githubTask && exchanges.isNotEmpty()) {
                finishFromRetained(guardedSession, ProviderEvent.Failed("Agent run timed out after $runTimeoutMillis ms. Completed receipts are retained; unfinished work can be resumed.", errorCode = "RUN_DEADLINE_REACHED"), true)
            } else {
                emit(failed("TIMEOUT", "Agent run timed out after $runTimeoutMillis ms."))
            }
        }
    }.withGitHubTerminalDelivery(limits.githubTask && limits.combined == null, session::retainedFailureResponse)
        .flowOn(limits.combined?.let(::CombinedRunScope) ?: kotlin.coroutines.EmptyCoroutineContext)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.executeLoop(
        session: AgentProviderSession,
        toolByName: Map<String, AgentTool>,
        initialExecutableToolByName: Map<String, AgentTool>,
        initialExposedDefinitions: List<AgentToolDefinition>,
        exchanges: MutableList<AgentToolExchange>,
        initialRounds: Int,
        initialToolCallCount: Int,
        initialToolMayHaveExecuted: Boolean,
        initialRetriedWithoutTools: Boolean,
        recovery: ToolBatchRecoverySnapshot?,
        dynamicTools: () -> List<AgentTool>,
        selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>
    ) {
        if (verificationOnlyRequested) {
            verifyRetainedResults(session, toolByName, recovery, exchanges)
            return
        }
        prepareToolSelection?.invoke()
        val initialSelectedDefinitions = selectedForRound(initialExposedDefinitions, selectDefinitions).filter { it.modelVisible }
        val initialSchemaTokens = limits.initialGitHubSchemaTokens ?: initialSelectedDefinitions.sumOf { providerSchemaTokens(it, limits.githubMode) }
        val retainedUnknownOutcome = recovery?.unsettledCalls.orEmpty().any { !replayableRead(it, toolByName[it.name]) }
        val ciInvestigation = GitHubCiInvestigationRecovery(limits.githubTaskRequest)
        val state = LoopState(initialExecutableToolByName, initialExposedDefinitions, initialRounds, initialToolCallCount, initialToolMayHaveExecuted, initialRetriedWithoutTools, recovery, retainedUnknownOutcome, diagnosticRunId, limits.githubTaskRequest.orEmpty())
        if (state.restoredCalls.isNotEmpty() && recovery != null) {
            AppLogRecorder.record("Recovery", "TOOL_BATCH_RESTORED · checkpointVersion=1 · pendingCalls=${state.restoredCalls.size} · previouslyDispatched=${state.restoredDispatched.size} · completed=${recovery.completedCount} · failed=${recovery.failedCount} · uncertain=${recovery.unknownCount} · queued=${recovery.queuedCount} · rejected=${recovery.rejectedCount} · snapshotLoadMs=${recovery.loadMillis} · objectiveMs=${recovery.objectiveMillis ?: "none"} · objectiveStatus=${recovery.objectiveStatus} · measurement=checkpoint_load_only")
        }
        val executionToolCallLimit = ToolBudgetPolicy.executionLimit(limits)
        if (!restoreCompletedExchanges(recovery, toolByName, exchanges, state)) return
        state.savedResultRecovery.observeDelivered(exchanges, state.deliveredResultDigests, replayRenderCache)
        exchanges.forEach(ciInvestigation::observe)
        val completedOperations = linkedSetOf<String>()
        exchanges.forEach { recordCompletedGitHubOperations(it, completedOperations, restoredJournal = true) }
        completedOperations.forEach { healthCatalog?.restoreCompletedPhase(it) }
        if (completeFromReceipts(session, exchanges, state)) return
        // Authorization can retain hidden compatibility tools or contracts omitted
        // from this request. Only selected schemas or queued work need new receipts.
        if ((initialSelectedDefinitions.isNotEmpty() || state.restoredCalls.isNotEmpty()) && !session.handlesToolsInternally && effectiveReplayTokens <= 0) {
            emit(replayAdmissionFailure(effectiveReplayTokens))
            return
        }
        state.replayTokens = prepareReplay(exchanges, effectiveReplayTokens, limits.maxReplayResultTokens, state.consumedCallIds, state.deliveredResultDigests).compactedTokens.toLong()

        var admittedReplayLimit = effectiveReplayTokens
        var capacityReplans = 0
        with(state) {
            while (true) {
                if (completeFromReceipts(session, exchanges, state)) return
                if (!prepareNextRound(session, exchanges, state, executionToolCallLimit)) return
                prepareToolSelection?.invoke()

                // collectRound catches only upstream provider failures. Cancellation and
                // downstream collector errors must leave the run without retrying or emitting.
                val searchContext = searchRoundContext(toolByName, executableToolByName, exchanges)
                val observedLimit = if (limits.combined == null) limits.maxObservedToolCalls.coerceIn(1, 1024) else limits.maxObservedToolCalls.coerceAtLeast(0)
                val automaticRecovery = if (limits.githubTask && !session.handlesToolResultRecovery) savedResultRecovery else null
                val pendingSavedPages = automaticRecovery?.pendingSavedPageDeliveries(exchanges, deliveredResultDigests).orEmpty()
                val authorizedPageTools = if (pendingSavedPages.isEmpty()) emptyList() else exposedDefinitions.filter { it.name in executableToolByName }
                val owedSchema = automaticRecovery?.owedPageSchema(pendingSavedPages, authorizedPageTools, exchanges, replayRenderCache)
                val selected = selectedForRound(exposedDefinitions, selectDefinitions)
                var roundDefinitions = fitRoundDefinitions(state, selected, initialSchemaTokens, completedOperations, owedSchema) ?: return
                reconcileDiscoveryExchange(exchanges, roundDefinitions)
                val restoringBatch = restoredCalls.isNotEmpty()
                val automaticRead = nextAutomaticRead(session, exchanges, roundDefinitions, state, ciInvestigation, admittedReplayLimit, executionToolCallLimit, observedLimit, pendingSavedPages)
                val preparedRound = if (restoringBatch) {
                    RoundResult(calls = restoredCalls, completed = true, observedCalls = restoredCalls.size).also { restoredCalls = emptyList() }
                } else if (automaticRead != null) {
                    emit(AgentRunEvent.Notice(automaticRead.notice, persistent = false))
                    RoundResult(calls = listOf(automaticRead.call), completed = true, observedCalls = 1)
                } else {
                    collectRound(session, roundDefinitions, exchanges, (observedLimit - observedToolCalls).coerceAtLeast(0), searchContext.successful, searchContext.names, consumedCallIds, deliveredResultDigests, retainedUnknownOutcome || unknownOutcomes.isNotEmpty(), admittedReplayLimit, pendingSavedPages, savedPageReading)
                }
                val round = if (preparedRound.unacknowledgedSavedPages.isNotEmpty()) {
                    val latest = checkpoint?.recoverySnapshot()
                    if (latest != null && latest.savedPageReading != savedPageReading) {
                        val knownCalls = SavedPageReading.fingerprints(savedPageReading) + exchanges.flatMap { it.calls }.associate { it.callId to ToolHandoffReceipt.fingerprint(it) }
                        if (!SavedPageReading.canRefresh(savedPageReading, latest.savedPageReading, knownCalls)) {
                            emit(
                                AgentRunEvent.Provider(
                                    SavedPageDeliveryRound.blocked("Another runner changed the saved reading context outside the current source history. Resume from the authorized checkpoint.", "planning") {
                                        put("replay_missing_record", "CHECKPOINT_IDENTITY")
                                    }
                                )
                            )
                            return
                        }
                        restoreReadingState(latest)
                        continue
                    }
                    val pending = pendingSavedPages.filterKeys { it in preparedRound.unacknowledgedSavedPages }
                    val reopenSchema = automaticRecovery?.owedPageSchema(pending, authorizedPageTools)
                    if (reopenSchema != null && reopenSchema != owedSchema) {
                        roundDefinitions = fitRoundDefinitions(state, selected, initialSchemaTokens, completedOperations, reopenSchema) ?: return
                    }
                    val restore = nextAutomaticRead(session, exchanges, roundDefinitions, state, ciInvestigation, admittedReplayLimit, executionToolCallLimit, observedLimit, pending, reopenAvailable = true)
                    if (restore == null) {
                        emit(
                            AgentRunEvent.Provider(
                                SavedPageDeliveryRound.blocked("The original source page could not be reopened from this checkpoint. Earlier notes and tool results are saved.", "planning") {
                                    put("pending_saved_page_count", pending.size)
                                    put("replay_allowance", admittedReplayLimit)
                                    put("replay_missing_record", "AUTHORIZED_REOPEN")
                                    val missing = pending.entries.firstOrNull()
                                    replaySource(exchanges, missing?.key, missing?.value)
                                }
                            )
                        )
                        return
                    }
                    emit(AgentRunEvent.Notice(restore.notice, persistent = false))
                    RoundResult(calls = listOf(restore.call), completed = true, observedCalls = 1)
                } else {
                    preparedRound
                }
                observedToolCalls += round.observedCalls
                if (round.savedPageDelivery) {
                    savedPageReading = requireNotNull(round.savedPageReading)
                    continue
                }
                if (round.batchRejected) {
                    if (!recoverRejectedRound(session, round, state, observedLimit)) return
                    continue
                }
                if (round.toolDefinitionsRejected) {
                    if (!recoverToolDefinitionRejection(round, state)) return
                    continue
                }
                if (round.failure != null) {
                    val recovery = recoverFailedLoopRound(round, session, state, exchanges, admittedReplayLimit, capacityReplans, roundDefinitions, initialSchemaTokens) ?: return
                    admittedReplayLimit = recovery.replayLimit
                    capacityReplans = recovery.capacityReplans
                    continue
                }
                if (!session.handlesToolsInternally && admittedReplayLimit <= 0 && round.calls.isNotEmpty()) {
                    // A plain-answer allowance cannot fund an unadvertised call if
                    // a provider ignores its empty schema list. Nothing is dispatched.
                    emit(replayAdmissionFailure(admittedReplayLimit))
                    return
                }
                if (!restoringBatch && automaticRead == null) retrySchemaNames = null
                // A provider may ignore an empty tool catalog. Offer one correction,
                // then stop instead of spending the observed-call ceiling on refusals.
                if (finalResponseRequested && exposedDefinitions.isEmpty() && round.calls.isNotEmpty()) {
                    if (finalizationToolRepairAttempted) {
                        finishFromRetained(session, ProviderEvent.Failed("The provider kept requesting tools after finalization. Completed results are retained; no additional action was dispatched.", errorCode = "FINAL_RESPONSE_REQUIRED"), exchanges.isNotEmpty())
                        return
                    }
                    finalizationToolRepairAttempted = true
                }
                savedResultRecovery.observeDelivered(exchanges, deliveredResultDigests, replayRenderCache)
                val resolvedCalls = resolveRoundCalls(round.calls, restoringBatch, exchanges, executableToolByName, savedResultRecovery, explicitSavedReread)
                resolvedCalls.filterNot { it.callId in restoredCompleted }.forEach { emit(AgentRunEvent.Provider(it)) }
                val calls = routeSearchCalls(resolvedCalls, executableToolByName)
                savedResultRecovery.record(calls)
                calls.filter { CatalogMissRecovery.isRecovery(it.callId) }.forEach { catalogRecoveryAttempts.add(it.callId) }
                if (calls.isEmpty()) {
                    // A successful delivery round may acknowledge the page without
                    // choosing another tool. Resume only an already-admitted automatic
                    // continuation; failed rounds never establish delivery proof.
                    if (pendingSavedPages.isNotEmpty() &&
                        round.completed &&
                        nextAutomaticRead(session, exchanges, roundDefinitions, state, ciInvestigation, admittedReplayLimit, executionToolCallLimit, observedLimit, savedResultRecovery.pendingSavedPageDeliveries(exchanges, deliveredResultDigests)) != null
                    ) {
                        continue
                    }
                    val recovery = recoverRoundWithoutCalls(session, round, exchanges, consumedCallIds, exposedDefinitions, recoveryState, emptyAnswerRecoveryAttempted, finalResponseRequested) ?: return
                    exposedDefinitions = recovery.definitions
                    emptyAnswerRecoveryAttempted = recovery.emptyAnswerRecoveryAttempted
                    finalResponseRequested = recovery.finalResponseRequested
                    rounds += recovery.roundAdjustment
                    if (finalResponseRequested) executableToolByName = emptyMap()
                    continue
                }
                if (!executeToolRound(session, calls, toolByName, roundDefinitions, restoringBatch, exchanges, state, dynamicTools, selectDefinitions, initialSchemaTokens, executionToolCallLimit, automaticRecovery, ciInvestigation, admittedReplayLimit, completedOperations)) return
            }
        }
    }

    /** Each recovery stage retains the loop's ordered mutations without inlining them into its DEX method. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverRejectedRound(
        session: AgentProviderSession,
        round: RoundResult,
        state: LoopState,
        observedLimit: Int
    ): Boolean = with(state) {
        if (limits.combined?.requested?.singlePass == true) {
            emit(failed("TOOL_BATCH_REJECTED", round.failure ?: "Tool batch rejected. Retry this profile explicitly; no calls from this batch were executed."))
            return@with false
        }
        val recovery = recoverRejectedBatch(session, round, exposedDefinitions, observedToolCalls, observedLimit, rounds, batchRepairAttempted, stormFinalizationAttempted, finalResponseRequested) ?: return@with false
        exposedDefinitions = recovery.definitions
        if (recovery.repair) {
            batchRepairAttempted = true
        } else {
            stormFinalizationAttempted = true
            executableToolByName = emptyMap()
            finalResponseRequested = true
        }
        true
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverToolDefinitionRejection(
        round: RoundResult,
        state: LoopState
    ): Boolean = with(state) {
        if (limits.githubMode) {
            emit(AgentRunEvent.Provider(ProviderEvent.Failed(round.failure ?: "This model rejected the required GitHub tool catalog.", errorCode = "GITHUB_TOOL_CONTRACT_UNSUPPORTED", failureDetails = round.failureDetails, invocationId = round.invocationId)))
            return@with false
        }
        if (limits.combined?.requested?.singlePass != true && exposedDefinitions.isNotEmpty() && !toolMayHaveExecuted && !retriedWithoutTools && !round.textEmitted) {
            retriedWithoutTools = true
            exposedDefinitions = emptyList()
            executableToolByName = emptyMap()
            rounds -= 1
            emit(AgentRunEvent.Notice(TOOLS_UNAVAILABLE_MESSAGE, persistent = true))
            return@with true
        }
        emit(failed("TOOL_UNAVAILABLE", round.failure ?: "Tools are unavailable for this model."))
        false
    }

    private data class FailedLoopRoundRecovery(val replayLimit: Int, val capacityReplans: Int)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverFailedLoopRound(
        round: RoundResult,
        session: AgentProviderSession,
        state: LoopState,
        exchanges: MutableList<AgentToolExchange>,
        admittedReplayLimit: Int,
        capacityReplans: Int,
        roundDefinitions: List<AgentToolDefinition>,
        initialSchemaTokens: Long
    ): FailedLoopRoundRecovery? = with(state) {
        val admission = round.failureDetails
        val payloadRetry = if (capacityReplans < 2 && !session.handlesToolsInternally) {
            replanRejectedPayload(round, state, exchanges, admittedReplayLimit, roundDefinitions, initialSchemaTokens)
        } else {
            null
        }
        if (payloadRetry != null) {
            rounds--
            return@with FailedLoopRoundRecovery(payloadRetry, capacityReplans + 1)
        }
        if (capacityReplans < 2 &&
            !session.handlesToolsInternally &&
            !retainedUnknownOutcome &&
            unknownOutcomes.isEmpty() &&
            admission?.get("logical_continuation_allowed") != JsonPrimitive(false) &&
            !round.textEmitted &&
            round.calls.isEmpty() &&
            exchanges.isNotEmpty() &&
            round.errorCode in setOf("CONTEXT_CAPACITY_EXCEEDED", "INPUT_CAPACITY_EXCEEDED") &&
            (admission?.get("attempt_dispatch_state") as? JsonPrimitive)?.content == "NOT_DISPATCHED"
        ) {
            fun count(key: String) = (admission?.get(key) as? JsonPrimitive)?.content?.toLongOrNull()
                ?.takeIf { it in 0..Int.MAX_VALUE }
            val prompt = count("prompt_tokens")
            val safety = count("safety_tokens")
            val ceiling = count("capacity_tokens")
            val output = if (round.errorCode == "INPUT_CAPACITY_EXCEEDED") 0L else count("output_allowance") ?: 0L
            if (prompt != null && safety != null && ceiling != null) {
                val overage = (prompt + safety + output - ceiling).coerceAtLeast(1)
                val requested = (minOf(replayTokens, admittedReplayLimit.toLong()) - overage - 64).coerceAtLeast(0).toInt()
                val fit = prepareReplay(exchanges, requested, limits.maxReplayResultTokens, consumedCallIds, deliveredResultDigests)
                if (requested > 0 && fit.compactedTokens <= requested && fit.compactedTokens.toLong() < replayTokens) {
                    replayTokens = fit.compactedTokens.toLong()
                    rounds--
                    return@with FailedLoopRoundRecovery(requested, capacityReplans + 1)
                }
            }
        }
        val recovery = recoverFailedRound(round, session, exposedDefinitions, finalResponseRequested, exchanges.isNotEmpty() || round.calls.isNotEmpty(), retainedUnknownOutcome, unknownOutcomes.values.toList(), recoveryState) ?: return@with null
        exposedDefinitions = recovery.definitions
        if (recovery.restoreRound) rounds--
        if (recovery.finalize) {
            executableToolByName = emptyMap()
            finalResponseRequested = true
            instructFinalResponse(exchanges, consumedCallIds)
        }
        FailedLoopRoundRecovery(admittedReplayLimit, capacityReplans)
    }

    private fun replayAdmissionFailure(allowance: Int) = AgentRunEvent.Provider(
        ProviderEvent.Failed(
            "No actionable tool receipt fits the replay budget. Reallocate stage output or narrow schemas before starting tool work.",
            errorCode = "REPLAY_EVIDENCE_REQUIRED",
            failureDetails = buildJsonObject {
                put("failed_stage", "planning")
                put("dispatched", false)
                put("failure_origin", "APPLICATION")
                put("replay_budget_state", "NO_INITIAL_ALLOWANCE")
                put("replay_allowance", allowance.coerceAtLeast(0))
            }
        )
    )

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.fitRoundDefinitions(
        state: LoopState,
        selected: List<AgentToolDefinition>,
        initialSchemaTokens: Long,
        completedOperations: Set<String>,
        owedSchema: AgentToolDefinition?
    ): List<AgentToolDefinition>? = with(state) {
        val candidates = if (owedSchema == null) selected else listOf(owedSchema) + selected.filterNot { ProgressiveToolCatalog.isGitHubTool(it) }

        val available = try {
            fitProviderToolSchemas(
                candidates,
                limits,
                initialSchemaTokens,
                replayTokens + SavedPageReading.reservation(savedPageReading, false),
                discoveryPaused = unproductiveDiscoveryRounds >= 2 && retrySchemaNames == null,
                completedOperations = completedOperations.toList(),
                focusedOperation = healthCatalog?.focusedOperation(candidates),
                owedOperation = owedSchema?.name
            )
        } catch (capacity: GitHubToolContextBudgetException) {
            emit(AgentRunEvent.Provider(capacity.providerFailure()))
            return@with null
        }
        val fitted = retrySchemaNames?.let { names -> available.filter { it.name in names || it.name == owedSchema?.name } } ?: available
        afterRepeatedGitHubDiscovery(fitted, unproductiveDiscoveryRounds >= 2)
    }

    private fun resolveRoundCalls(
        calls: List<ProviderEvent.ToolCall>,
        restoringBatch: Boolean,
        exchanges: List<AgentToolExchange>,
        executableToolByName: Map<String, AgentTool>,
        savedResultRecovery: GitHubReplayRecovery.State?,
        explicitSavedReread: Boolean
    ): List<ProviderEvent.ToolCall> = calls.map { call ->
        // Stamp live calls at the trusted routing boundary; restored journals retain their identity.
        val identified = if (restoringBatch) call else call.captureProviderInvocation()
        val resolved = resolveGitHubReadAlias(GitHubRecoveryCalls.resolve(identified, exchanges, executableToolByName), executableToolByName.keys)
        val canonical = resolved.copy(arguments = dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(resolved.name, resolved.arguments))
        if (restoringBatch) {
            canonical
        } else {
            val repaired = GitHubRecoveryCalls.repairSourceRead(canonical, executableToolByName[canonical.name])
            savedResultRecovery?.advanceSavedRead(repaired, executableToolByName[repaired.name]?.definition, repairRepeatedProviderRead = !explicitSavedReread) ?: repaired
        }
    }

    /** Keep batch dispatch and its ordered state updates out of the loop's DEX method. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.executeToolRound(
        session: AgentProviderSession,
        calls: List<ProviderEvent.ToolCall>,
        toolByName: Map<String, AgentTool>,
        roundDefinitions: List<AgentToolDefinition>,
        restoringBatch: Boolean,
        exchanges: MutableList<AgentToolExchange>,
        state: LoopState,
        dynamicTools: () -> List<AgentTool>,
        selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>,
        initialSchemaTokens: Long,
        executionToolCallLimit: Int,
        automaticRecovery: GitHubReplayRecovery.State?,
        ciInvestigation: GitHubCiInvestigationRecovery,
        admittedReplayLimit: Int,
        completedOperations: MutableSet<String>
    ): Boolean {
        with(state) {
            val roundLimitReached = limits.maxRounds < Int.MAX_VALUE && rounds >= limits.maxRounds

            val remainingCalls = if (executionToolCallLimit == Int.MAX_VALUE) {
                calls.size
            } else {
                ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount)
            }
            val completed = processToolCalls(
                calls, toolByName, executableToolByName, exposedDefinitions, roundDefinitions, restoringBatch, exchanges, dynamicTools, selectDefinitions,
                initialSchemaTokens, replayTokens, toolCallsByName, repeatAdmissionHistory, consecutiveToolFailures, blockedReadScopes,
                remainingCalls, toolCallCount, restoredDispatched, restoredRetained, restoredCompleted, roundLimitReached, finalResponseRequested,
                retriedWithoutTools, executionToolCallLimit, suppressedReadRounds, unproductiveDiscoveryRounds, unknownOutcomes, retainedUnknownOutcome, automaticRecovery, ciInvestigation,
                admittedReplayLimit, consumedCallIds, deliveredResultDigests
            ) { fresh -> completeFromReceipts(session, exchanges + fresh, state) } ?: return false
            exchanges.lastOrNull()?.let { recordCompletedGitHubOperations(it, completedOperations) }
            executableToolByName = completed.tools
            exposedDefinitions = completed.definitions
            toolCallCount = completed.toolCallCount
            restoredDispatched = emptySet()
            restoredRetained = emptySet()
            restoredCompleted = emptyMap()
            toolMayHaveExecuted = toolMayHaveExecuted || completed.executedAny
            suppressedReadRounds = completed.suppressedReadRounds
            replayTokens = completed.batch.replayTokens
            unproductiveDiscoveryRounds = completed.batch.discoveryRounds
            finalResponseRequested = completed.batch.finalResponseRequested
            unsuccessfulGitHubRounds = if (limits.githubTask && !completed.batch.hasSuccessfulResult) unsuccessfulGitHubRounds + 1 else 0
            if (completed.executedAny && exchanges.lastOrNull()?.results?.any { !it.isError && !it.sharedResult } == true) {
                // Transport recovery is consecutive-outage state. A new successful
                // operation is a progress boundary, not another retry of that outage.
                recoveryState.githubRecoveries = 0
            }
            val stalled = if (limits.githubTask) exchanges.lastOrNull()?.let(investigation::observe) else null
            if ((unsuccessfulGitHubRounds >= 3 || stalled != null) && !finalResponseRequested && limits.combined?.requested?.singlePass != true) {
                val replan = if (automaticRecovery != null && !session.handlesToolsInternally && !retainedUnknownOutcome && unknownOutcomes.isEmpty()) {
                    resumeGitHubInvestigation(investigation, ciInvestigation, exposedDefinitions.filter { it.name in executableToolByName }, exchanges, consumedCallIds)
                } else {
                    null
                }
                if (replan != null) {
                    unsuccessfulGitHubRounds = 0
                    emit(AgentRunEvent.Notice(replan, persistent = false))
                } else {
                    finalResponseRequested = true
                    exposedDefinitions = emptyList()
                    executableToolByName = emptyMap()
                    instructFinalResponse(exchanges, consumedCallIds)
                    emit(AgentRunEvent.Notice(stalled ?: "GitHub operations failed for three consecutive rounds. Finishing with saved results and the remaining blockers.", persistent = false))
                }
            }
        }
        return true
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.completeFromReceipts(
        session: AgentProviderSession,
        exchanges: List<AgentToolExchange>,
        state: LoopState
    ): Boolean {
        if (!limits.githubTask ||
            verificationOnlyRequested ||
            state.restoredCalls.isNotEmpty() ||
            state.retainedUnknownOutcome ||
            state.unknownOutcomes.isNotEmpty()
        ) {
            return false
        }
        val response = session.completeFromReceipts(exchanges) ?: return false
        response.collect { event ->
            check(event !is ProviderEvent.ToolCall && event !is ProviderEvent.ToolResult) { "Receipt completion cannot dispatch operations" }
            if (event is ProviderEvent.Completed) checkpoint?.finish()
            emit(AgentRunEvent.Provider(event))
        }
        return true
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.replanRejectedPayload(
        round: RoundResult,
        state: LoopState,
        exchanges: List<AgentToolExchange>,
        admittedReplayLimit: Int,
        definitions: List<AgentToolDefinition>,
        initialSchemaTokens: Long
    ): Int? {
        val admission = round.failureDetails ?: return null
        if (state.retainedUnknownOutcome ||
            state.unknownOutcomes.isNotEmpty() ||
            round.textEmitted ||
            round.calls.isNotEmpty() ||
            admission["logical_continuation_allowed"] == JsonPrimitive(false) ||
            admission["requires_reconciliation"] == JsonPrimitive(true) ||
            round.providerLimit != null ||
            round.taskOutcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED", "WAITING_FOR_APPROVAL")
        ) {
            return null
        }
        if (admission["http_status"] != JsonPrimitive(413) && round.errorCode != RequestByteBudget.CODE) return null
        if (kotlinx.coroutines.currentCoroutineContext()[RequestByteBudgetScope]?.consumeReplan(admission) != true) return null
        val joint = RequestByteBudget.jointTarget(admission, minOf(state.replayTokens, admittedReplayLimit.toLong()), definitions.sumOf { providerSchemaTokens(it, limits.githubMode) })
        if (joint != null) {
            val compacted = prepareReplay(exchanges, joint.replayTokens, limits.maxReplayResultTokens, state.consumedCallIds, state.deliveredResultDigests)
            val narrower = narrowerSchemaWindow(definitions, admission, initialSchemaTokens, compacted.compactedTokens.toLong(), joint.schemaTokens)
            if (narrower != null && compacted.compactedTokens <= joint.replayTokens && compacted.compactedTokens.toLong() < state.replayTokens) {
                state.retrySchemaNames = narrower
                state.replayTokens = compacted.compactedTokens.toLong()
                emit(AgentRunEvent.Notice("Reduced tool descriptions and replay together to fit the provider's measured request limit.", persistent = false))
                return joint.replayTokens
            }
        }
        val target = RequestByteBudget.replayTarget(admission, state.replayTokens, admittedReplayLimit)
        if (target != null) {
            val fit = prepareReplay(exchanges, target, limits.maxReplayResultTokens, state.consumedCallIds, state.deliveredResultDigests)
            if (fit.compactedTokens <= target && fit.compactedTokens.toLong() < state.replayTokens) {
                state.replayTokens = fit.compactedTokens.toLong()
                emit(AgentRunEvent.Notice("The provider's request-size limit was reached. I have retained the findings and am reducing the tool-result replay before continuing.", persistent = false))
                return target
            }
        }
        state.retrySchemaNames = narrowerSchemaWindow(definitions, admission, initialSchemaTokens, state.replayTokens) ?: return null
        emit(AgentRunEvent.Notice("The provider rejected this request's size. Tool discovery stays available while I send fewer optional tool descriptions.", persistent = false))
        return admittedReplayLimit
    }

    /** Defer optional schemas for the rejected model round. Discovery may still select every authorized operation. */
    private fun narrowerSchemaWindow(definitions: List<AgentToolDefinition>, admission: JsonObject?, initialSchemaTokens: Long, replayTokens: Long, targetOverride: Int? = null): Set<String>? {
        if (definitions.none { it.name == ProgressiveToolCatalog.NAME }) return null
        val current = definitions.sumOf { providerSchemaTokens(it, limits.githubMode) }
        val target = targetOverride ?: RequestByteBudget.schemaTarget(admission, current) ?: return null
        val selected = try {
            fitProviderToolSchemas(definitions, limits, initialSchemaTokens, replayTokens, target)
        } catch (_: GitHubToolContextBudgetException) {
            return null
        }
        return selected.mapTo(linkedSetOf()) { it.name }.takeIf { ProgressiveToolCatalog.NAME in it && it.size < definitions.size }
    }

    /** Verification can read settled receipts, but cannot advance the saved execution journal. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.verifyRetainedResults(
        session: AgentProviderSession,
        toolByName: Map<String, AgentTool>,
        recovery: ToolBatchRecoverySnapshot?,
        exchanges: MutableList<AgentToolExchange>
    ) {
        val retainedUnknownOutcome = recovery?.unsettledCalls.orEmpty().any { !replayableRead(it, toolByName[it.name]) }
        val state = LoopState(emptyMap(), emptyList(), 0, recovery?.dispatchedCount ?: 0, false, false, recovery, retainedUnknownOutcome, diagnosticRunId, limits.githubTaskRequest.orEmpty())
        if (!restoreCompletedExchanges(recovery, toolByName, exchanges, state)) return
        if (recovery?.unsettledCalls.orEmpty().isNotEmpty()) {
            emit(
                AgentRunEvent.Provider(
                    verificationOnlyFailure(
                        "A saved operation has an unresolved outcome. Reconcile it before verifying completion; no operation was dispatched again.",
                        true
                    )
                )
            )
            return
        }
        // A queued batch, saved-result continuation, CI investigation, or provider
        // tool request is execution work. None may precede the verifier's guard.
        val round = collectRound(
            session,
            emptyList(),
            exchanges,
            0,
            false,
            emptySet(),
            state.consumedCallIds,
            state.deliveredResultDigests,
            retainedUnknownOutcome || state.unknownOutcomes.isNotEmpty(),
            savedPageReading = state.savedPageReading
        )
        if (round.failure != null) {
            emit(
                AgentRunEvent.Provider(
                    ProviderEvent.Failed(
                        round.failure,
                        errorCode = round.errorCode,
                        progressDeadline = round.progressDeadline,
                        transportComplete = round.failureTransportComplete,
                        providerLimit = round.providerLimit,
                        taskOutcome = round.taskOutcome,
                        failureDetails = round.failureDetails,
                        invocationId = round.invocationId
                    )
                )
            )
        } else if (round.completed && round.textEmitted) {
            // Completion belongs to verification only. Pending operations and
            // their original call grouping remain available for reconciliation.
            emit(AgentRunEvent.Provider(ProviderEvent.Completed))
        } else {
            emit(
                AgentRunEvent.Provider(
                    verificationOnlyFailure(
                        "Verification returned no final answer. Saved evidence and unfinished operations remain retained; no tool work was started.",
                        retainedUnknownOutcome || state.unknownOutcomes.isNotEmpty()
                    )
                )
            )
        }
    }

    private fun verificationOnlyFailure(message: String, hasUnknownOutcome: Boolean): ProviderEvent.Failed = ProviderEvent.Failed(
        message,
        errorCode = "GITHUB_TASK_INCOMPLETE",
        taskOutcome = "BLOCKED",
        failureDetails = buildJsonObject {
            put("failed_stage", "verification")
            put("dispatched", false)
            put("resume_allowed", false)
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", false)
            if (hasUnknownOutcome) put("requires_reconciliation", true)
        }
    )

    private fun resumeGitHubInvestigation(
        progress: GitHubInvestigationProgress,
        ci: GitHubCiInvestigationRecovery,
        tools: List<AgentToolDefinition>,
        exchanges: MutableList<AgentToolExchange>,
        consumedCallIds: MutableSet<String>
    ): String? {
        val instruction = if (ci.activate(tools)) {
            progress.resume()
            "Repeated workflow discovery did not inspect executed CI. Continue the accepted diagnosis with bounded, revision-pinned runs, observed run attempts, failed jobs and log ranges. Existing authorization and task budgets still apply. Retain unavailable observations and do not claim unexecuted tests passed."
        } else {
            progress.replan(exchanges, tools) ?: return null
        }
        val last = exchanges.lastOrNull() ?: return null
        val result = last.results.lastOrNull() ?: return null
        exchanges[exchanges.lastIndex] = last.copy(results = last.results.dropLast(1) + appendInstruction(result, instruction))
        consumedCallIds.remove(result.callId)
        return "The investigation stalled on repeated reads. Continuing with remaining authorized observations."
    }

    private data class AutomaticRead(val call: ProviderEvent.ToolCall, val notice: String)

    /** Deliver each admitted saved page before another automatic planner can displace it. */
    private fun nextAutomaticRead(
        session: AgentProviderSession,
        exchanges: List<AgentToolExchange>,
        definitions: List<AgentToolDefinition>,
        state: LoopState,
        ciInvestigation: GitHubCiInvestigationRecovery,
        replayLimit: Int,
        executionToolCallLimit: Int,
        observedLimit: Int,
        pendingSavedPages: Map<String, String?>,
        reopenAvailable: Boolean = false
    ): AutomaticRead? {
        return with(state) {
            if (session.handlesToolResultRecovery ||
                finalResponseRequested ||
                restoredCalls.isNotEmpty() ||
                observedToolCalls >= observedLimit ||
                reopenAvailable &&
                pendingSavedPages.isEmpty()
            ) {
                return null
            }
            val remoteAllowed = !session.handlesToolsInternally && !retainedUnknownOutcome && unknownOutcomes.isEmpty() && limits.combined?.requested?.singlePass != true
            if (pendingSavedPages.isNotEmpty()) {
                if (!remoteAllowed || ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount) <= 0) return null
                val pageBudget = minOf(replayLimit, limits.maxRetainedEvidenceTokens.coerceIn(1024, 262_144))
                val restore = savedResultRecovery.restoreMissingPage(pendingSavedPages, exchanges, definitions.filter { it.name in executableToolByName }, pageBudget, reopenAvailable)
                return restore?.let {
                    val status = if ("view_cursor" in it.arguments) "Restoring the exact saved page from the authorized local view." else "Reading the original file ranges at their pinned commit to recover the saved page."
                    AutomaticRead(it, status)
                }
            }
            val exhaustiveSearch = limits.githubTask && dev.chungjungsoo.gptmobile.data.github.GitHubExecutionContinuation.requiresExhaustiveSearch(limits.githubTaskRequest.orEmpty())
            if (remoteAllowed && definitions.any { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) == "github_ci" || exhaustiveSearch && dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) == "github_search" }) {
                GitHubReplayRecovery.nextRemoteRead(exchanges, definitions, savedResultRecovery, allowSearchContinuation = exhaustiveSearch, searchTask = limits.githubTaskRequest)?.let {
                    return AutomaticRead(it, "Reading the next workflow page from the issued continuation.")
                }
            }
            if (remoteAllowed &&
                executableToolByName[ProgressiveToolCatalog.NAME]?.argumentCatalog() != null &&
                ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount) > 0
            ) {
                CatalogMissRecovery.next(exchanges, definitions.filter { it.name in executableToolByName }, catalogRecoveryAttempts)?.let {
                    return AutomaticRead(it, if (it.name == ProgressiveToolCatalog.NAME) "Checking the authorized tool catalog." else "Using the verified reading tool from the catalog.")
                }
            }
            if (!limits.githubTask) return null
            if (remoteAllowed && !limits.githubTaskRequest.isNullOrBlank() && ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount) > 0) {
                PhoneIndexContinuation.next(exchanges, definitions.filter { it.name in executableToolByName })?.let {
                    return AutomaticRead(it, "Checking retained phone index progress at the selected revision.")
                }
            }
            GitHubReplayRecovery.nextSavedRead(exchanges, definitions, minOf(limits.maxReplayResultTokens, replayLimit / 2), savedResultRecovery, replayLimit, limits.maxToolOutputBytes)?.let {
                return AutomaticRead(it, "Reading the saved GitHub result; the remote operation is not being repeated.")
            }
            if (remoteAllowed && ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount) > 0) {
                ciInvestigation.nextCall(definitions.filter { it.name in executableToolByName })?.let {
                    return AutomaticRead(it, "Continuing CI diagnosis with the next revision-bound run, job or log observation.")
                }
            }
            null
        }
    }

    /** One execution's mutable state; keeping it together avoids coroutine spill growth. */
    private class LoopState(
        initialExecutableToolByName: Map<String, AgentTool>,
        initialExposedDefinitions: List<AgentToolDefinition>,
        initialRounds: Int,
        initialToolCallCount: Int,
        initialToolMayHaveExecuted: Boolean,
        initialRetriedWithoutTools: Boolean,
        recovery: ToolBatchRecoverySnapshot?,
        val retainedUnknownOutcome: Boolean,
        diagnosticRunId: String?,
        acceptedTask: String
    ) {
        var executableToolByName = initialExecutableToolByName
        var exposedDefinitions = initialExposedDefinitions
        var rounds = initialRounds
        var toolCallCount = initialToolCallCount
        var toolMayHaveExecuted = initialToolMayHaveExecuted
        var retriedWithoutTools = initialRetriedWithoutTools
        val recoveryState = RoundRecoveryState()
        var emptyAnswerRecoveryAttempted = false
        var finalResponseRequested = false
        var roundLimitFinalizationAttempted = false
        var wrapUpNoticeEmitted = false
        var replayTokens = 0L
        var retrySchemaNames: Set<String>? = null
        var observedToolCalls = 0
        var stormFinalizationAttempted = false
        var batchRepairAttempted = false
        var unproductiveDiscoveryRounds = 0
        var suppressedReadRounds = 0
        var unsuccessfulGitHubRounds = 0
        val investigation = GitHubInvestigationProgress(acceptedTask)
        val explicitSavedReread = Regex("(?i)\\b(?:re-read|reread|read again|revisit|read.*again)\\b").containsMatchIn(acceptedTask)
        var finalizationToolRepairAttempted = false
        var restoredCalls = recovery?.pending.orEmpty()
        var restoredDispatched = recovery?.pendingDispatchedIds.orEmpty()
        var restoredRetained = recovery?.pendingRetainedIds.orEmpty()
        var restoredCompleted = emptyMap<String, AgentToolResult>()
        val toolCallsByName = mutableMapOf<String, Int>()
        val repeatAdmissionHistory = ToolRepeatAdmissionHistory.forLoop(diagnosticRunId, recovery, initialToolCallCount)
        val consecutiveToolFailures = mutableMapOf<String, Int>()
        val blockedReadScopes = mutableSetOf<String>()
        val unknownOutcomes = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
        val consumedCallIds = mutableSetOf<String>()
        val deliveredResultDigests = mutableMapOf<String, String>()
        var savedPageReading: JsonObject? = null
        val catalogRecoveryAttempts = recovery?.completedExchanges.orEmpty().flatMap { it.calls }.map { it.callId }.filter(CatalogMissRecovery::isRecovery).toMutableSet()
        val savedResultRecovery = GitHubReplayRecovery.State(
            recovery?.completedExchanges.orEmpty().flatMap { it.calls } + recovery?.pending.orEmpty(),
            recovery?.completedExchanges.orEmpty().flatMap { it.results } + recovery?.pendingCompletedExchange?.results.orEmpty()
        )
        init {
            if (recovery != null) restoreReadingState(recovery)
        }

        fun restoreReadingState(recovery: ToolBatchRecoverySnapshot) {
            consumedCallIds.clear()
            consumedCallIds += recovery.consumedCallIds
            deliveredResultDigests.clear()
            deliveredResultDigests += recovery.deliveredResultDigests
            savedPageReading = recovery.savedPageReading
            // Historical schema-2 delivery is not a stateless request's context. Re-admit
            // retained full pages lacking durable notes, without repeating their tools.
            // Admission-only/missing bodies still fail the ordinary pending-page guard.
            val restored = recovery.completedExchanges + listOfNotNull(recovery.pendingCompletedExchange)
            val withoutNotes = GitHubReplayRecovery.savedPageResultAllowances(restored, emptySet(), Int.MAX_VALUE, 512).keys - SavedPageReading.fingerprints(savedPageReading).keys
            consumedCallIds.removeAll(withoutNotes)
            deliveredResultDigests.keys.removeAll(withoutNotes)
        }
    }

    /** Deliver retained receipts before provider work without expanding the loop coroutine. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.restoreCompletedExchanges(
        recovery: ToolBatchRecoverySnapshot?,
        toolByName: Map<String, AgentTool>,
        exchanges: MutableList<AgentToolExchange>,
        state: LoopState
    ): Boolean {
        with(state) {
            val completed = recovery?.completedExchanges.orEmpty().filter { exchange -> exchange.calls.all { it.name in toolByName } }
            val pendingCompleted = recovery?.pendingCompletedExchange?.let { exchange ->
                val admittedCalls = exchange.calls.filter { it.name in toolByName }
                val admittedIds = admittedCalls.mapTo(hashSetOf()) { it.callId }
                admittedCalls.takeIf { it.isNotEmpty() }?.let {
                    AgentToolExchange(it, exchange.results.filter { result -> result.callId in admittedIds })
                }
            }
            val retained = completed + listOfNotNull(pendingCompleted)
            // Keep partial receipts in their original assistant call group.
            restoredCompleted = pendingCompleted?.results.orEmpty().associateBy { it.callId }
            if (limits.githubTask || verificationOnlyRequested) {
                if (retained.isEmpty()) return true
                // Restore current authorization/evidence once, before provider work.
                // Completion callbacks persist new work and must not replay here.
                var observedPair: Pair<ProviderEvent.ToolCall, AgentToolResult>? = null
                try {
                    if (onToolResultsRestored != null) {
                        onToolResultsRestored.invoke(retained)
                    } else {
                        for (exchange in retained) {
                            val results = exchange.results.associateBy { it.callId }
                            for (call in exchange.calls) {
                                val result = results.getValue(call.callId)
                                observedPair = call to result
                                onToolResult?.invoke(call, result)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val observed = observedPair ?: (retained.first().calls.first() to retained.first().results.first())
                    val failure = ToolFailureMetadata.withAuthorityFailure(observed.second, error, "delivery") ?: throw error
                    observedPair?.let { emit(AgentRunEvent.ToolFinished(it.first, failure)) }
                    emit(AgentRunEvent.Provider(ToolFailureMetadata.authorityFailure(failure, retainedUnknownOutcome)))
                    return false
                }
                retained.forEach { exchange ->
                    val results = exchange.results.associateBy { it.callId }
                    for (call in exchange.calls) {
                        if (results.getValue(call.callId).errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead(call, toolByName[call.name])) {
                            unknownOutcomes.putIfAbsent(call.name + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(call.arguments), ToolReconciliationEvidence.observation(call, results.getValue(call.callId)))
                        }
                    }
                }
                exchanges += completed
                emit(AgentRunEvent.ToolResultsRestored(retained.sumOf { it.results.size }))
                return true
            }
            // Restore all settled batches before model finalization, without executing them.
            // Full GitHub receipts are reloaded by call identity by the evidence observer.
            for (exchange in retained) {
                val results = exchange.results.associateBy { it.callId }
                for (call in exchange.calls) {
                    val result = results.getValue(call.callId)
                    val restored = try {
                        onToolResult?.invoke(call, result)
                        onToolResultCommitted?.invoke(call, result)
                        result
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        ToolFailureMetadata.withAuthorityFailure(result, error, "delivery") ?: throw error
                    }
                    // Replayed receipts remain visible as shared results, without a fresh dispatch.
                    emit(AgentRunEvent.ToolFinished(call, restored))
                    if (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead(call, toolByName[call.name])) {
                        unknownOutcomes.putIfAbsent(call.name + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(call.arguments), ToolReconciliationEvidence.observation(call, results.getValue(call.callId)))
                    }
                    if (restored !== result) {
                        emit(AgentRunEvent.Provider(ToolFailureMetadata.authorityFailure(restored, retainedUnknownOutcome || unknownOutcomes.isNotEmpty())))
                        return false
                    }
                }
                if (exchange !== pendingCompleted) exchanges += exchange
            }
        }
        return true
    }

    /** Apply the original call/round boundaries before the next provider request. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.prepareNextRound(
        session: AgentProviderSession,
        exchanges: List<AgentToolExchange>,
        state: LoopState,
        executionToolCallLimit: Int
    ): Boolean {
        with(state) {
            if (executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit && restoredCalls.isEmpty()) {
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                if (!finalResponseRequested) {
                    finalResponseRequested = true
                    val reserve = limits.finalResponseToolCallReserve.coerceAtLeast(0)
                    if (limits.maxToolCalls > 0) {
                        emit(
                            AgentRunEvent.Notice(
                                "Tool-call limit reached ($toolCallCount/$executionToolCallLimit executable calls; " +
                                    "${limits.maxToolCalls} configured, $reserve reserved). Generating a final response with completed results.",
                                persistent = true
                            )
                        )
                    }
                }
            } else if (ToolBudgetPolicy.shouldEmitWrapUpNotice(executionToolCallLimit, limits, toolCallCount, wrapUpNoticeEmitted)) {
                wrapUpNoticeEmitted = true
                val remainingAllowance = ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount)
                emit(
                    AgentRunEvent.Notice(
                        "Approaching tool limit ($toolCallCount/$executionToolCallLimit executable calls used; " +
                            "$remainingAllowance remaining; ${limits.maxToolCalls} configured).",
                        persistent = false
                    )
                )
            }
            if (limits.maxRounds < Int.MAX_VALUE && rounds >= limits.maxRounds) {
                if (!roundLimitFinalizationAttempted) {
                    roundLimitFinalizationAttempted = true
                    finalResponseRequested = true
                    exposedDefinitions = emptyList()
                    executableToolByName = emptyMap()
                    emit(AgentRunEvent.Notice(ROUND_LIMIT_FINAL_RESPONSE_NOTICE, persistent = false))
                } else {
                    finishFromRetained(
                        session,
                        ProviderEvent.Failed(
                            "Agent could not finalize after ${limits.maxRounds} model/tool rounds and its reserved synthesis attempt. Completed receipts remain retained.",
                            errorCode = "FINAL_RESPONSE_REQUIRED",
                            taskOutcome = "BLOCKED",
                            failureDetails = buildJsonObject {
                                put("failed_stage", "final_synthesis")
                                put("stop_reason", "MODEL_TOOL_ROUND_LIMIT")
                                put("rounds_used", rounds)
                                put("round_limit", limits.maxRounds)
                                put("tool_calls_used", toolCallCount)
                                put("tool_call_limit", executionToolCallLimit)
                                put("tool_calls_remaining", (executionToolCallLimit.toLong() - toolCallCount).coerceAtLeast(0))
                                put("next_action", "RESUME_SYNTHESIS_FROM_RETAINED_EVIDENCE")
                                put("dispatched", false)
                                put("retryable", false)
                            }
                        ),
                        exchanges.isNotEmpty()
                    )
                    return false
                }
            }
            rounds += 1
        }
        return true
    }

    private data class EmptyRoundRecovery(
        val definitions: List<AgentToolDefinition>,
        val emptyAnswerRecoveryAttempted: Boolean,
        val finalResponseRequested: Boolean,
        val roundAdjustment: Int = 0
    )

    /** Keep empty-round recovery outside the main coroutine's DEX method budget. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverRoundWithoutCalls(
        session: AgentProviderSession,
        round: RoundResult,
        exchanges: MutableList<AgentToolExchange>,
        consumedCallIds: MutableSet<String>,
        definitions: List<AgentToolDefinition>,
        recoveryState: RoundRecoveryState,
        emptyAnswerRecoveryAttempted: Boolean,
        finalResponseRequested: Boolean
    ): EmptyRoundRecovery? {
        // An admitted response repair must not stack another evidence-only correction.
        if (round.completed &&
            round.textEmitted &&
            round.evidenceCorrectionEligible &&
            limits.githubTask &&
            exchanges.isEmpty() &&
            definitions.isNotEmpty() &&
            limits.combined?.requested?.singlePass != true &&
            !recoveryState.protocolRecoveryAttempted &&
            !recoveryState.githubOutputRepairAttempted &&
            !emptyAnswerRecoveryAttempted &&
            !session.handlesToolsInternally &&
            !session.handlesToolResultRecovery &&
            !finalResponseRequested
        ) {
            val continued = definitions.map {
                it.copy(description = it.description + " The previous round answered before acquiring repository evidence. Use one relevant read operation now, then answer from its receipt.")
            }
            emit(AgentRunEvent.Notice("The GitHub answer had no repository evidence. Acquiring one bounded evidence round before finalizing.", persistent = false))
            return EmptyRoundRecovery(continued, true, false, -1)
        }
        if (round.completed && !round.textEmitted) {
            if (limits.combined?.requested?.singlePass != true && !emptyAnswerRecoveryAttempted && !session.handlesToolsInternally && !finalResponseRequested) {
                return if (limits.githubTask) {
                    val continued = definitions.map { it.copy(description = it.description + " The previous round was empty. Continue the unfinished task using retained results, or return the verified final outcome.") }
                    emit(AgentRunEvent.Notice("The provider returned an empty round. Resuming the GitHub task with its tools and saved results.", persistent = false))
                    EmptyRoundRecovery(continued, true, false, -1)
                } else {
                    emit(AgentRunEvent.Notice("The model returned no visible answer. Finalizing once with tools disabled and the results already collected.", persistent = false))
                    EmptyRoundRecovery(emptyList(), true, true)
                }
            }
            if (limits.githubTask && limits.combined?.requested?.singlePass != true && !session.handlesToolsInternally && !finalResponseRequested) {
                instructFinalResponse(exchanges, consumedCallIds)
                emit(AgentRunEvent.Notice("The GitHub provider returned another empty round. Generating a text answer from the saved operation results.", persistent = false))
                return EmptyRoundRecovery(emptyList(), emptyAnswerRecoveryAttempted, true)
            }
            finishFromRetained(session, ProviderEvent.Failed("The model returned no visible answer after finalization. No completed tool action was repeated. Please retry or choose another model.", errorCode = "FINAL_RESPONSE_REQUIRED", transportComplete = round.completed), exchanges.isNotEmpty())
            return null
        }
        if (round.completed) {
            checkpoint?.finish()
            emit(AgentRunEvent.Provider(ProviderEvent.Completed))
        }
        return null
    }

    private class RoundRecoveryState {
        var roundRecoveryAttempted = false
        var protocolRecoveryAttempted = false
        var githubRecoveries = 0
        var githubOutputRepairAttempted = false
        var githubProtocolFinalizationAttempted = false
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.finishFromRetained(session: AgentProviderSession, failure: ProviderEvent.Failed, hasResults: Boolean) {
        if (limits.githubTask && hasResults) {
            session.retainedFailureResponse(failure).collect { event ->
                if (event is ProviderEvent.Completed && !verificationOnlyRequested) checkpoint?.finish()
                emit(AgentRunEvent.Provider(event))
            }
        } else {
            emit(AgentRunEvent.Provider(failure))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.deliverExpiredTask(session: AgentProviderSession, check: GitHubExecutionLimits.DeadlineCheck) {
        // A diagnostic reply may complete, but it cannot finish the original
        // execution checkpoint or discard its outstanding cleanup obligations.
        session.retainedFailureResponse(GitHubExecutionLimits.expiredTaskFailure(check)).collect {
            emit(AgentRunEvent.Provider(it))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.deliverTerminalFailure(session: AgentProviderSession, failure: ProviderEvent.Failed, noPendingCalls: Boolean, applicationReportEmitted: Boolean) {
        val reviewOrigin = failure.errorCode?.startsWith("GITHUB_REVIEW_") == true ||
            failure.failureDetails?.get("review_stage") in setOf(JsonPrimitive("answer_review"), JsonPrimitive("review_compatibility"))
        if (limits.githubTask && noPendingCalls && reviewOrigin && !applicationReportEmitted) {
            // A final review refusal publishes its saved explanation only when
            // this round has not already delivered an application report.
            // This callback cannot dispatch a model request or replay an operation.
            session.retainedFailureResponse(failure).collect { event ->
                if (event is ProviderEvent.Completed) checkpoint?.finish()
                emit(AgentRunEvent.Provider(event))
            }
        } else {
            emit(AgentRunEvent.Provider(failure))
        }
    }

    private data class RoundRecovery(val definitions: List<AgentToolDefinition>, val restoreRound: Boolean, val finalize: Boolean)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverFailedRound(
        round: RoundResult,
        session: AgentProviderSession,
        definitions: List<AgentToolDefinition>,
        finalResponseRequested: Boolean,
        hasToolWork: Boolean,
        retainedUnknownOutcome: Boolean,
        unknownOperations: List<JsonObject>,
        state: RoundRecoveryState
    ): RoundRecovery? {
        val roundFailure = requireNotNull(round.failure)
        val unknownSignatureCount = unknownOperations.size
        val hasUnknownOutcome = retainedUnknownOutcome || unknownSignatureCount > 0
        fun unresolvedToolEvidence() = buildJsonObject {
            ToolReconciliationEvidence.summary(unknownOperations).forEach { (key, value) -> put(key, value) }
            put("recovery_observation", "RECONCILIATION_REQUIRED")
            put("pending_tool_receipt_state", "OUTCOMES_UNRESOLVED")
            put(
                "reconciliation_source",
                when {
                    retainedUnknownOutcome && unknownSignatureCount > 0 -> "SAVED_AND_TRACKED"
                    retainedUnknownOutcome -> "SAVED_UNSETTLED_CALL"
                    else -> "TRACKED_CALL_SIGNATURE"
                }
            )
            put("reconciliation_tracked_signature_count", unknownSignatureCount.coerceAtMost(1024))
        }
        if (GatewayOutcomeRecovery.requiresReconciliation(roundFailure, round.errorCode)) {
            // Even a tool-free repair can reach a gateway with server tools enabled.
            // Leave the terminal delivery boundary to report the retained failure without another model request.
            deliverTerminalFailure(session, ProviderEvent.Failed(roundFailure, round.progressDeadline, GatewayOutcomeRecovery.ERROR_CODE, round.failureTransportComplete, round.providerLimit, round.taskOutcome, round.failureDetails, round.invocationId), round.calls.isEmpty(), round.applicationReportEmitted)
            return null
        }
        if (round.errorCode in ToolFailureMetadata.authorityInvalidationCodes || round.taskOutcome == "CONFIGURATION_INVALIDATED") {
            val details = if (hasUnknownOutcome) {
                JsonObject(
                    round.failureDetails.orEmpty() - "dispatched" + unresolvedToolEvidence() + kotlinx.serialization.json.buildJsonObject {
                        put("failed_stage", "tool_dispatch")
                        put("blocked_stage", round.failureDetails?.get("failed_stage") ?: JsonPrimitive("provider_admission"))
                        round.failureDetails?.get("dispatched")?.let { put("blocked_stage_dispatched", it) }
                        round.errorCode?.let { put("authority_invalidation_code", it) }
                        put("resume_required", true)
                        put("requires_reconciliation", true)
                    }
                )
            } else {
                round.failureDetails
            }
            emit(AgentRunEvent.Provider(ProviderEvent.Failed(roundFailure, round.progressDeadline, if (hasUnknownOutcome) "RECONCILIATION_REQUIRED" else round.errorCode, round.failureTransportComplete, round.providerLimit, if (hasUnknownOutcome) "PARTIAL" else "CONFIGURATION_INVALIDATED", details, round.invocationId)))
            return null
        }
        if (hasUnknownOutcome) {
            emit(
                AgentRunEvent.Provider(
                    ProviderEvent.Failed(
                        "RECONCILIATION_REQUIRED: A saved remote operation has an uncertain outcome. Check that operation before continuing; the failed model request did not authorize repeating it.",
                        errorCode = "RECONCILIATION_REQUIRED",
                        taskOutcome = "PARTIAL",
                        failureDetails = JsonObject(
                            round.failureDetails.orEmpty() + unresolvedToolEvidence() + kotlinx.serialization.json.buildJsonObject {
                                put("failed_stage", "tool_dispatch")
                                put("interrupted_provider_code", round.errorCode.orEmpty())
                                put("requires_reconciliation", true)
                                put("logical_continuation_allowed", false)
                            }
                        )
                    )
                )
            )
            return null
        }
        val typedFailure = ProviderEvent.Failed(roundFailure, round.progressDeadline, round.errorCode, round.failureTransportComplete, round.providerLimit, round.taskOutcome, round.failureDetails, round.invocationId)
        val continuationDelay = GitHubTaskRecovery.delayMillis(typedFailure, state.githubRecoveries, hasUnknownOutcome)
        val recoverableConnection = GitHubTaskRecovery.isConnectionUnavailable(typedFailure) && continuationDelay != null
        var exposedDefinitions = definitions
        var finalize = false
        if (round.failureDetails?.get("logical_continuation_allowed") == JsonPrimitive(false)) {
            // Saved-report publication does not start another model round. Keep
            // its receipt and acceptance delivery even when continuation is forbidden,
            // without publishing an application report already delivered by this round.
            if (limits.githubTask && round.calls.isEmpty() && !round.applicationReportEmitted) {
                session.retainedFailureResponse(typedFailure).collect { event ->
                    if (event is ProviderEvent.Completed) checkpoint?.finish()
                    emit(AgentRunEvent.Provider(event))
                }
            } else {
                emit(AgentRunEvent.Provider(typedFailure))
            }
            return null
        }
        if (round.providerLimit != null || round.taskOutcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED", "WAITING_FOR_APPROVAL") || round.errorCode == "WAITING_FOR_APPROVAL" || round.taskOutcome == "PARTIAL" && !recoverableConnection) {
            deliverTerminalFailure(session, typedFailure, round.calls.isEmpty(), round.applicationReportEmitted)
            return null
        }
        val protocolFailure = round.errorCode?.startsWith("TOOL_PROTOCOL_") == true ||
            listOf(
                "Tool arguments were not valid JSON",
                "incomplete function call",
                "unparsed tool-call markup",
                "before completing the tool call"
            ).any { roundFailure.contains(it, ignoreCase = true) }
        if (protocolFailure) {
            if (limits.combined?.requested?.singlePass != true && !state.protocolRecoveryAttempted && (!round.textEmitted || limits.githubTask) && !session.handlesToolsInternally) {
                state.protocolRecoveryAttempted = true

                exposedDefinitions = exposedDefinitions.map { definition ->
                    definition.copy(description = definition.description + " Protocol repair: call this capability only with its native function-call frame and valid JSON arguments. Never print tool-call markup as assistant text.")
                }
                emit(AgentRunEvent.Notice(if (exposedDefinitions.isEmpty()) "The provider attempted a tool call with tools disabled. Requesting a text answer once; no tool action was dispatched." else "The provider returned an invalid tool frame. Retrying its authorized call once.", persistent = false))
                return RoundRecovery(exposedDefinitions, limits.githubTask, finalize)
            }
            if (limits.githubTask && limits.combined?.requested?.singlePass != true && !session.handlesToolsInternally && hasToolWork && !finalResponseRequested && !state.githubProtocolFinalizationAttempted) {
                state.githubProtocolFinalizationAttempted = true
                emit(AgentRunEvent.Notice("The provider could not repair its tool format. Generating a text answer from completed GitHub operations; their actions will not be repeated.", persistent = false))
                return RoundRecovery(emptyList(), restoreRound = true, finalize = true)
            }
            // Bounded, attempt-owned outcome. Never include the malformed
            // frame, model output or tool arguments in an approved incident.
            val recoveryOutcome = when {
                state.githubProtocolFinalizationAttempted -> "TEXT_FALLBACK_EXHAUSTED"
                state.protocolRecoveryAttempted -> "TOOL_RETRY_EXHAUSTED"
                session.handlesToolsInternally -> "BACKEND_HANDLED_TOOLS"
                limits.combined?.requested?.singlePass == true -> "SINGLE_PASS_NOT_RETRYABLE"
                round.textEmitted && !limits.githubTask -> "TEXT_ALREADY_EMITTED"
                else -> "NO_SAFE_REPAIR"
            }
            val observedDetails = JsonObject(
                round.failureDetails.orEmpty() +
                    ("tool_protocol_recovery_outcome" to JsonPrimitive(recoveryOutcome))
            )
            val failure = ProviderEvent.Failed(
                roundFailure,
                round.progressDeadline,
                errorCode = round.errorCode ?: "TOOL_PROTOCOL_INVALID_FRAME",
                failureDetails = observedDetails,
                invocationId = round.invocationId
            )
            if (limits.githubTask && hasToolWork && round.calls.isEmpty()) {
                session.retainedFailureResponse(failure).collect { event ->
                    if (event is ProviderEvent.Completed) checkpoint?.finish()
                    emit(AgentRunEvent.Provider(event))
                }
            } else {
                emit(AgentRunEvent.Provider(failure))
            }
            return null
        }
        if (limits.githubTask && !session.handlesToolsInternally && !hasUnknownOutcome && (!finalResponseRequested || recoverableConnection)) {
            if (!finalResponseRequested && !state.githubOutputRepairAttempted && dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(roundFailure)) {
                state.githubOutputRepairAttempted = true

                exposedDefinitions = exposedDefinitions.map { it.copy(description = it.description + " The previous round reached its output limit. Continue unfinished work with a concise valid function call using retained receipts; do not repeat completed mutations.") }
                emit(AgentRunEvent.Notice("The provider reached its output limit. Repairing the unfinished GitHub round once with its tools and completed results retained.", persistent = false))
                return RoundRecovery(exposedDefinitions, limits.githubTask, finalize)
            }
            val recoveryDelay = continuationDelay
            if (recoveryDelay != null) {
                state.githubRecoveries++

                emit(AgentRunEvent.Notice("Waiting for connection before continuing the saved GitHub task (${state.githubRecoveries}/${GitHubTaskRecovery.recoveryLimit(typedFailure)}). Completed tool results are retained.", persistent = false))
                kotlinx.coroutines.delay(recoveryDelay)
                if (!session.awaitRecoveryReady(30_000L)) {
                    emit(
                        AgentRunEvent.Provider(
                            typedFailure.copy(
                                taskOutcome = "PARTIAL",
                                failureDetails = JsonObject(
                                    typedFailure.failureDetails.orEmpty() + kotlinx.serialization.json.buildJsonObject {
                                        put("waiting_for_connectivity", true)
                                        put("recovery_state", "WAITING_FOR_CONNECTION")
                                        put("resume_required", true)
                                    }
                                )
                            )
                        )
                    )
                    return null
                }
                emit(AgentRunEvent.Notice("Resuming from saved tool results; the interrupted HTTP request and completed actions are not replayed.", persistent = false))
                return RoundRecovery(exposedDefinitions, limits.githubTask, finalize)
            }
        }
        if (!limits.githubTask && limits.combined?.requested?.singlePass != true && !state.roundRecoveryAttempted && !round.textEmitted && !session.handlesToolsInternally && recoverableRoundFailure(roundFailure, hasToolWork)) {
            state.roundRecoveryAttempted = true
            exposedDefinitions = emptyList()
            finalize = true
            emit(AgentRunEvent.Notice("The provider interrupted this round. Finishing once with the results already collected.", persistent = false))
            kotlinx.coroutines.delay(1000)
            return RoundRecovery(exposedDefinitions, limits.githubTask, finalize)
        }
        val failure = ProviderEvent.Failed(roundFailure, round.progressDeadline, round.errorCode, round.failureTransportComplete, round.providerLimit, round.taskOutcome, round.failureDetails, round.invocationId)
        if (limits.githubTask && round.calls.isEmpty()) {
            session.retainedFailureResponse(failure).collect { event ->
                if (event is ProviderEvent.Completed) checkpoint?.finish()
                emit(AgentRunEvent.Provider(event))
            }
        } else {
            emit(AgentRunEvent.Provider(failure))
        }
        return null
    }

    private data class ToolRoundCompletion(
        val tools: Map<String, AgentTool>,
        val definitions: List<AgentToolDefinition>,
        val toolCallCount: Int,
        val executedAny: Boolean,
        val suppressedReadRounds: Int,
        val batch: BatchCompletion
    )

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.processToolCalls(
        calls: List<ProviderEvent.ToolCall>,
        toolByName: Map<String, AgentTool>,
        initialTools: Map<String, AgentTool>,
        initialDefinitions: List<AgentToolDefinition>,
        roundDefinitions: List<AgentToolDefinition>,
        restoringBatch: Boolean,
        exchanges: MutableList<AgentToolExchange>,
        dynamicTools: () -> List<AgentTool>,
        selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>,
        initialSchemaTokens: Long,
        replayTokens: Long,
        toolCallsByName: MutableMap<String, Int>,
        repeatAdmissionHistory: ToolRepeatAdmissionHistory,
        consecutiveToolFailures: MutableMap<String, Int>,
        blockedReadScopes: MutableSet<String>,
        remainingCalls: Int,
        initialToolCallCount: Int,
        restoredDispatched: Set<String>,
        restoredRetained: Set<String>,
        restoredCompleted: Map<String, AgentToolResult>,
        roundLimitReached: Boolean,
        initialFinalResponseRequested: Boolean,
        retriedWithoutTools: Boolean,
        executionToolCallLimit: Int,
        previousSuppressedReadRounds: Int,
        unproductiveDiscoveryRounds: Int,
        unknownOutcomes: MutableMap<String, JsonObject>,
        retainedUnknownOutcome: Boolean,
        savedResultRecovery: GitHubReplayRecovery.State?,
        ciInvestigation: GitHubCiInvestigationRecovery,
        replayLimit: Int,
        consumedCallIds: Set<String>,
        deliveredResultDigests: Map<String, String>,
        completeReceipts: suspend (AgentToolExchange) -> Boolean
    ): ToolRoundCompletion? {
        var executableToolByName = initialTools
        var exposedDefinitions = initialDefinitions
        var toolCallCount = initialToolCallCount
        var finalResponseRequested = initialFinalResponseRequested
        var suppressedReadRounds = previousSuppressedReadRounds
        val questionNames = executableToolByName.values.filter { AgentToolCapability.USER_DECISION in it.definition.capabilities }.mapTo(hashSetOf()) { it.definition.name }
        // A choice and a mutation in the same model batch must not race. Replan
        // dependent actions after the answer has been returned to the model.
        val decisionCalls = calls.filter { it.name in questionNames }
        // Queued calls restore their original checkpoint. Fresh model calls can use
        // only the native GitHub operation actually exposed in this compact round.
        val roundTools = if (!restoringBatch && (limits.githubMode || limits.githubTask) && limits.contextTokens <= 4096) {
            val names = roundDefinitions.mapTo(hashSetOf()) { it.name }
            executableToolByName.filterKeys { it in names || dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it) == null }
        } else {
            executableToolByName
        }
        val toolsForBatch = when {
            decisionCalls.isNotEmpty() -> roundTools.filterKeys { it in questionNames }
            unproductiveDiscoveryRounds >= 2 && roundDefinitions.none { it.name == ProgressiveToolCatalog.NAME } -> roundTools - ProgressiveToolCatalog.NAME
            else -> roundTools
        }
        val batchAllowance = when {
            decisionCalls.any { it.callId in restoredCompleted } -> 0
            decisionCalls.isNotEmpty() -> minOf(1, remainingCalls)
            else -> remainingCalls
        }
        // Keep this batch decision-only even if a saved answer is available.
        val dependencyCalls = decisionCalls.ifEmpty { calls }
        val dependencyPlan = planIntelligentBatch(dependencyCalls, toolsForBatch).map { node ->
            if (node.call.callId in restoredCompleted) node.copy(dependencies = emptySet(), failureDependencies = emptySet()) else node
        }
        val reusableReads = if (limits.githubTask) calls.mapNotNull { call -> GitHubCiReadProgress.reused(call, exchanges)?.let { call.callId to it } }.toMap() else emptyMap()
        fun planAdmission(refreshContextId: String? = null) = planToolBatch(
            calls, batchAllowance, toolsForBatch.keys, toolCallsByName, blockedReadScopes,
            if (limits.githubTask) limits.maxToolCalls else MAX_SAME_TOOL_CALLS_PER_RUN,
            restoredRetained, restoredCompleted.keys, refreshContextId, reusableReads.keys, ::failureScope
        )
        val initialAdmission = planAdmission()
        val refreshContext = contextRefreshPrerequisite(dependencyPlan, initialAdmission, toolCallsByName, exchanges, batchAllowance)
        val admission = refreshContext?.let(::planAdmission) ?: initialAdmission
        val observedAdmission = repeatAdmissionHistory.capture(admission, calls, restoredRetained, restoredCompleted.keys)
        val plan = dependencyClosedPlan(observedAdmission, dependencyPlan, restoredRetained)
        val executableCalls = plan.executable
        val deferredCalls = plan.deferred
        val perToolSuppressedIds = plan.perToolSuppressedIds
        val hostSuppressedIds = plan.hostSuppressedIds
        plan.acceptedByName.forEach { (name, count) -> toolCallsByName[name] = (toolCallsByName[name] ?: 0) + count }
        repeatAdmissionHistory.commit(plan, restoredRetained, restoredCompleted.keys)
        if (plan.disabledNames.isNotEmpty()) {
            executableToolByName = executableToolByName.filterKeys { it !in plan.disabledNames }
            exposedDefinitions = exposedDefinitions.filterNot { it.name in plan.disabledNames && !(limits.githubMode && ProgressiveToolCatalog.isGitHubTool(it)) }
            AppLogRecorder.record("Agent", "TOOL_REPEAT_SUPPRESSED · tools=${plan.disabledNames.size} · calls=${plan.perToolSuppressedIds.size}", "W")
        }

        checkpoint?.enqueue(calls)
        executableCalls.filterNot { it.callId in restoredCompleted }.forEach { emit(AgentRunEvent.ToolStarted(it)) }
        val invalidatedAuthority = AtomicReference(restoredCompleted.values.firstOrNull { ToolFailureMetadata.authorityCode(it) != null })
        val deliveredResults = mutableMapOf<String, AgentToolResult>()
        val executedResults = mutableListOf<AgentToolResult>()
        // Keep one graph for the entire admitted round: chunk boundaries must not
        // erase dependencies or allow a write after an earlier failed read.
        executedResults += executeToolBatch(
            executableCalls,
            toolsForBatch,
            unknownOutcomes,
            invalidatedAuthority,
            completed = { call, result ->
                deliveredResults[call.callId] = if (call.callId in restoredCompleted) result else completeTool(call, result, invalidatedAuthority)
                repeatAdmissionHistory.observe(call, deliveredResults.getValue(call.callId))
            },
            progress = { emit(AgentRunEvent.BatchProgress(it)) },
            dependencyPlan = dependencyPlan,
            restoredCompleted = restoredCompleted
        ).map { deliveredResults[it.callId] ?: it }
        invalidatedAuthority.get()?.let { failure ->
            val deferred = deferredCalls.map { call -> completeTool(call, ToolFailureMetadata.authorityBlocked(call.callId, call.name, failure), invalidatedAuthority) }
            val retained = (executedResults + deferred).associateBy { it.callId }
            exchanges += AgentToolExchange(calls, calls.map { retained.getValue(it.callId) })
            val unknown = retainedUnknownOutcome || unknownOutcomes.isNotEmpty() || retained.values.any { it.errorCode in ToolResultEnvelope.unknownOutcomeCodes }
            emit(AgentRunEvent.Provider(ToolFailureMetadata.authorityFailure(failure, unknown)))
            return null
        }
        // Verify the native batch before schema/replay planning or wrap-up text
        // can remove source fields. Deferred calls still pass through normal
        // delivery first; pending writes block the receipt-only completion guard.
        if (deferredCalls.isEmpty() && completeReceipts(AgentToolExchange(calls, executedResults))) return null
        if (!finalResponseRequested && !retriedWithoutTools) {
            val update = admitDiscoveredTools(dynamicTools(), toolByName, executableToolByName, exposedDefinitions, initialSchemaTokens, consecutiveToolFailures, toolCallsByName, replayTokens, selectDefinitions) ?: return null
            executableToolByName = update.tools
            exposedDefinitions = update.definitions
        }
        val dispatched = executableCalls.zip(executedResults).count { (call, result) ->
            result.dispatched && call.callId !in restoredDispatched
        }
        toolCallCount = (toolCallCount.toLong() + dispatched).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (limits.githubTask) toolCallCount = maxOf(toolCallCount, executedResults.mapNotNull { it.toolCallBudgetUsed }.maxOrNull() ?: 0)

        val newlyBlockedTools = updateFailureCircuits(executableCalls, executedResults, consecutiveToolFailures, blockedReadScopes, ::failureScope)
        if (newlyBlockedTools.isNotEmpty()) {
            executableToolByName = executableToolByName.filterKeys { it !in newlyBlockedTools }
            exposedDefinitions = exposedDefinitions.filterNot { it.name in newlyBlockedTools && !(limits.githubMode && ProgressiveToolCatalog.isGitHubTool(it)) }
            newlyBlockedTools.forEach { toolName ->
                AppLogRecorder.record("Agent", "TOOL_FAILURE_CIRCUIT_OPEN · tool=$toolName · failures=${consecutiveToolFailures[toolName]}", "W")
            }
        }
        val authorizationStops = executableCalls.zip(executedResults).filter { (_, result) -> result.isError && ToolResultEnvelope.isAuthorizationRejection(result.errorCode) }.map { it.first.name }.toSet()
        if (authorizationStops.isNotEmpty()) {
            executableToolByName = executableToolByName.filterKeys { it !in authorizationStops }
            exposedDefinitions = exposedDefinitions.filterNot { it.name in authorizationStops }
            authorizationStops.forEach { name -> AppLogRecorder.record("Agent", "TOOL_AUTHORIZATION_STOP · tool=$name · action=review_authorization · providerCircuitChanged=false") }
        }

        val pausedGitHubNames = exposedDefinitions.filter { limits.githubMode && ProgressiveToolCatalog.isGitHubTool(it) && it.name !in executableToolByName }.mapTo(hashSetOf()) { it.name }
        val admittedIds = executableCalls.mapTo(hashSetOf()) { it.callId }
        val availableCatalog = roundDefinitions.filter { it.modelVisible && it.name in executableToolByName }.associateBy { it.name }
        val catalogHash = if (plan.unavailableIds.isEmpty()) {
            null
        } else {
            dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(
                availableCatalog.toSortedMap().values.joinToString("\n") { it.name + ":" + it.inputSchema.toString() }
            )
        }
        val deferredResults = deferredToolResults(deferredCalls, hostSuppressedIds, perToolSuppressedIds, plan.unavailableIds, availableCatalog, exchanges, pausedGitHubNames, catalogHash, reusableReads.filterKeys { it in plan.reusedReadIds }).map { result ->
            fun admissionReason(id: String) = when (id) {
                in plan.dependencyDeferredIds -> ToolDependencyDiagnostics.Admission.PREREQUISITE_DEFERRED
                in plan.unavailableIds -> if (decisionCalls.isEmpty()) ToolDependencyDiagnostics.Admission.TOOL_UNAVAILABLE else ToolDependencyDiagnostics.Admission.DECISION_REQUIRED
                in hostSuppressedIds -> ToolDependencyDiagnostics.Admission.REPEATED_FAILURE
                in perToolSuppressedIds -> ToolDependencyDiagnostics.Admission.REPEAT_LIMIT
                else -> ToolDependencyDiagnostics.Admission.CALL_ALLOWANCE
            }
            if (result.callId in plan.dependencyDeferredIds) {
                val dependencies = dependencyPlan.single { it.call.callId == result.callId }.failureDependencies
                val blocked = dependencies - admittedIds
                ToolDependencyDiagnostics.rejection(
                    result.callId,
                    ToolDependencyDiagnostics.State.DEFERRED,
                    dependencies,
                    blocked,
                    "A prerequisite was not admitted. Resolve its recorded admission reason before continuing dependent calls.",
                    blocked.mapTo(linkedSetOf(), ::admissionReason)
                )
            } else if (result.isError && !result.dispatched) {
                ToolRepeatAdmissionEvidence.attachTo(
                    result.copy(
                        executionReceipt = JsonObject(
                            result.executionReceipt.orEmpty() + buildJsonObject {
                                put("failed_stage", "tool_precheck")
                                put("dependency_admission_reasons", JsonArray(listOf(JsonPrimitive(admissionReason(result.callId).name))))
                            }
                        )
                    ),
                    plan.repeatLimitSnapshots[result.callId]
                )
            } else {
                result
            }
        }
        deferredCalls.zip(deferredResults).forEach { (call, result) -> checkpoint?.complete(call, result) }
        // Retain successful saved-page admission before replay compaction can evict it.
        savedResultRecovery?.record(calls, executedResults + deferredResults)
        val savedReadAvailable = savedResultRecovery != null &&
            GitHubReplayRecovery.nextSavedRead(
                exchanges + AgentToolExchange(calls, executedResults + deferredResults),
                exposedDefinitions.filter { it.name in executableToolByName },
                minOf(limits.maxReplayResultTokens, replayLimit / 2),
                savedResultRecovery,
                replayLimit,
                limits.maxToolOutputBytes
            ) != null
        val canReadOtherSources = savedReadAvailable ||
            (plan.unavailableIds.isNotEmpty() && executableToolByName.isNotEmpty() && deferredResults.any { it.callId in plan.unavailableIds && it.executionReceipt?.get("same_catalog_rejection") != JsonPrimitive(true) }) ||
            hostSuppressedIds.isNotEmpty() ||
            plan.reusedReadIds.isNotEmpty() ||
            deferredCalls.any { it.callId in perToolSuppressedIds && it.name in executableToolByName }
        // Give a scoped read one round to choose another source, then stop
        // paying for provider rounds that only repeat already-suppressed work.
        suppressedReadRounds = if (executableCalls.isEmpty() && canReadOtherSources) suppressedReadRounds + 1 else 0
        ciInvestigation.observe(AgentToolExchange(calls, executedResults + deferredResults))
        val completedBatch = finishBatch(
            calls, executedResults + deferredResults, exchanges, executableCalls.isEmpty(),
            canReadOtherSources, suppressedReadRounds >= 2 && !savedReadAvailable,
            roundLimitReached, toolCallCount, executionToolCallLimit,
            unproductiveDiscoveryRounds, finalResponseRequested, executableCalls.mapTo(hashSetOf()) { it.callId },
            executableToolByName.values.any { it.definition.modelVisible && ProgressiveToolCatalog.isGitHubTool(it.definition) },
            replayLimit, consumedCallIds, deliveredResultDigests, savedResultRecovery
        )
        finalResponseRequested = completedBatch.finalResponseRequested
        if (finalResponseRequested) {
            exposedDefinitions = emptyList()
            executableToolByName = emptyMap()
        }
        return ToolRoundCompletion(executableToolByName, exposedDefinitions, toolCallCount, executedResults.any { it.dispatched }, suppressedReadRounds, completedBatch)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.completeTool(call: ProviderEvent.ToolCall, result: AgentToolResult, invalidatedAuthority: AtomicReference<AgentToolResult?>): AgentToolResult {
        checkpoint?.complete(call, result)
        healthCatalog?.recordObservation(call.name, result, arguments = call.arguments)
        val observed = try {
            onToolResult?.invoke(call, result)
            onToolResultCommitted?.invoke(call, result)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The execution checkpoint already contains the actual receipt.
            // Failed access to a secondary evidence store cannot erase that outcome.
            ToolFailureMetadata.withAuthorityFailure(result, error, "delivery") ?: throw error
        }
        if (ToolFailureMetadata.authorityCode(observed) != null) invalidatedAuthority.compareAndSet(null, observed)
        emit(AgentRunEvent.ToolFinished(call, observed))
        return observed
    }

    private fun failureScope(call: ProviderEvent.ToolCall): String {
        if (call.name == "read_url" || call.name.endsWith("_read_url")) {
            val url = (call.arguments["url"] as? JsonPrimitive)?.content.orEmpty()
            val host = runCatching { URI(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
            return "${call.name}|host=${host ?: "invalid-url"}"
        }
        return toolRepeatKey(call).takeIf { "|operation=" in it } ?: call.name
    }

    private data class SearchRoundContext(val names: Set<String>, val successful: Boolean)

    private fun searchRoundContext(initial: Map<String, AgentTool>, active: Map<String, AgentTool>, exchanges: List<AgentToolExchange>): SearchRoundContext {
        val names = (initial.values + active.values).filter { AgentToolCapability.WEB_SEARCH in it.definition.capabilities }.mapTo(HashSet()) { it.definition.name }
        val successful = exchanges.any { exchange ->
            exchange.results.any { result -> !result.isError && result.dispatched && exchange.calls.any { it.callId == result.callId && it.name in names } }
        }
        return SearchRoundContext(names, successful)
    }

    private fun deferredToolResults(calls: List<ProviderEvent.ToolCall>, hostSuppressedIds: Set<String>, perToolSuppressedIds: Set<String>, unavailableIds: Set<String>, availableCatalog: Map<String, AgentToolDefinition>, exchanges: List<AgentToolExchange>, pausedGitHubNames: Set<String> = emptySet(), catalogHash: String? = null, reusableReads: Map<String, AgentToolResult> = emptyMap()): List<AgentToolResult> = calls.map { call ->
        reusableReads[call.callId]?.let { return@map it }
        if (call.callId in unavailableIds) {
            if (call.name in pausedGitHubNames) {
                return@map ToolResultEnvelope.error(
                    call.callId,
                    "TOOL_EXECUTION_PAUSED",
                    "This authorized GitHub tool remains visible, but execution is paused for this response after repeated failures or a repeat limit. Nothing was dispatched. Use the retained error's correction or another authorized GitHub operation; resume the paused operation after its blocker is resolved."
                )
            }
            val availableNames = availableCatalog.keys
            val previouslyUnavailable = catalogHash != null &&
                exchanges.any { exchange ->
                    exchange.calls.any { prior ->
                        prior.name == call.name &&
                            exchange.results.any { result ->
                                result.callId == prior.callId &&
                                    result.errorCode == "TOOL_NOT_AVAILABLE" &&
                                    !result.dispatched &&
                                    result.executionReceipt?.get("catalog_revision_hash") == JsonPrimitive(catalogHash)
                            }
                    }
                }
            val suggested = when {
                call.name in availableNames -> null
                call.name.substringAfterLast("__") in setOf("read_url", "firecrawl_scrape", "fetch_url") && "url" in call.arguments && "read_url" in availableNames -> "read_url"
                call.name.substringAfterLast("__") in setOf("search", "search_web", "web_search", "firecrawl_search") && "query" in call.arguments && "web_search" in availableNames -> "web_search"
                ProgressiveToolCatalog.NAME in availableNames -> ProgressiveToolCatalog.NAME
                else -> null
            }
            val availableList = availableNames.sorted().take(32).joinToString(", ") { name ->
                val required = availableCatalog.getValue(name).inputSchema["required"] as? JsonArray
                val fields = required.orEmpty().take(6).mapNotNull { (it as? JsonPrimitive)?.content?.take(60) }
                if (fields.isEmpty()) name else "$name (requires ${fields.joinToString(", ")})"
            }.ifEmpty { "none" }
            val recovery = when (suggested) {
                ProgressiveToolCatalog.NAME -> {
                    val query = when (call.arguments.keys) {
                        setOf("url") -> "url"
                        setOf("evidence_id") -> "evidence"
                        else -> null
                    }
                    val example = query?.let { " Start with " + buildJsonObject { put("query", it) } + "." }.orEmpty()
                    " Use $suggested to look up this capability.$example Use the exact name and schema it returns."
                }
                null -> " If none of these tools provides the capability, use the evidence already collected and explain what is missing."
                else -> " Use the advertised $suggested schema for this request."
            }
            return@map AgentToolResult(
                call.callId,
                ToolResultContent.Text(if (previouslyUnavailable) "The authorized tool catalog is unchanged. The previous lookup for this tool found no executable route. Continue independent work using an advertised tool." else "The requested tool '${call.name}' is not available in this round. Nothing was dispatched. Use an exact advertised tool name and its schema. Available tools: $availableList.$recovery"),
                isError = true,
                dispatched = false,
                errorCode = "TOOL_NOT_AVAILABLE",
                executionReceipt = if (call.name in availableNames) {
                    null
                } else {
                    buildJsonObject {
                        put("schema", "gptmobile.round-tool-catalog.v1")
                        put("call_id", call.callId)
                        put("reason_code", "UNKNOWN_TOOL")
                        put("phase", "PREFLIGHT_REJECTED")
                        put("dispatched", false)
                        put("retryable", false)
                        put("available_tool_count", availableNames.size)
                        put("missing_tool_hash", dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore.digest(call.name))
                        put("same_catalog_rejection", previouslyUnavailable)
                        catalogHash?.let { put("catalog_revision_hash", it) }
                        put("discovery_available", ProgressiveToolCatalog.NAME in availableNames)
                        suggested?.let { put("suggested_tool", it) }
                    }
                }
            )
        }
        val message = if (call.callId in hostSuppressedIds) {
            if (dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(call.name) != null) {
                "This exact GitHub request failed repeatedly and is paused for this response. Other GitHub operations remain available. Use the returned correction or retained evidence; do not repeat the failed arguments."
            } else {
                "This website failed repeatedly and is paused for this response. read_url remains available for other hosts; choose another source."
            }
        } else if (call.callId in perToolSuppressedIds) {
            "This operation reached the per-response repeat limit. Use results already collected; do not repeat these arguments. " +
                if (dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(call.name) in setOf("github_read", "github_search")) {
                    "For more GitHub data, use the exact continuation_call returned by the last result or request a different unread range at the same commit_sha. Never repeat a mutation to recover output."
                } else {
                    "Other authorized sources may still be read within the remaining budget."
                }
        } else {
            FINAL_RESPONSE_INSTRUCTION
        }
        if (call.callId in perToolSuppressedIds) GitHubReplayRecovery.repeated(call, exchanges, message)?.let { return@map it }
        AgentToolResult(call.callId, ToolResultContent.Text(message), isError = true, dispatched = false, errorCode = "NOT_DISPATCHED")
    }

    private data class BatchRejectionRecovery(val definitions: List<AgentToolDefinition>, val repair: Boolean)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverRejectedBatch(
        session: AgentProviderSession,
        round: RoundResult,
        exposedDefinitions: List<AgentToolDefinition>,
        observedToolCalls: Int,
        observedLimit: Int,
        rounds: Int,
        batchRepairAttempted: Boolean,
        stormFinalizationAttempted: Boolean,
        finalResponseRequested: Boolean
    ): BatchRejectionRecovery? {
        AppLogRecorder.record("Agent", "TOOL_BATCH_OVERSIZE · rejectedBatchObserved=${round.observedCalls} · permittedPerRound=${limits.maxToolCallsPerRound} · cumulativeObserved=$observedToolCalls · rejection=${round.batchRejection ?: "UNKNOWN_THRESHOLD"} · dispatched=0", "W")
        if (!batchRepairAttempted &&
            !round.textEmitted &&
            !session.handlesToolsInternally &&
            !finalResponseRequested &&
            exposedDefinitions.isNotEmpty() &&
            observedToolCalls < observedLimit &&
            rounds < limits.maxRounds &&
            round.batchRejection?.contains("threshold=STREAM_TOOL_SLOTS") == true
        ) {
            val batchSize = 1
            val repaired = (if (limits.githubMode) exposedDefinitions else exposedDefinitions.take(4)).map { definition ->
                definition.copy(description = definition.description + " Batch repair: the previous response exceeded the tool-call limit and NONE of its calls ran. Make at most $batchSize total calls in this response, then wait for results before requesting more.")
            }
            emit(AgentRunEvent.Notice("The provider requested too many tools at once. Retrying once with a smaller batch; completed evidence is retained.", persistent = false))
            return BatchRejectionRecovery(repaired, true)
        }
        emit(AgentRunEvent.Notice("An oversized or invalid tool batch was stopped before dispatch. Retry with at most ${limits.maxToolCallsPerRound} calls per batch; completed evidence remains available.", persistent = true))
        if (stormFinalizationAttempted || session.handlesToolsInternally || !limits.allowToolStormFinalization) {
            emit(failed("TOOL_BATCH_OVERSIZE", "TOOL_BATCH_OVERSIZE: partial answer and completed evidence retained. The provider did not honor tool-free finalization."))
            return null
        }
        return BatchRejectionRecovery(emptyList(), false)
    }

    private fun routeSearchCalls(calls: List<ProviderEvent.ToolCall>, executableToolByName: Map<String, AgentTool>): List<ProviderEvent.ToolCall> = calls.map { call ->
        // The aggregate search surface can replace individual engine
        // schemas after delegation. Reuse only the current authorized
        // search tool, never discover or enable an unassigned connection.
        if (call.name !in executableToolByName &&
            "web_search" in executableToolByName &&
            Regex("^mcp__[A-Za-z0-9_-]+__web_search$").matches(call.name) &&
            (call.arguments["query"] as? JsonPrimitive)?.isString == true
        ) {
            AppLogRecorder.record("Agent", "SEARCH_ROUTED_TO_AGGREGATE · requested=${call.name} · assigned=web_search")
            call.copy(name = "web_search", arguments = dev.chungjungsoo.gptmobile.data.agent.tool.legacySearchArguments(call.arguments))
        } else if (call.name == "web_search" && call.name !in executableToolByName) {
            val candidates = executableToolByName.values.filter {
                AgentToolCapability.WEB_SEARCH in it.definition.capabilities &&
                    Regex("^mcp__[A-Za-z0-9_-]+__web_search$").matches(it.definition.name) &&
                    ToolArgumentValidator.errors(call.arguments, it.definition.inputSchema).isEmpty()
            }
            candidates.singleOrNull()?.let { call.copy(name = it.definition.name) } ?: call
        } else {
            call
        }
    }

    private data class ToolCatalogUpdate(val tools: Map<String, AgentTool>, val definitions: List<AgentToolDefinition>)

    private fun recordCompletedGitHubOperations(exchange: AgentToolExchange, completed: MutableSet<String>, restoredJournal: Boolean = false) {
        if (!(limits.githubMode || limits.githubTask) || limits.contextTokens > 4096) return
        val results = exchange.results.associateBy { it.callId }
        for (call in exchange.calls) {
            val result = results[call.callId] ?: continue
            if (dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(call.name) != null && githubSchemaPhaseComplete(call.name, result, call.arguments, restoredJournal)) {
                completed.remove(call.name)
                completed.add(call.name)
            }
        }
    }

    private fun selectedForRound(definitions: List<AgentToolDefinition>, selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>): List<AgentToolDefinition> {
        val selected = selectDefinitions(definitions)
        if (!limits.githubMode) return selected
        val authorized = definitions.filter { it.modelVisible }.associateBy { it.name }
        return selected.mapNotNull { choice -> authorized[choice.name]?.let { GitHubActionSchema.authorized(it, choice) } }.distinctBy { it.name }
    }

    /** Dynamic discovery has an independent coroutine to stay below ART's method-size ceiling. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.admitDiscoveredTools(
        discovered: List<AgentTool>,
        toolByName: Map<String, AgentTool>,
        executableToolByName: Map<String, AgentTool>,
        exposedDefinitions: List<AgentToolDefinition>,
        initialSchemaTokens: Long,
        consecutiveToolFailures: Map<String, Int>,
        toolCallsByName: Map<String, Int>,
        replayTokens: Long,
        selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>
    ): ToolCatalogUpdate? {
        val additions = discovered.filter { it.definition.name !in toolByName && it.definition.name !in executableToolByName && (consecutiveToolFailures[it.definition.name] ?: 0) < 2 && (toolCallsByName[it.definition.name] ?: 0) < (if (limits.githubTask) limits.maxToolCalls else MAX_SAME_TOOL_CALLS_PER_RUN) }
        if (additions.groupBy { it.definition.name }.any { it.value.size > 1 }) {
            emit(failed("AMBIGUOUS_TOOL_ALIAS", "Ambiguous discovered tool aliases; no discovered action dispatched."))
            return null
        }
        val selectedSchemas = selectedForRound((exposedDefinitions + additions.map { it.definition }).distinctBy { it.name }, selectDefinitions)
        if (limits.githubMode) {
            val admittedSchemas = try {
                fitProviderToolSchemas(selectedSchemas, limits, initialSchemaTokens, replayTokens)
            } catch (capacity: GitHubToolContextBudgetException) {
                emit(AgentRunEvent.Provider(capacity.providerFailure()))
                return null
            }
            val admittedNames = admittedSchemas.mapTo(hashSetOf()) { it.name }
            val admitted = additions.filter { it.definition.name in admittedNames }
            healthCatalog?.bindExecutionContracts(admitted.map { it.definition })
            return ToolCatalogUpdate(executableToolByName + admitted.associateBy { it.definition.name }, (exposedDefinitions + admitted.map { it.definition }).distinctBy { it.name })
        }
        val selectedNames = selectedSchemas.mapTo(HashSet()) { it.name }
        var schemaTokens = selectedSchemas.filter { it.name in executableToolByName }.sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() }
        val contextWithoutInitialSchemas = (limits.initialContextTokens.toLong() - initialSchemaTokens).coerceAtLeast(0)
        val schemaAllowance = minOf(ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE.toLong(), (limits.contextTokens.toLong() - contextWithoutInitialSchemas - replayTokens - limits.finalResponseReserveTokens).coerceAtLeast(0))
        val omitted = linkedMapOf<String, String>()
        val admitted = additions.filter { tool ->
            val cost = ProgressiveToolCatalog.schemaTokens(tool.definition)
            val fits = tool.definition.name in selectedNames && schemaTokens + cost <= schemaAllowance
            if (fits) schemaTokens += cost
            if (!fits) omitted[tool.definition.name] = if (tool.definition.name !in selectedNames) "CATALOG_NOT_SELECTED" else "REMAINING_CONTEXT_BUDGET"
            fits
        }
        ToolCatalogDiagnostics.recordOmissions("dynamic_admission", omitted, schemaAllowance, inventoryNames = additions.map { it.definition.name })
        if (admitted.size < additions.size) emit(AgentRunEvent.Notice("Some discovered tool schemas exceed the remaining context allowance. Existing conversation context was preserved.", persistent = false))
        healthCatalog?.bindExecutionContracts(admitted.map { it.definition })
        return ToolCatalogUpdate(executableToolByName + admitted.associateBy { it.definition.name }, (exposedDefinitions + admitted.map { it.definition }).distinctBy { it.name })
    }

    private data class BatchCompletion(val replayTokens: Long, val discoveryRounds: Int, val finalResponseRequested: Boolean, val hasSuccessfulResult: Boolean)

    /** Separate tool-result publication/finalization from the dispatch coroutine for ART compilation. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.finishBatch(
        calls: List<ProviderEvent.ToolCall>,
        producedResults: List<AgentToolResult>,
        exchanges: MutableList<AgentToolExchange>,
        noExecutableCalls: Boolean,
        canReadOtherSources: Boolean,
        repeatedReadSuppression: Boolean,
        roundLimitReached: Boolean,
        toolCallCount: Int,
        executionToolCallLimit: Int,
        previousDiscoveryRounds: Int,
        wasFinalResponseRequested: Boolean,
        publishedCallIds: Set<String>,
        hasActionableGitHubTools: Boolean,
        replayLimit: Int,
        consumedCallIds: Set<String>,
        deliveredResultDigests: Map<String, String>,
        savedResultRecovery: GitHubReplayRecovery.State?
    ): BatchCompletion {
        var finalResponseRequested = wasFinalResponseRequested
        val resultByCallId = producedResults.associateBy { it.callId }
        val allResults = calls.map { call ->
            resultByCallId[call.callId] ?: AgentToolResult(
                callId = call.callId,
                content = ToolResultContent.Text("Tool result was unavailable; do not retry this call in the same response."),
                isError = true
            )
        }.toMutableList()
        // Discovery that leaves the advertised set unchanged is not research progress.
        // After two such rounds, stop discovery. GitHub execution can continue with
        // its attached operations; unrelated conversations can finish from evidence.
        val noNewTools = calls.isNotEmpty() &&
            calls.all { it.name == ProgressiveToolCatalog.NAME } &&
            allResults.all { result ->
                result.isError || (((result.content as? ToolResultContent.Json)?.value as? kotlinx.serialization.json.JsonObject)?.get("catalog_unchanged")?.toString() == "true")
            }
        val unproductiveDiscoveryRounds = if (noNewTools) previousDiscoveryRounds + 1 else 0
        val repeatedDiscoveryStalled = unproductiveDiscoveryRounds >= 2
        val suppressDiscovery = repeatedDiscoveryStalled && (limits.githubMode || limits.githubTask) && hasActionableGitHubTools
        val discoveryRequiresFinalization = repeatedDiscoveryStalled && !suppressDiscovery
        val combinedWorkBudgetExhausted = allResults.any { it.combinedWorkBudgetExhausted }
        val outputBudgetExhausted = allResults.any { it.outputBudgetExhausted }
        val toolCallBudgetExhausted = allResults.any { it.toolCallBudgetExhausted }
        val sharedCallBudgetResult = allResults
            .filter { it.toolCallBudgetExhausted }
            .maxByOrNull { it.toolCallBudgetUsed ?: -1 }
        val sharedOutputBudgetResult = allResults
            .filter { it.outputBudgetExhausted }
            .maxByOrNull { it.toolResultBudgetUsedBytes ?: -1 }
        val projectedExchanges = exchanges + AgentToolExchange(calls, allResults)
        val replayTokens = prepareReplay(
            exchanges = projectedExchanges,
            maxReplayTokens = replayLimit,
            maxResultTokens = limits.maxReplayResultTokens,
            consumedCallIds = consumedCallIds,
            deliveredResultDigests = deliveredResultDigests
        ).compactedTokens.toLong()
        val estimatedPrompt = limits.initialContextTokens.toLong().coerceAtLeast(0) + replayTokens
        val contextNearLimit = limits.contextTokens != Int.MAX_VALUE &&
            estimatedPrompt + limits.finalResponseReserveTokens +
            RequestAdmission.estimateSafety(estimatedPrompt) > limits.contextTokens
        val repeatLimited = allResults.any { toolResultText(it).contains("reached the per-response repeat limit", ignoreCase = true) }
        val unavailableCallsOnly = noExecutableCalls && calls.isNotEmpty() && !canReadOtherSources
        val argumentRepairStalled = allResults.isNotEmpty() &&
            allResults.all {
                it.isError && !it.dispatched && it.executionReceipt?.get("argument_repair_exhausted") == JsonPrimitive(true)
            }
        val repeatedReadsRequireFinalization = repeatedReadSuppression &&
            !((limits.githubMode || limits.githubTask) && hasActionableGitHubTools && canReadOtherSources)
        val mustFinalize = unavailableCallsOnly ||
            argumentRepairStalled ||
            allResults.any { it.errorCode == "GITHUB_USER_STOP" } ||
            repeatedReadsRequireFinalization ||
            discoveryRequiresFinalization ||
            roundLimitReached ||
            contextNearLimit ||
            combinedWorkBudgetExhausted ||
            outputBudgetExhausted ||
            toolCallBudgetExhausted ||
            (executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit)
        if (allResults.any { it.errorCode == "GITHUB_USER_STOP" }) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice("Stopped at your decision. Retained work is available for review.", persistent = true))
        } else if (unavailableCallsOnly) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice(if (repeatLimited) "Repeated calls were stopped. Finishing with the findings already collected." else "Requested tools are unavailable for this response. Finishing with the findings already collected.", persistent = false))
        } else if (argumentRepairStalled) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice("Repeated argument corrections made no progress. Finishing with retained evidence and the unresolved validation constraints.", persistent = false))
        } else if (repeatedReadsRequireFinalization) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice("Repeated reads produced no new work. Finishing with the results already collected.", persistent = false))
        } else if (discoveryRequiresFinalization) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice("No new tools became available after repeated discovery. Summarizing the evidence already collected.", persistent = false))
        } else if (roundLimitReached) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice(ROUND_LIMIT_FINAL_RESPONSE_NOTICE, persistent = false))
        } else if (combinedWorkBudgetExhausted) {
            finalResponseRequested = true
            val denial = allResults.firstOrNull { it.combinedWorkBudgetExhausted }?.let(::toolResultText).orEmpty()
            emit(AgentRunEvent.Notice(denial.ifBlank { "Combined work allowance exhausted. Finishing with retained evidence." }, persistent = true))
        } else if (toolCallBudgetExhausted && outputBudgetExhausted) {
            finalResponseRequested = true
            val callUsed = sharedCallBudgetResult?.toolCallBudgetUsed ?: toolCallCount
            val callLimit = sharedCallBudgetResult?.toolCallBudgetLimit ?: executionToolCallLimit
            val configured = sharedCallBudgetResult?.toolCallBudgetConfigured ?: limits.maxToolCalls
            val reserve = sharedCallBudgetResult?.toolCallBudgetReserved ?: limits.finalResponseToolCallReserve.coerceAtLeast(0)
            val usedBytes = sharedOutputBudgetResult?.toolResultBudgetUsedBytes ?: limits.maxToolOutputBytes
            val limitBytes = sharedOutputBudgetResult?.toolResultBudgetLimitBytes ?: limits.maxToolOutputBytes
            emit(
                AgentRunEvent.Notice(
                    "Shared tool budgets reached: $callUsed/$callLimit executable calls used " +
                        "($configured configured, $reserve reserved) and $usedBytes/$limitBytes result bytes used. " +
                        "Finishing with completed results; successful delegated research remains usable.",
                    persistent = true
                )
            )
        } else if (toolCallBudgetExhausted) {
            finalResponseRequested = true
            val used = sharedCallBudgetResult?.toolCallBudgetUsed ?: toolCallCount
            val limit = sharedCallBudgetResult?.toolCallBudgetLimit ?: executionToolCallLimit
            val configured = sharedCallBudgetResult?.toolCallBudgetConfigured ?: limits.maxToolCalls
            val reserve = sharedCallBudgetResult?.toolCallBudgetReserved ?: limits.finalResponseToolCallReserve.coerceAtLeast(0)
            emit(
                AgentRunEvent.Notice(
                    "Shared tool-call limit reached: $used/$limit executable calls used " +
                        "($configured configured, $reserve reserved). Finishing with completed results. " +
                        "Increase Maximum Tool Calls on the active AI profile if more research is required.",
                    persistent = true
                )
            )
        } else if (outputBudgetExhausted) {
            finalResponseRequested = true
            val usedBytes = sharedOutputBudgetResult?.toolResultBudgetUsedBytes ?: limits.maxToolOutputBytes
            val limitBytes = sharedOutputBudgetResult?.toolResultBudgetLimitBytes ?: limits.maxToolOutputBytes
            emit(
                AgentRunEvent.Notice(
                    "Tool-result byte budget reached: $usedBytes/$limitBytes bytes used. " +
                        "Finishing with completed results; successful delegated research remains usable.",
                    persistent = true
                )
            )
        } else if (contextNearLimit) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice("Context limit approaching. Finishing with the results already available.", persistent = false))
        }
        val remainingAllowance = ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount)
        val shouldInjectWrapUp = ToolBudgetPolicy.shouldInjectWrapUpPrompt(executionToolCallLimit, limits, toolCallCount)

        if (mustFinalize && allResults.isNotEmpty()) {
            allResults[allResults.lastIndex] = when {
                unavailableCallsOnly && repeatLimited -> appendInstruction(allResults.last(), "Repeated identical calls reached their safety limit. This does not establish that GitHub read or write tools are disabled. Report completed work and remaining actions accurately; do not claim edits or commits without successful tool receipts. Finish this response from retained evidence.")
                unavailableCallsOnly -> appendInstruction(allResults.last(), "These tools are disabled or unavailable. Use successful findings already collected to answer now. Do not request more tools in this response.")
                argumentRepairStalled -> appendInstruction(allResults.last(), "The same argument constraints remained invalid after the bounded correction attempt. Finish with verified results and identify the unresolved fields from validation_errors. No operation was dispatched for these rejections; do not claim a provider outage or disabled GitHub permissions.")
                repeatedReadsRequireFinalization -> appendInstruction(allResults.last(), "Repeated reads were skipped without acquiring new evidence. Summarize the results already collected and identify remaining gaps. Do not request more tools in this response.")
                discoveryRequiresFinalization -> appendInstruction(allResults.last(), "No more tools became available. Do not call discovery again; summarize completed findings and clearly identify any gaps.")
                roundLimitReached -> appendInstruction(allResults.last(), ROUND_LIMIT_FINAL_RESPONSE_INSTRUCTION)
                contextNearLimit -> appendInstruction(allResults.last(), "The current task cannot fit its irreducible request and tool receipt in the resolved context allowance after compaction. Report the exact context blocker and retained work. Do not ask for a manual continuation that would repeat the same oversized request.")
                outputBudgetExhausted && toolCallBudgetExhausted ->
                    appendInstruction(allResults.last(), COMBINED_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                outputBudgetExhausted -> appendInstruction(allResults.last(), OUTPUT_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                else -> appendFinalResponseInstruction(allResults.last())
            }
        } else if (repeatedReadSuppression && allResults.isNotEmpty()) {
            allResults[allResults.lastIndex] = appendInstruction(allResults.last(), "This exact read produced no new evidence and remains suppressed. Follow its issued remote continuation, or use a different required action or unread range. Other authorized tools remain available within the current task budget. Workflow definitions do not establish executed checks or repository health.")
            emit(AgentRunEvent.Notice("Repeated reads were skipped. Continuing with remaining checks and unread pages.", persistent = false))
        } else if (suppressDiscovery && allResults.isNotEmpty()) {
            allResults[allResults.lastIndex] = appendInstruction(allResults.last(), "Repeated discovery returned no new schemas. Use an attached GitHub operation next. If only discovery fits, select one exact tool and action. Do not repeat the unchanged request.")
            emit(AgentRunEvent.Notice("Repeated discovery is paused when a GitHub operation fits. Continuing with the available tools.", persistent = false))
        } else if (shouldInjectWrapUp && allResults.isNotEmpty()) {
            val wrapUpPrompt = ToolBudgetPolicy.buildWrapUpPrompt(remainingAllowance, combined = limits.combined != null)
            allResults[allResults.lastIndex] = appendInstruction(allResults.last(), wrapUpPrompt)
        }

        calls.zip(allResults).forEach { (call, result) ->
            if (call.callId !in publishedCallIds) {
                onToolResult?.invoke(call, result)
                // Deferred/precheck results were committed before finishBatch.
                onToolResultCommitted?.invoke(call, result)
                emit(AgentRunEvent.ToolFinished(call, result))
            }
        }
        // Retain bounded observations on ingress, including trace/retained payloads.
        val retentionBudget = limits.maxRetainedEvidenceTokens.coerceIn(1024, if (limits.githubTask) 262_144 else 32_000)
        val observations = exchanges + AgentToolExchange(calls, allResults.map { it.copy(traceContent = null, retainedContent = null) })
        val retained = prepareReplay(
            observations,
            retentionBudget,
            // Preserve complete evidence while it fits the retained budget.
            // Provider replay still enforces its independent, smaller allowance.
            limits.maxReplayResultTokens.coerceIn(64, 2048),
            requiredSavedPageDigests = savedResultRecovery?.pendingSavedPageDeliveries(observations, deliveredResultDigests).orEmpty()
        ).exchanges
        exchanges.clear()
        exchanges.addAll(retained)
        return BatchCompletion(replayTokens, unproductiveDiscoveryRounds, finalResponseRequested, allResults.any { !it.isError })
    }

    private data class RoundResult(
        val calls: List<ProviderEvent.ToolCall> = emptyList(),
        val completed: Boolean = false,
        val failure: String? = null,
        val textEmitted: Boolean = false,
        val toolDefinitionsRejected: Boolean = false,
        val batchRejected: Boolean = false,
        val observedCalls: Int = 0,
        val progressDeadline: ProgressDeadlineOutcome? = null,
        val batchRejection: String? = null,
        val errorCode: String? = null,
        val failureTransportComplete: Boolean = false,
        val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null,
        val taskOutcome: String? = null,
        val failureDetails: JsonObject? = null,
        val invocationId: String? = null,
        val applicationReportEmitted: Boolean = false,
        val evidenceCorrectionEligible: Boolean = false,
        val savedPageDelivery: Boolean = false,
        val savedPageReading: JsonObject? = null,
        val unacknowledgedSavedPages: Set<String> = emptySet()
    )

    private fun recoverableRoundFailure(message: String, hasToolContext: Boolean): Boolean = (
        hasToolContext &&
            listOf(
                "reached its output limit",
                "reached the model output limit"
            ).any { message.contains(it, ignoreCase = true) }
        ) ||
        listOf(
            "Tool arguments were not valid JSON",
            "incomplete function call",
            "unparsed tool-call markup",
            "before completing the tool call",
            "connection abort",
            "connection reset",
            "temporarily overloaded",
            "timed out",
            "timeout has expired"
        ).any { message.contains(it, ignoreCase = true) }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.collectRound(
        session: AgentProviderSession,
        exposedDefinitions: List<AgentToolDefinition>,
        exchanges: List<AgentToolExchange>,
        remainingObserved: Int,
        successfulSearch: Boolean,
        searchToolNames: Set<String>,
        consumedCallIds: MutableSet<String>,
        deliveredResultDigests: MutableMap<String, String>,
        hasUnknownOutcome: Boolean,
        replayLimit: Int = effectiveReplayTokens,
        requiredSavedPageDigests: Map<String, String?> = emptyMap(),
        savedPageReading: JsonObject? = null
    ): RoundResult {
        val calls = mutableListOf<ProviderEvent.ToolCall>()
        val admission = ToolRoundAdmission(minOf(MAX_BUFFERED_TOOL_CALLS, remainingObserved), maxSameTool = MAX_SAME_TOOL_CALLS_PER_RUN)
        var batchRejected = false
        var batchRejection: String? = null
        var parserObservedCalls = 0
        var completed = false
        var failure: String? = null
        var progressDeadline: ProgressDeadlineOutcome? = null
        var failureCode: String? = null
        var failureTransportComplete = false
        var taskOutcome: String? = null
        var failureDetails: JsonObject? = null
        var failureInvocationId: String? = null
        var providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null
        var textEmitted = false
        var applicationReportEmitted = false
        var evidenceCorrectionEligible = true
        var providerPayloadObserved = false
        val answerCandidate = StringBuilder()
        var toolDefinitionsRejected = false
        val roundInput = ProviderUsageCounter()
        val roundOutput = ProviderUsageCounter()
        val roundTotal = ProviderUsageCounter()
        var roundRuntime: String? = null
        var roundDecodeSpeed: Double? = null
        var roundFinishReason: String? = null
        var roundReasoningTokens: Long? = null
        var roundSettingsConfirmed = false
        val usageInvocationIds = linkedSetOf<String>()
        var unboundUsageObserved = false
        var deliveredCallIds: Set<String> = emptySet()
        var deliveredBodies: Map<String, String> = emptyMap()
        var deliveryOnly = false
        var unacknowledgedSavedPages = emptySet<String>()
        var completedReading: JsonObject? = null
        var invocationStage = "planning"
        var invocationStarted = false

        suspend fun emitRoundUsage() {
            if (roundInput.value == null && roundOutput.value == null && roundTotal.value == null && roundDecodeSpeed == null && roundFinishReason == null && !roundSettingsConfirmed && roundReasoningTokens == null) return
            emit(
                AgentRunEvent.Provider(
                    ProviderEvent.Usage(
                        inputTokens = roundInput.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        outputTokens = roundOutput.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        totalTokens = roundTotal.value?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        cumulative = false,
                        decodeTokensPerSecond = roundDecodeSpeed,
                        finishReason = roundFinishReason,
                        effectiveSettingsConfirmed = roundSettingsConfirmed,
                        reasoningTokens = roundReasoningTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        inputSource = roundInput.source,
                        outputSource = roundOutput.source,
                        actualRuntime = roundRuntime,
                        phase = if (listOf(roundInput, roundOutput, roundTotal).filter { it.value != null }.all { it.phase == ProviderUsagePhase.FINAL }) ProviderUsagePhase.FINAL else ProviderUsagePhase.INTERIM,
                        invocationId = usageInvocationIds.singleOrNull().takeUnless { unboundUsageObserved }
                    )
                )
            )
        }

        // Keep provider failures separate from downstream collector failures. In
        // particular, never emit usage from finally after cancellation or a failed emit.
        flow {
            if (exchanges.isNotEmpty() && replayLimit <= 0) {
                emit(
                    ProviderEvent.Failed(
                        "No actionable tool receipt fits this request. Lower the stage output reservation or narrow the schemas before continuing; saved evidence was retained.",
                        errorCode = "REPLAY_EVIDENCE_REQUIRED",
                        failureDetails = kotlinx.serialization.json.buildJsonObject {
                            put("failed_stage", "planning")
                            put("dispatched", false)
                            put("failure_origin", "APPLICATION")
                            put("replay_budget_state", "NO_CONTINUATION_ALLOWANCE")
                            put("replay_allowance", replayLimit.coerceAtLeast(0))
                            put("replay_exchange_count", exchanges.size)
                        }
                    )
                )
                return@flow
            }
            val originalResults = exchanges.flatMap { it.results }.associateBy { it.callId }
            val mayStage = requiredSavedPageDigests.isNotEmpty() &&
                requiredSavedPageDigests.values.none { it == null } &&
                !hasUnknownOutcome &&
                !session.handlesToolsInternally &&
                limits.combined == null &&
                !verificationOnlyRequested
            if (savedPageReading != null || mayStage && requiredSavedPageDigests.size > 1) {
                // Private sessions intentionally accept neither tools nor exchange replay. Do not
                // inject restored plaintext notes after their masking/serialized-request boundary.
                dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore.requireUnprotectedTransport()
            }
            // Only reserve a new private note after full-page delivery actually fails.
            var sourceAllowance = (replayLimit - SavedPageReading.reservation(savedPageReading, false)).coerceAtLeast(0)
            if (sourceAllowance == 0 && savedPageReading != null) {
                emit(
                    ProviderEvent.Failed(
                        "Mandatory saved-page reading context does not fit this request. Saved pages and prior notes remain intact.",
                        errorCode = "REPLAY_EVIDENCE_REQUIRED",
                        failureDetails = buildJsonObject {
                            put("failed_stage", "planning")
                            put("failure_origin", "APPLICATION")
                            put("dispatched", false)
                            put("replay_budget_state", "NO_CONTINUATION_ALLOWANCE")
                            put("replay_allowance", replayLimit)
                            put("source_allowance", sourceAllowance)
                            put("pending_saved_page_count", requiredSavedPageDigests.size)
                        }
                    )
                )
                return@flow
            }
            suspend fun project(required: Map<String, String?>, candidates: List<AgentToolExchange> = exchanges) = prepareReplay(candidates, sourceAllowance, limits.maxReplayResultTokens, consumedCallIds, deliveredResultDigests, required)
            fun delivered(projection: ReplayProjection) = projection.exchanges.flatMap { it.results }.filter { originalResults[it.callId]?.content == it.content }
            var replay = project(requiredSavedPageDigests)
            var exact = delivered(replay)
            deliveredBodies = ToolResultDeliveryProof.capture(exact, replayRenderCache)
            val isolatedReading = mayStage && requiredSavedPageDigests.any { (id, digest) -> deliveredBodies[id] != digest }
            if (isolatedReading) {
                dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore.requireUnprotectedTransport()
                // Spend a private reading stage's allowance only on complete pairs
                // whose exact bytes this journal can acknowledge. A decorated final
                // page remains in the ordinary history, but cannot crowd out these reads.
                val journalBodies = checkpoint?.readingSourceBodies(exchanges.flatMap { it.calls }.associateBy { it.callId }, requiredSavedPageDigests)
                val eligible = journalBodies?.keys ?: requiredSavedPageDigests.keys
                val privateCandidates = exchanges.mapNotNull { exchange ->
                    val calls = exchange.calls.filter { it.callId in eligible }
                    if (calls.isEmpty()) return@mapNotNull null
                    val results = exchange.results.filter { it.callId in eligible }.map { result ->
                        journalBodies?.get(result.callId)?.let { result.copy(content = it) } ?: result
                    }
                    AgentToolExchange(calls, results)
                }
                val privateResults = privateCandidates.flatMap { it.results }.associateBy { it.callId }
                fun privateDelivery(projection: ReplayProjection) = projection.exchanges.flatMap { it.results }.filter {
                    privateResults[it.callId]?.content == it.content
                }
                sourceAllowance = (replayLimit - SavedPageReading.reservation(savedPageReading, true)).coerceAtLeast(0)
                if (sourceAllowance > 0) {
                    replay = project(requiredSavedPageDigests, privateCandidates)
                    exact = privateDelivery(replay)
                    deliveredBodies = ToolResultDeliveryProof.capture(exact, replayRenderCache)
                } else {
                    emit(
                        ProviderEvent.Failed(
                            "The saved reading context leaves no space for another page.",
                            errorCode = "REPLAY_EVIDENCE_REQUIRED",
                            failureDetails = buildJsonObject {
                                put("failed_stage", "planning")
                                put("failure_origin", "APPLICATION")
                                put("dispatched", false)
                                put("replay_budget_state", "SAVED_PAGE_EXCEEDS_ALLOWANCE")
                                put("replay_missing_record", "BODY_ALLOWANCE")
                                put("source_allowance", 0)
                                put("replay_allowance", replayLimit)
                                put("pending_saved_page_count", requiredSavedPageDigests.size)
                            }
                        )
                    )
                    return@flow
                }
                if (eligible.none { deliveredBodies[it] == requiredSavedPageDigests[it] }) {
                    val first = eligible.mapNotNull(privateResults::get)
                        .filter { ToolResultDeliveryProof.digest(it.content, replayRenderCache) == requiredSavedPageDigests[it.callId] }
                        .minByOrNull { replayRenderCache.measure(it.content).tokens }
                    if (first != null) {
                        val sourceCall = privateCandidates.asSequence().flatMap { it.calls.asSequence() }.first { it.callId == first.callId }
                        val singlePage = AgentToolExchange(listOf(sourceCall), listOf(first))
                        replay = project(mapOf(first.callId to requiredSavedPageDigests[first.callId]), listOf(singlePage))
                        exact = privateDelivery(replay)
                        deliveredBodies = ToolResultDeliveryProof.capture(exact, replayRenderCache)
                    }
                }
                if (eligible.size < requiredSavedPageDigests.size) {
                    deliveredBodies = deliveredBodies.filterKeys { it in eligible }
                    if (eligible.none { deliveredBodies[it] == requiredSavedPageDigests[it] }) {
                        unacknowledgedSavedPages = requiredSavedPageDigests.keys - eligible
                        return@flow
                    }
                }
            }
            val owed = requiredSavedPageDigests.filter { (id, digest) -> digest == null || deliveredBodies[id] != digest }
            deliveryOnly = isolatedReading && owed.size < requiredSavedPageDigests.size
            if (deliveryOnly) deliveredBodies = deliveredBodies.filterKeys { it in requiredSavedPageDigests && it !in owed }
            deliveredCallIds = if (deliveryOnly) deliveredBodies.keys else exact.mapTo(mutableSetOf()) { it.callId }
            val rawReplayTokens = replay.rawTokens
            val replayExchanges = if (deliveryOnly) {
                replay.exchanges.mapNotNull { exchange ->
                    val calls = exchange.calls.filter { it.callId in deliveredBodies }
                    calls.takeIf { it.isNotEmpty() }?.let { AgentToolExchange(it, exchange.results.filter { result -> result.callId in deliveredBodies }) }
                }
            } else {
                replay.exchanges
            }
            if (owed.isNotEmpty() && !deliveryOnly) {
                val measured = owed.mapNotNull { (id, digest) ->
                    originalResults[id]?.takeIf { digest != null && ToolResultDeliveryProof.digest(it.content, replayRenderCache) == digest }
                        ?.let { replayRenderCache.measure(it.content).tokens }
                }
                val smallest = measured.minOrNull()
                val state = when {
                    owed.any { (id, digest) -> digest == null || originalResults[id] == null } -> "SAVED_PAGE_BODY_UNAVAILABLE"
                    measured.size == owed.size && smallest != null && smallest > sourceAllowance -> "SAVED_PAGE_EXCEEDS_ALLOWANCE"
                    else -> "SAVED_PAGE_DELIVERY_MISMATCH"
                }
                val pageIds = owed.keys.take(3).filter { it.matches(Regex("[A-Za-z0-9_.:-]{1,96}")) }.joinToString(", ")
                val recovery = when (state) {
                    "SAVED_PAGE_BODY_UNAVAILABLE" -> {
                        val liveObservation = exchanges.any { exchange ->
                            exchange.calls.any { it.callId in owed && dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) in setOf("github_pr", "github_issue", "github_ci", "github_release", "github_watch_checks") }
                        }
                        if (liveObservation) {
                            "The original GitHub observation is unavailable. A separately authorized read can report current state. Reconcile any previously submitted change without repeating it."
                        } else {
                            "The original saved page body is unavailable. Its pinned source could not be verified against the authorized checkpoint."
                        }
                    }
                    "SAVED_PAGE_EXCEEDS_ALLOWANCE" -> "The smallest outstanding page needs at least $smallest estimated body tokens and the source allowance is $sourceAllowance. Narrow the requested range or authorize more context."
                    else -> "The delivered saved page does not match its expected body. Restore the exact page from the saved checkpoint before continuing."
                }
                emit(
                    ProviderEvent.Failed(
                        recovery + if (pageIds.isEmpty()) "" else " Outstanding page receipts: $pageIds.",
                        errorCode = "REPLAY_EVIDENCE_REQUIRED",
                        taskOutcome = "BLOCKED",
                        failureDetails = buildJsonObject {
                            put("failed_stage", "planning")
                            put("dispatched", false)
                            put("logical_continuation_allowed", false)
                            put("failure_origin", "APPLICATION")
                            put("replay_budget_state", state)
                            val missing = owed.entries.firstOrNull { (id, digest) -> digest == null || originalResults[id] == null } ?: owed.entries.first()
                            put(
                                "replay_missing_record",
                                when {
                                    missing.value == null -> "EXPECTED_BODY_DIGEST"
                                    originalResults[missing.key] == null -> "SAVED_PAGE_BODY"
                                    state == "SAVED_PAGE_EXCEEDS_ALLOWANCE" -> "BODY_ALLOWANCE"
                                    else -> "PROJECTION_MATCH"
                                }
                            )
                            replaySource(exchanges, missing.key, missing.value)
                            put("pending_saved_page_count", owed.size)
                            smallest?.let { put("minimum_page_body_tokens", it) }
                            put("source_allowance", sourceAllowance)
                            put("replay_exchange_count", exchanges.size)
                            put("retryable", false)
                            put("replay_tokens", replay.compactedTokens)
                            put("replay_allowance", replayLimit)
                        }
                    )
                )
                return@flow
            }
            val compactedReplayTokens = replay.compactedTokens
            val compactionMicros = replay.compactionMicros
            if (rawReplayTokens > compactedReplayTokens && replayCompactionLogGate.shouldLog(rawReplayTokens, exchanges.size, compactionMicros)) {
                AppLogRecorder.record(
                    "Agent",
                    "PRIMARY_REPLAY_COMPACTED · rawTokens=$rawReplayTokens · replayTokens=$compactedReplayTokens · savedTokens=${rawReplayTokens - compactedReplayTokens} · exchanges=${exchanges.size} · budget=$replayLimit · compactionUs=$compactionMicros · tokenCounts=ESTIMATED · retainedEvidenceIndependent=true · logPolicy=EXPONENTIAL_HIGH_WATER"
                )
            }
            if (compactedReplayTokens > replayLimit) {
                AppLogRecorder.record(
                    "Agent",
                    "PRIMARY_REPLAY_BUDGET_OVERRUN · replayTokens=$compactedReplayTokens · budget=$replayLimit · exchanges=${exchanges.size}",
                    "W"
                )
                emit(
                    ProviderEvent.Failed(
                        "The exact saved tool-call identities do not fit the replay allowance. Increase the available context or reduce the request's output reservation before resuming; saved tool results remain intact.",
                        errorCode = "REPLAY_EVIDENCE_REQUIRED",
                        taskOutcome = "BLOCKED",
                        failureDetails = buildJsonObject {
                            put("failed_stage", "planning")
                            put("dispatched", false)
                            put("failure_origin", "APPLICATION")
                            put("replay_budget_state", "COMPACTED_IDENTITIES_EXCEED_ALLOWANCE")
                            put("replay_exchange_count", exchanges.size)
                            put("retryable", false)
                            put("replay_tokens", compactedReplayTokens)
                            put("replay_allowance", replayLimit)
                        }
                    )
                )
                return@flow
            }
            // Include synchronous stream construction failures in the upstream boundary.
            val readingSources = if (deliveryOnly) SavedPageReading.sources(exchanges, deliveredBodies) else null
            val noteBytes = readingSources?.let { SavedPageReading.noteAllowance(savedPageReading, it, replayLimit) }
            if (noteBytes != null && noteBytes == 0) {
                emit(
                    SavedPageDeliveryRound.blocked("No bounded space remains for the next saved-page reading note. Prior notes and pending source pages remain checkpointed.", "planning") {
                        put("replay_budget_state", "NO_CONTINUATION_ALLOWANCE")
                        put("pending_saved_page_count", requiredSavedPageDigests.size)
                        put("replay_allowance", replayLimit)
                    }
                )
                return@flow
            }
            val (preparedExchanges, projectedTokens) = SavedPageReading.providerProjection(replayExchanges, savedPageReading, noteBytes, replayLimit, replayRenderCache) {
                dev.chungjungsoo.gptmobile.data.assist.OptionalAssistArtifacts.ready(it)
            }
            if (projectedTokens > replayLimit) {
                emit(
                    SavedPageDeliveryRound.blocked("The saved-page bodies and mandatory reading context exceed this request's replay allowance. No page was acknowledged.", "planning") {
                        put("replay_budget_state", "SAVED_PAGE_EXCEEDS_ALLOWANCE")
                        put("pending_saved_page_count", requiredSavedPageDigests.size)
                        put("replay_allowance", replayLimit)
                        put("replay_tokens", projectedTokens)
                    }
                )
                return@flow
            }
            if (requiredSavedPageDigests.isNotEmpty()) {
                val onWire = ToolResultDeliveryProof.capture(preparedExchanges.flatMap { it.results }, replayRenderCache)
                val mismatch = requiredSavedPageDigests.keys.firstOrNull { it in deliveredBodies && onWire[it] != deliveredBodies[it] }
                if (mismatch != null) {
                    emit(
                        SavedPageDeliveryRound.blocked("The final provider projection changed a required saved page. Its original receipt is available for recovery.", "planning") {
                            put("replay_budget_state", "SAVED_PAGE_DELIVERY_MISMATCH")
                            put("replay_missing_record", "PROJECTION_MATCH")
                            replaySource(exchanges, mismatch, requiredSavedPageDigests[mismatch])
                            put("pending_saved_page_count", requiredSavedPageDigests.size)
                            put("replay_allowance", replayLimit)
                        }
                    )
                    return@flow
                }
            }
            val exchangeError = AgentContractValidation.exchangeError(preparedExchanges)
            if (exchangeError != null) {
                emit(
                    ProviderEvent.Failed(
                        exchangeError,
                        errorCode = "TOOL_EXCHANGE_INVALID",
                        failureDetails = kotlinx.serialization.json.buildJsonObject {
                            put("failed_stage", "planning")
                            put("dispatched", false)
                        }
                    )
                )
            } else {
                invocationStage = "model_invocation"
                invocationStarted = true
                emitAll(
                    (
                        if (deliveryOnly) {
                            SavedPageDeliveryRound.read(session, preparedExchanges, requireNotNull(noteBytes)) { notes ->
                                completedReading = SavedPageReading.append(savedPageReading, requireNotNull(readingSources), notes)
                            }
                        } else {
                            session.streamRound(exposedDefinitions, preparedExchanges)
                        }
                        )
                        .onEach { event ->
                            // Observe ownership before buffering can make mixed lineage UNKNOWN.
                            // Legacy bare text remains eligible; owned output cannot become a draft again.
                            if (event is ProviderEvent.CombinedAnswer ||
                                event is ProviderEvent.TextDelta &&
                                event.lineage.origin != OutputOrigin.MODEL_TEXT &&
                                event.lineage.origin != OutputOrigin.UNKNOWN
                            ) {
                                evidenceCorrectionEligible = false
                            }
                        }
                        .withAssistantTextMarkupGuard(successfulSearch || session.hasPreparedSearchEvidence, searchToolNames, allowControlJsonText = session.allowsControlJsonText)
                )
            }
        }.catch { error ->
            if (error is CancellationException || error is VirtualMachineError || error is ThreadDeath) throw error
            // Preparation may rebuild before any work. Once an earlier operation
            // is uncertain, retain this upstream failure for reconciliation instead
            // of losing the journal context at an outer preparation boundary.
            if (!hasUnknownOutcome && error is IllegalStateException && error.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true) throw error
            if (error is ToolBatchOversizeException) {
                batchRejection = error.message
                batchRejected = true
                if (error.threshold == "STREAM_TOOL_SLOTS") parserObservedCalls = error.observedValue?.coerceIn(0, 1024) ?: 0
                return@catch
            }
            failureTransportComplete = false
            // Keep the first terminal provider failure; trailing transport errors
            // must not replace it or change the recovery decision.
            if (failure == null) {
                toolDefinitionsRejected = error is ToolDefinitionsRejectedException
                val network = dev.chungjungsoo.gptmobile.data.network.networkFailureKind(error)
                val classified = ErrorClassification.classify(error)
                val gatewayOutcomeUnknown = GatewayOutcomeRecovery.requiresReconciliation(error.message.orEmpty(), classified.errorCode)
                val localCircuitRejection = (error as? dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException)
                    ?.takeUnless { gatewayOutcomeUnknown }
                val persistenceFailure = generateSequence(error) { it.cause }.take(12)
                    .filterIsInstance<dev.chungjungsoo.gptmobile.data.workspace.WorkspaceEvidenceConflict>().firstOrNull()
                val typedRejection = persistenceFailure?.event() ?: AgentFailureEvidence.exception(error, providerPayloadObserved)
                val classifiedCode = ToolFailureMetadata.classify(error)
                // This boundary invokes the model, not an AgentTool. An unknown
                // exception cannot establish a tool failure or authorize replay.
                val fallbackCode = if (classifiedCode == "TOOL_FAILURE") {
                    if (invocationStarted) "PROVIDER_INVOCATION_FAILED" else "APP_ERROR"
                } else {
                    classifiedCode
                }
                failureDetails = typedRejection?.failureDetails ?: localCircuitRejection?.failureDetails(providerPayloadObserved) ?: kotlinx.serialization.json.buildJsonObject {
                    put(
                        "failed_stage",
                        when {
                            gatewayOutcomeUnknown -> "gateway_dispatch"
                            toolDefinitionsRejected -> "provider_admission"
                            network != dev.chungjungsoo.gptmobile.data.network.NetworkFailureKind.UNKNOWN -> network.failedStage
                            else -> invocationStage
                        }
                    )
                    if (!invocationStarted) put("dispatched", false)
                    if (classifiedCode == "TOOL_FAILURE") {
                        put("retryable", false)
                        put("request_replay_allowed", false)
                        put("logical_continuation_allowed", false)
                        IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.AGENT_ROUND_CATCH)
                            .forEach { (key, value) -> put(key, value) }
                    }
                }
                failure = if (toolDefinitionsRejected) {
                    error.message ?: "Tools are unavailable for this model."
                } else {
                    failureCode = if (gatewayOutcomeUnknown) GatewayOutcomeRecovery.ERROR_CODE else typedRejection?.errorCode ?: ToolFailureMetadata.authorityCode(error) ?: fallbackCode
                    taskOutcome = typedRejection?.taskOutcome
                    failureTransportComplete = typedRejection?.transportComplete ?: false
                    typedRejection?.message ?: classified.userMessage
                }
            }
        }.transformWhile { event ->
            if (verificationOnlyRequested && (event is ProviderEvent.ToolCall || event is ProviderEvent.ToolResult)) {
                emit(
                    verificationOnlyFailure(
                        "Verification requested tool work. The request was blocked; saved receipts and unfinished operations remain unchanged.",
                        hasUnknownOutcome
                    )
                )
                false
            } else if (event is ProviderEvent.Failed && GatewayOutcomeRecovery.requiresReconciliation(event.message, event.errorCode)) {
                emit(event)
                false
            } else if (event is ProviderEvent.ToolCall && !admission.admit(event)) {
                batchRejection = admission.rejection?.message
                batchRejected = true
                false
            } else if (event is ProviderEvent.Failed && event.message.startsWith("TOOL_BATCH_OVERSIZE")) {
                batchRejection = event.message
                batchRejected = true
                false
            } else {
                emit(event)
                true
            }
        }.collect { event ->
            if (event is ProviderEvent.TextDelta || event is ProviderEvent.ThinkingDelta || event is ProviderEvent.ToolCall || event is ProviderEvent.ToolResult || event is ProviderEvent.CombinedAnswer) providerPayloadObserved = true
            if (event is ProviderEvent.Failed && GatewayOutcomeRecovery.requiresReconciliation(event.message, event.errorCode)) {
                failure = event.message
                failureCode = GatewayOutcomeRecovery.ERROR_CODE
                progressDeadline = event.progressDeadline
                failureTransportComplete = event.transportComplete
                providerLimit = event.providerLimit
                taskOutcome = event.taskOutcome
                failureDetails = event.failureDetails
                failureInvocationId = event.invocationId
                return@collect
            }
            if (failure != null && event !is ProviderEvent.Usage) {
                if (event is ProviderEvent.Failed && event.transportComplete && dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(event.message)) failureTransportComplete = true
                if (event == ProviderEvent.Completed && dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(failure.orEmpty())) failureTransportComplete = true
                return@collect
            }
            when (event) {
                is ProviderEvent.ToolCall -> {
                    if (!session.handlesToolsInternally) {
                        calls += event
                    } else {
                        emit(AgentRunEvent.Provider(event))
                    }
                }

                is ProviderEvent.ToolResult -> {
                    val receipt = AgentContractValidation.receipt(event.call.callId, event.result)
                    onToolResult?.invoke(event.call, receipt)
                    emit(AgentRunEvent.ToolFinished(event.call, ToolObservationRetention.bound(receipt)))
                }

                is ProviderEvent.Failed -> {
                    failure = event.message
                    progressDeadline = event.progressDeadline
                    failureCode = event.errorCode
                    failureTransportComplete = event.transportComplete
                    taskOutcome = event.taskOutcome
                    // The producer owns this terminal event. Chat observes delivery separately.
                    failureDetails = event.failureDetails
                    failureInvocationId = event.invocationId
                    providerLimit = event.providerLimit
                }

                is ProviderEvent.Notice -> emit(AgentRunEvent.Notice(event.message, event.persistent, event.researchDelivery))

                is ProviderEvent.Usage -> {
                    // Private invocations have their own ledger entries. This event describes the public provider round.
                    if (event.advisoryRole != null) return@collect
                    if (event.invocationId == null) unboundUsageObserved = true else usageInvocationIds += event.invocationId
                    event.reasoningTokens?.let { tokens ->
                        roundReasoningTokens = if (event.cumulative) maxOf(roundReasoningTokens ?: 0L, tokens.toLong()) else (roundReasoningTokens ?: 0L) + tokens
                    }
                    event.decodeTokensPerSecond?.let { roundDecodeSpeed = it }
                    event.finishReason?.let { roundFinishReason = it }
                    roundSettingsConfirmed = roundSettingsConfirmed || event.effectiveSettingsConfirmed
                    roundRuntime = event.actualRuntime ?: roundRuntime
                    roundInput.accept(event.inputTokens, event.cumulative, event.inputSource, event.phase)
                    roundOutput.accept(event.outputTokens, event.cumulative, event.outputSource, event.phase)
                    val totalSource = if (event.inputSource == event.outputSource) {
                        event.outputSource
                    } else if (measuredUsageSource(event.inputSource, event.phase) && measuredUsageSource(event.outputSource, event.phase)) {
                        "mixed_reported"
                    } else {
                        "estimated"
                    }
                    roundTotal.accept(event.totalTokens, event.cumulative, totalSource, event.phase)
                }

                ProviderEvent.Completed -> completed = true

                else -> {
                    if (event is ProviderEvent.CombinedAnswer) {
                        // This is the whole delivered body, including finalization
                        // paths that emitted no provisional deltas. Treating it as
                        // empty would dispatch a second writer after publication.
                        answerCandidate.clear()
                        textEmitted = dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.hasAnswer(event.text)
                    } else if (event is ProviderEvent.TextDelta && !textEmitted) {
                        answerCandidate.append(event.text)
                        textEmitted = dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.hasAnswer(answerCandidate.toString())
                    }
                    if (event is ProviderEvent.TextDelta && event.text.isNotBlank() && event.lineage.origin == dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.APPLICATION_REPORT) {
                        applicationReportEmitted = true
                    }
                    emit(AgentRunEvent.Provider(event))
                }
            }
        }
        // Do not emit from finally: a cancelled or failed consumer cannot accept
        // another event. Genuine provider failures still retain observed usage.
        emitRoundUsage()
        if (unacknowledgedSavedPages.isNotEmpty()) return RoundResult(unacknowledgedSavedPages = unacknowledgedSavedPages)
        if (!verificationOnlyRequested &&
            completed &&
            failure == null &&
            (deliveredCallIds.any { it !in consumedCallIds } || deliveredBodies.any { (id, digest) -> deliveredResultDigests[id] != digest })
        ) {
            if (deliveryOnly) {
                val reading = requireNotNull(completedReading)
                SavedPageReading.requireAppend(savedPageReading, reading, deliveredBodies.keys)
                SavedPageReading.validate(reading, SavedPageReading.fingerprints(savedPageReading) + exchanges.flatMap { it.calls }.associate { it.callId to ToolHandoffReceipt.fingerprint(it) }, deliveredResultDigests + deliveredBodies)
            }
            if (checkpoint?.markConsumed(deliveredCallIds, deliveredBodies, completedReading.takeIf { deliveryOnly }) == false) {
                return RoundResult(unacknowledgedSavedPages = deliveredBodies.keys)
            }
            consumedCallIds += deliveredCallIds
            deliveredResultDigests.putAll(deliveredBodies)
            while (deliveredResultDigests.size > ToolResultDeliveryProof.MAX_RESULTS) deliveredResultDigests.remove(deliveredResultDigests.keys.first())
        }
        return RoundResult(calls, completed, failure, textEmitted, toolDefinitionsRejected, batchRejected, maxOf(admission.observed, parserObservedCalls), progressDeadline, batchRejection, failureCode, failureTransportComplete, providerLimit, taskOutcome, failureDetails, failureInvocationId, applicationReportEmitted, evidenceCorrectionEligible = evidenceCorrectionEligible, savedPageDelivery = deliveryOnly && completed && failure == null, savedPageReading = completedReading)
    }

    private fun toolResultText(result: AgentToolResult): String = when (val content = result.content) {
        is ToolResultContent.Text -> content.text
        is ToolResultContent.Json -> content.value.toString()
        is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
    }

    private fun countsTowardToolFailureCircuit(result: AgentToolResult): Boolean {
        if (!result.isError ||
            !result.dispatched ||
            result.combinedWorkBudgetExhausted ||
            result.outputBudgetExhausted ||
            result.toolCallBudgetExhausted ||
            ToolResultEnvelope.isAuthorizationRejection(result.errorCode)
        ) {
            return false
        }
        val text = when (val content = result.content) {
            is ToolResultContent.Text -> content.text
            is ToolResultContent.Json -> content.value.toString()
            is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
        }.lowercase()
        return CONTROL_FAILURE_MARKERS.none(text::contains)
    }

    private fun updateFailureCircuits(
        executableCalls: List<ProviderEvent.ToolCall>,
        executedResults: List<AgentToolResult>,
        consecutiveToolFailures: MutableMap<String, Int>,
        blockedReadScopes: MutableSet<String>,
        failureScope: (ProviderEvent.ToolCall) -> String
    ): Set<String> {
        val newlyBlockedTools = mutableSetOf<String>()
        // Count reliability once per tool per model round, not once per parallel
        // invocation. A single burst of four searches must not consume four
        // "consecutive failure" slots and instantly poison the tool for the turn.
        executableCalls.zip(executedResults)
            .groupBy(keySelector = { failureScope(it.first) }, valueTransform = { it.second })
            .forEach { (toolName, roundResults) ->
                val failures = when {
                    roundResults.any { result -> result.isError && result.dispatched && !ToolResultEnvelope.isAuthorizationRejection(result.errorCode) && terminalToolFailure(toolResultText(result)) } -> MAX_CONSECUTIVE_TOOL_FAILURES
                    roundResults.any { !it.isError } -> 0
                    roundResults.none(::countsTowardToolFailureCircuit) -> consecutiveToolFailures[toolName] ?: 0
                    else -> (consecutiveToolFailures[toolName] ?: 0) + 1
                }
                consecutiveToolFailures[toolName] = failures
                if (failures >= MAX_CONSECUTIVE_TOOL_FAILURES) {
                    if ("|host=" in toolName || "|operation=" in toolName) {
                        blockedReadScopes += toolName
                        AppLogRecorder.record("Agent", "TOOL_FAILURE_CIRCUIT_OPEN · scope=$toolName · failures=$failures · otherHostsAvailable=true", "W")
                    } else {
                        newlyBlockedTools += toolName
                    }
                }
            }
        return newlyBlockedTools
    }

    private suspend fun executeToolBatch(
        calls: List<ProviderEvent.ToolCall>,
        tools: Map<String, AgentTool>,
        unknownOutcomes: MutableMap<String, JsonObject>,
        invalidatedAuthority: AtomicReference<AgentToolResult?>,
        completed: suspend (ProviderEvent.ToolCall, AgentToolResult) -> Unit,
        progress: suspend (ToolBatchProgress) -> Unit,
        dependencyPlan: List<ToolBatchNode>,
        restoredCompleted: Map<String, AgentToolResult>
    ): List<AgentToolResult> {
        val intelligent = limits.githubTask ||
            limits.researchAcquisition ||
            calls.any {
                dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) != null || replayableRead(it, tools[it.name])
            }
        if (!intelligent) {
            val results = mutableListOf<AgentToolResult>()
            // Keep undispatched later chunks out of the cancellation race. The
            // dependency-aware scheduler below still owns one complete DAG.
            for (batch in calls.chunked(limits.maxToolCallsPerRound.coerceIn(1, 32))) {
                val receipts = executeLegacyToolBatch(batch, tools, unknownOutcomes, invalidatedAuthority, restoredCompleted, completed)
                results += receipts
            }
            return results
        }
        val admittedIds = calls.mapTo(hashSetOf()) { it.callId }
        val nodesById = dependencyPlan.associateBy { it.call.callId }
        val fullPlan = calls.map { call ->
            nodesById[call.callId] ?: run {
                check(call.callId in restoredCompleted)
                ToolBatchNode(call, emptySet(), false)
            }
        }
        val blocked = fullPlan.filter { !admittedIds.containsAll(it.failureDependencies) }.mapTo(hashSetOf()) { it.call.callId }
        val nodes = fullPlan.map {
            it.copy(dependencies = it.dependencies.intersect(admittedIds), failureDependencies = it.failureDependencies.intersect(admittedIds))
        }
        return IntelligentToolBatch(kotlinx.coroutines.currentCoroutineContext()[AdaptiveBatchScope]?.window ?: AdaptiveBatchWindow(limits.maxConcurrentTools)).execute(
            nodes,
            dispatch = { call ->
                if (call.callId in blocked) {
                    val dependencies = nodesById.getValue(call.callId).failureDependencies
                    return@execute ToolDependencyDiagnostics.rejection(call.callId, ToolDependencyDiagnostics.State.DEFERRED, dependencies, dependencies - admittedIds, "A prerequisite was deferred by admission. This operation was not dispatched; replan after the missing read completes.")
                }
                val signature = call.name + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(call.arguments)
                suspend fun dispatch(): AgentToolResult = if (!replayableRead(call, tools[call.name]) && unknownOutcomes.containsKey(signature)) {
                    ToolReconciliationEvidence.blocked(call.callId, requireNotNull(unknownOutcomes[signature]))
                } else {
                    invalidatedAuthority.get()?.let { ToolFailureMetadata.authorityBlocked(call.callId, call.name, it) }
                        ?: executeBounded(call, tools[call.name]).also { if (ToolFailureMetadata.authorityCode(it) != null) invalidatedAuthority.compareAndSet(null, it) }
                }
                val interaction = AgentToolCapability.USER_DECISION in tools[call.name]?.definition?.capabilities.orEmpty()
                val result = if (interaction) dispatch().also { checkpoint?.complete(call, it) } else checkpoint?.execute(call, replayableRead = replayableRead(call, tools[call.name])) { dispatch() } ?: dispatch()
                if (ToolFailureMetadata.authorityCode(result) != null) invalidatedAuthority.compareAndSet(null, result)
                if (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead(call, tools[call.name])) synchronized(unknownOutcomes) { unknownOutcomes.putIfAbsent(signature, ToolReconciliationEvidence.observation(call, result)) }
                result
            },
            completed = completed,
            progress = progress,
            restored = restoredCompleted
        )
    }

    private suspend fun executeLegacyToolBatch(
        calls: List<ProviderEvent.ToolCall>,
        tools: Map<String, AgentTool>,
        unknownOutcomes: MutableMap<String, JsonObject>,
        invalidatedAuthority: AtomicReference<AgentToolResult?>,
        restoredCompleted: Map<String, AgentToolResult>,
        completed: suspend (ProviderEvent.ToolCall, AgentToolResult) -> Unit
    ): List<AgentToolResult> {
        val semaphore = Semaphore(limits.maxConcurrentTools.coerceIn(1, 32))
        val perTool = calls.map { it.name }.distinct().associateWith { Semaphore(1) }
        fun ownerOf(call: ProviderEvent.ToolCall): AgentToolExecutionOwner =
            (tools[call.name] as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT
        return coroutineScope {
            // One terminal outcome per worker fits without suspending a confirmed receipt.
            val channel = Channel<Result<Pair<ProviderEvent.ToolCall, AgentToolResult>>>(calls.size.coerceAtLeast(1))
            val receipts = linkedMapOf<String, AgentToolResult>()
            val observed = java.util.concurrent.ConcurrentHashMap<String, AgentToolResult>()
            val workers = mutableListOf<Job>()
            var nextCompletion = 0
            try {
                for (ownedCalls in calls.groupBy(::ownerOf).values) {
                    for (call in ownedCalls) {
                        workers += launch {
                            try {
                                val result = restoredCompleted[call.callId] ?: perTool.getValue(call.name).withPermit {
                                    semaphore.withPermit {
                                        val signature = call.name + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(call.arguments)
                                        suspend fun dispatch(): AgentToolResult {
                                            val result = if (!replayableRead(call, tools[call.name]) && unknownOutcomes.containsKey(signature)) {
                                                ToolReconciliationEvidence.blocked(call.callId, requireNotNull(unknownOutcomes[signature]))
                                            } else {
                                                invalidatedAuthority.get()?.let { ToolFailureMetadata.authorityBlocked(call.callId, call.name, it) }
                                                    ?: executeBounded(call, tools[call.name])
                                            }
                                            if (ToolFailureMetadata.authorityCode(result) != null) invalidatedAuthority.compareAndSet(null, result)
                                            // The observer already has the authorized full receipt.
                                            // Preserve this result even if cancellation arrives while
                                            // the checkpoint finishes its non-cancellable save.
                                            observed[call.callId] = result
                                            return result
                                        }
                                        // A local question has its own durable answer. Keep its
                                        // checkpoint queued while waiting, so process death cannot
                                        // misclassify user input as an uncertain external write.
                                        val interaction = AgentToolCapability.USER_DECISION in tools[call.name]?.definition?.capabilities.orEmpty()
                                        val result = if (interaction) dispatch().also { checkpoint?.complete(call, it) } else checkpoint?.execute(call, replayableRead = replayableRead(call, tools[call.name])) { dispatch() } ?: dispatch()
                                        if (ToolFailureMetadata.authorityCode(result) != null) invalidatedAuthority.compareAndSet(null, result)
                                        if (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead(call, tools[call.name])) synchronized(unknownOutcomes) { unknownOutcomes.putIfAbsent(signature, ToolReconciliationEvidence.observation(call, result)) }
                                        result
                                    }
                                }
                                observed[call.callId] = result
                                channel.trySend(Result.success(call to result))
                            } catch (cancelled: CancellationException) {
                                // Let the collector surface confirmed siblings before cancelling
                                // this batch; cancelling its scope here would discard queued results.
                                channel.trySend(Result.failure(cancelled))
                                throw cancelled
                            }
                        }
                    }
                    repeat(ownedCalls.size) {
                        val outcome = channel.receive()
                        val cancelled = outcome.exceptionOrNull() as? CancellationException
                        if (cancelled != null) {
                            workers.forEach { it.cancel(cancelled) }
                            workers.joinAll()
                            // Cancellation can leave a gap in model order. Publish only actual
                            // completed siblings, in their original relative order, then propagate
                            // cancellation unchanged without claiming the batch or run completed.
                            for (call in calls.drop(nextCompletion)) observed[call.callId]?.let { completed(call, it) }
                            throw cancelled
                        }
                        val (call, result) = outcome.getOrThrow()
                        receipts[call.callId] = result
                        // Dispatch ownership and completion timing must not change the public
                        // result order. Persisted observations do not wait for this visible prefix.
                        while (nextCompletion < calls.size) {
                            val next = calls[nextCompletion]
                            val ready = receipts[next.callId] ?: break
                            completed(next, ready)
                            nextCompletion++
                        }
                    }
                }
                calls.map { receipts.getValue(it.callId) }
            } finally {
                channel.close()
            }
        }
    }

    private fun appendInstruction(result: AgentToolResult, instruction: String): AgentToolResult {
        GitHubReplayRecovery.envelope(result.content)?.let { envelope ->
            val previous = (envelope["agent_instruction"] as? JsonPrimitive)?.content.orEmpty()
            if (previous.contains(instruction)) return result
            return result.copy(content = ToolResultContent.Json(kotlinx.serialization.json.JsonObject(envelope + ("agent_instruction" to JsonPrimitive(listOf(previous, instruction).filter { it.isNotBlank() }.joinToString("\n"))))))
        }
        val existing = when (val content = result.content) {
            is ToolResultContent.Text -> content.text
            is ToolResultContent.Json -> Json.encodeToString(content.value)
            is ToolResultContent.ResourceLinks -> Json.encodeToString(content.links.map { it.uri })
        }
        if (existing.contains(instruction)) return result
        return result.copy(content = ToolResultContent.Text("$existing\n\n$instruction"))
    }

    private fun appendFinalResponseInstruction(result: AgentToolResult): AgentToolResult = appendInstruction(result, FINAL_RESPONSE_INSTRUCTION)

    private fun instructFinalResponse(exchanges: MutableList<AgentToolExchange>, consumedCallIds: MutableSet<String>) {
        val previous = exchanges.lastOrNull() ?: return
        val result = previous.results.lastOrNull() ?: return
        val updated = appendInstruction(result, "Continue with a text answer and no further tool calls. Report verified outcomes from saved receipts, the concrete failure and any unfinished work. Do not claim changes were applied without a successful receipt or repeat a completed action.")
        if (updated === result) return
        // The changed payload needs to reach the provider with its new instruction.
        consumedCallIds.remove(result.callId)
        exchanges[exchanges.lastIndex] = previous.copy(results = previous.results.dropLast(1) + updated)
    }

    private suspend fun executeBounded(
        call: ProviderEvent.ToolCall,
        tool: AgentTool?
    ): AgentToolResult {
        if (tool == null) {
            return AgentToolResult(
                callId = call.callId,
                content = ToolResultContent.Text("Tool '${call.name}' is not assigned to this profile."),
                isError = true,
                errorCode = "TOOL_UNAVAILABLE",
                dispatched = false
            )
        }
        try {
            dev.chungjungsoo.gptmobile.data.agent.LocalToolTransport.requireExecution(tool)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (blocked: Exception) {
            return ToolFailureMetadata.authorityPrecheck(call.callId, call.name, blocked) ?: throw blocked
        }
        AgentContractValidation.admission(tool.definition, call.callId)?.let { return it }
        if (!tool.managesExecutionBudget) {
            val errors = ToolArgumentValidator.errors(call.arguments, tool.definition.inputSchema)
            if (errors.isNotEmpty()) return requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ToolArgumentRepairScope]).budget.rejection(call.callId, tool.definition, call.arguments, errors, searchValidationOwner = tool.searchValidationOwner, catalog = tool.argumentCatalog())
        }
        val replayableRead = replayableRead(call, tool)
        val phoneInterruption = PhoneIndexInterruption(call)
        var observedReceipt: AgentToolResult? = null
        return try {
            // A delegated child can have different data permissions. Capture only
            // this call's authorized receipt, never its private intermediate calls.
            val result = kotlinx.coroutines.withContext(
                phoneInterruption + ToolReceiptObserver { observedCall, receipt ->
                    if (observedCall.callId == call.callId && observedCall.name == call.name && observedCall.arguments == call.arguments) {
                        observedReceipt = ToolFailureMetadata.receipt(AgentContractValidation.receipt(call.callId, receipt), call.name, replaySafe = replayableRead)
                        onToolResult?.invoke(call, receipt)
                    }
                }
            ) {
                if (!tool.managesExecutionBudget && limits.toolTimeoutMillis < Long.MAX_VALUE) {
                    withTimeoutOrNull(limits.toolTimeoutMillis) {
                        tool.execute(call.callId, call.arguments)
                    } ?: phoneInterruption.close() ?: AgentToolResult(
                        callId = call.callId,
                        content = ToolResultContent.Text("Tool '${call.name}' timed out after ${limits.toolTimeoutMillis} ms."),
                        isError = true,
                        errorCode = if (replayableRead) "READ_DEADLINE" else "TIMEOUT_OUTCOME_UNKNOWN"
                    )
                } else {
                    tool.execute(call.callId, call.arguments)
                }
            }
            val receipt = ToolFailureMetadata.receipt(AgentContractValidation.receipt(call.callId, result), call.name, replaySafe = replayableRead)
                .let { if (limits.githubTask) ToolHandoffReceipt.identifySource(it) else it }
            observedReceipt = receipt
            onToolResult?.invoke(call, receipt)
            ToolObservationRetention.bound(if (tool.managesExecutionBudget) receipt else receipt.copy(content = boundContent(receipt.content)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred) {
            throw error
        } catch (error: Exception) {
            if (error is dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected && ToolFailureMetadata.authorityCode(error) == null) {
                return ToolResultEnvelope.error(call.callId, error.code, error.message.orEmpty())
            }
            val retained = observedReceipt?.let { ToolFailureMetadata.withAuthorityFailure(it, error, "delivery") }
            val result = retained ?: AgentToolResult(
                callId = call.callId,
                content = ToolResultContent.Text(error.message ?: "Tool '${call.name}' failed."),
                isError = true,
                errorCode = if (replayableRead) ToolFailureMetadata.classify(error) else "DISPATCH_OUTCOME_UNKNOWN"
            ).let { ToolFailureMetadata.withAuthorityFailure(it, error, "tool_execution") ?: it }
                .let { ToolFailureMetadata.receipt(it, call.name, diagnosticCode = ToolFailureMetadata.classify(error), replaySafe = replayableRead) }
            ToolObservationRetention.bound(if (tool.managesExecutionBudget) result else result.copy(content = boundContent(result.content)))
        } finally {
            phoneInterruption.close()
        }
    }

    private fun replayableRead(call: ProviderEvent.ToolCall, tool: AgentTool?): Boolean =
        ToolReplaySafety.isReplayableRead(call.name, call.arguments, tool)

    private fun boundContent(content: ToolResultContent): ToolResultContent = ToolObservationRetention.content(content, limits.maxToolOutputBytes.coerceIn(0, 256 * 1024))

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val chunk = String(Character.toChars(codePoint))
            val chunkBytes = chunk.toByteArray(StandardCharsets.UTF_8).size
            if (bytes + chunkBytes > maxBytes) break
            result.append(chunk)
            bytes += chunkBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun failed(code: String, message: String) = AgentRunEvent.Provider(ProviderEvent.Failed(message, errorCode = code))

    companion object {
        private const val MAX_SAME_TOOL_CALLS_PER_RUN = 24
        private const val MAX_CONSECUTIVE_TOOL_FAILURES = 3
        private val CONTROL_FAILURE_MARKERS = listOf(
            "tool-call allowance is exhausted",
            "tool result budget exhausted",
            "tool-result byte budget exhausted",
            "tool-call limit reached",
            "tool permission was denied",
            "permission was denied",
            "github_write_blocked",
            "github_not_found_or_hidden",
            "a github token is required",
            "reached the per-response repeat limit",
            "do not retry this call in the same response",
            "delegation was canceled for this turn",
            "delegation was canceled. continue this turn",
            "canceled by parent",
            "cancelled by parent"
        )
        const val TOOLS_UNAVAILABLE_MESSAGE = "Tools unavailable for this model."
        const val FINAL_RESPONSE_NOTICE = "Tool-call limit is approaching; generating a final response."
        const val ROUND_LIMIT_FINAL_RESPONSE_NOTICE =
            "Agent work-round limit reached; generating a final response with the results already available."
        const val ROUND_LIMIT_FINAL_RESPONSE_INSTRUCTION =
            "The model/tool work-round allowance is exhausted. Do not request more tools. " +
                "Use the available findings to answer now. If additional tool work is required, " +
                "briefly state what remains and ask the user to reply exactly \"continue\"."
        const val COMBINED_BUDGET_FINAL_RESPONSE_INSTRUCTION =
            "The shared tool-call and tool-result byte budgets are exhausted for this response. Do not request more tools. " +
                "Use every successful result already returned, including completed delegated research, to answer the user's request now. " +
                "Do not claim research failed merely because the shared budgets are exhausted. " +
                "Ask the user to continue only if essential evidence is still missing."
        const val OUTPUT_BUDGET_FINAL_RESPONSE_INSTRUCTION =
            "The tool-result byte budget is exhausted for this response. Do not request more tool output. " +
                "Use every successful result already returned, including completed delegated research, to answer the user's request now. " +
                "Do not claim research failed merely because additional result bytes cannot be collected. " +
                "Ask the user to continue only if essential evidence is still missing."
        const val FINAL_RESPONSE_INSTRUCTION =
            "The tool-call allowance is exhausted for this response. Do not request more tools. " +
                "Use every successful result already returned, including completed delegated research, to answer the user's request now. " +
                "Do not claim research failed merely because no further calls are available. " +
                "Ask the user to continue only if essential evidence is still missing."
    }
}
