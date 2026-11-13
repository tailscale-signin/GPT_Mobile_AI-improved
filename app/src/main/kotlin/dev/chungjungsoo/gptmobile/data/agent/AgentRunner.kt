package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
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
    /** GitHub workspace execution always exposes the complete authorized GitHub catalog. */
    val githubMode: Boolean = false,
    /** Optional objective for reading a saved batch; this is not an end-to-end recovery target. */
    val checkpointRestoreObjective: CheckpointRestoreObjective? = null,
    val combined: CombinedExecutionContext? = null
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
    private val replayDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val replayRenderCache = ToolExchangeCompactor.RenderCache()
    private val replayCompactionLogGate = ReplayCompactionLogGate()

    /** Serialization, hashing, and replay projection can scan megabytes of evidence. */
    private suspend fun prepareReplay(
        exchanges: List<AgentToolExchange>,
        maxReplayTokens: Int,
        maxResultTokens: Int,
        consumedCallIds: Set<String> = emptySet()
    ): ReplayProjection = withContext(replayDispatcher) {
        val rawTokens = ToolExchangeCompactor.estimateTokens(exchanges, replayRenderCache)
        val started = System.nanoTime()
        val compacted = ToolExchangeCompactor.compact(
            exchanges,
            maxReplayTokens,
            if (rawTokens <= maxReplayTokens) maxReplayTokens else maxResultTokens,
            consumedCallIds,
            replayRenderCache
        )
        ReplayProjection(
            exchanges = compacted,
            rawTokens = rawTokens,
            compactedTokens = ToolExchangeCompactor.estimateTokens(compacted, replayRenderCache),
            compactionMicros = ((System.nanoTime() - started) / 1000).coerceAtLeast(0)
        )
    }

    fun run(session: AgentProviderSession, tools: List<AgentTool>, dynamicTools: () -> List<AgentTool> = { emptyList() }, selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition> = { it }): Flow<AgentRunEvent> = flow {
        emitAll(runWithRepairScope(session, tools, dynamicTools, selectDefinitions).flowOn(ToolArgumentRepairScope()))
    }

    private fun runWithRepairScope(session: AgentProviderSession, tools: List<AgentTool>, dynamicTools: () -> List<AgentTool>, selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>): Flow<AgentRunEvent> = flow {
        if (minOf(limits.runTimeoutMillis, remainingTaskMillis?.invoke() ?: Long.MAX_VALUE) <= 0L) {
            // An expired accepted task may deliver retained evidence, but must not
            // restore queued calls or start the model loop. Delivery remains
            // cancellable and is not charged a fabricated five-second work budget.
            if (limits.githubTask) {
                deliverExpiredTask(session)
            } else {
                emit(
                    AgentRunEvent.Provider(
                        ProviderEvent.Failed(
                            "The accepted task time budget has ended. Completed work is saved; increase the budget before continuing.",
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
        if (tools.isNotEmpty() && !session.handlesToolsInternally && limits.maxReplayTokens <= 0) {
            emit(
                AgentRunEvent.Provider(
                    ProviderEvent.Failed(
                        "No actionable tool receipt fits the replay budget. Reallocate stage output or narrow schemas before starting tool work.",
                        errorCode = "REPLAY_EVIDENCE_REQUIRED",
                        failureDetails = kotlinx.serialization.json.buildJsonObject {
                            put("failed_stage", "planning")
                            put("dispatched", false)
                        }
                    )
                )
            )
            return@flow
        }
        if (tools.groupBy { it.definition.name }.any { it.value.size > 1 }) {
            emit(failed("Ambiguous tool aliases: resolve duplicate model-facing names before execution."))
            return@flow
        }
        val guardedSession = session.withGenerationSafety(GenerationSafety(limits.maxGeneratedCharacters, limits.detectRepetition))
        val toolByName = tools.associateBy { it.definition.name }
        var executableToolByName = toolByName
        var exposedDefinitions = tools.map { it.definition }
        val exchanges = mutableListOf<AgentToolExchange>()
        var rounds = 0
        if (retryUndispatchedBudgetFailures) checkpoint?.prepareExplicitBudgetRetry()
        val recovery = checkpoint?.recoverySnapshot(limits.checkpointRestoreObjective)
        // Reopening an undispatched refusal does not renew the accepted allowance.
        // Only a separate explicit grant may start a new bounded tranche.
        var toolCallCount = initialToolCallCountOverride?.coerceAtLeast(0) ?: if (resumeToolBudget) 0 else recovery?.dispatchedCount ?: 0
        var toolMayHaveExecuted = (recovery?.dispatchedCount ?: 0) > 0
        var retriedWithoutTools = false

        val acceptedRemainingMillis = remainingTaskMillis?.invoke()
        val runTimeoutMillis = minOf(limits.runTimeoutMillis, acceptedRemainingMillis ?: Long.MAX_VALUE)
        if (limits.githubTask && runTimeoutMillis <= 0L) {
            deliverExpiredTask(session)
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
                deliverExpiredTask(session)
            } else if (limits.githubTask && exchanges.isNotEmpty()) {
                finishFromRetained(guardedSession, ProviderEvent.Failed("Agent run timed out after $runTimeoutMillis ms. Completed receipts are retained; unfinished work can be resumed.", errorCode = "RUN_DEADLINE_REACHED"), true)
            } else {
                emit(failed("Agent run timed out after $runTimeoutMillis ms."))
            }
        }
    }.withGitHubTerminalDelivery(limits.githubTask && limits.combined == null)
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
        val initialSchemaTokens = selectedForRound(initialExposedDefinitions, selectDefinitions).sumOf { providerSchemaTokens(it, limits.githubMode) }
        val retainedUnknownOutcome = recovery?.unsettledCalls.orEmpty().any { !replayableRead(it, toolByName[it.name]) }
        val state = LoopState(initialExecutableToolByName, initialExposedDefinitions, initialRounds, initialToolCallCount, initialToolMayHaveExecuted, initialRetriedWithoutTools, recovery, retainedUnknownOutcome)
        if (state.restoredCalls.isNotEmpty() && recovery != null) {
            AppLogRecorder.record("Recovery", "TOOL_BATCH_RESTORED · checkpointVersion=1 · pendingCalls=${state.restoredCalls.size} · previouslyDispatched=${state.restoredDispatched.size} · completed=${recovery.completedCount} · failed=${recovery.failedCount} · uncertain=${recovery.unknownCount} · queued=${recovery.queuedCount} · rejected=${recovery.rejectedCount} · snapshotLoadMs=${recovery.loadMillis} · objectiveMs=${recovery.objectiveMillis ?: "none"} · objectiveStatus=${recovery.objectiveStatus} · measurement=checkpoint_load_only")
        }
        val executionToolCallLimit = ToolBudgetPolicy.executionLimit(limits)
        if (!restoreCompletedExchanges(recovery, toolByName, exchanges, state)) return
        state.replayTokens = prepareReplay(exchanges, limits.maxReplayTokens, limits.maxReplayResultTokens).compactedTokens.toLong()

        with(state) {
            while (true) {
                if (!prepareNextRound(session, exchanges, state, executionToolCallLimit)) return

                // collectRound catches only upstream provider failures. Cancellation and
                // downstream collector errors must leave the run without retrying or emitting.
                val searchContext = searchRoundContext(toolByName, executableToolByName, exchanges)
                val observedLimit = if (limits.combined == null) limits.maxObservedToolCalls.coerceIn(1, 1024) else limits.maxObservedToolCalls.coerceAtLeast(0)
                val roundDefinitions = try {
                    fitProviderToolSchemas(selectedForRound(exposedDefinitions, selectDefinitions), limits, initialSchemaTokens, replayTokens)
                } catch (capacity: GitHubToolContextBudgetException) {
                    emit(AgentRunEvent.Provider(capacity.providerFailure()))
                    return
                }
                reconcileDiscoveryExchange(exchanges, roundDefinitions)
                val restoringBatch = restoredCalls.isNotEmpty()
                val automaticRecovery = if (limits.githubTask && !session.handlesToolResultRecovery) savedResultRecovery else null
                val remoteRead = if (!session.handlesToolResultRecovery &&
                    !session.handlesToolsInternally &&
                    !retainedUnknownOutcome &&
                    unknownOutcomes.isEmpty() &&
                    limits.combined?.requested?.singlePass != true &&
                    !finalResponseRequested &&
                    !restoringBatch &&
                    observedToolCalls < observedLimit &&
                    roundDefinitions.any { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.operationName(it.name) == "github_ci" }
                ) {
                    GitHubReplayRecovery.nextRemoteRead(exchanges, roundDefinitions, savedResultRecovery)
                } else {
                    null
                }
                val savedRead = if (automaticRecovery != null && !finalResponseRequested && !restoringBatch && observedToolCalls < observedLimit) GitHubReplayRecovery.nextSavedRead(exchanges, roundDefinitions, minOf(limits.maxReplayResultTokens, limits.maxReplayTokens / 2), automaticRecovery) else null
                val round = if (restoringBatch) {
                    RoundResult(calls = restoredCalls, completed = true, observedCalls = restoredCalls.size).also { restoredCalls = emptyList() }
                } else if (remoteRead != null) {
                    emit(AgentRunEvent.Notice("Reading the next workflow page from the issued continuation.", persistent = false))
                    RoundResult(calls = listOf(remoteRead), completed = true, observedCalls = 1)
                } else if (savedRead != null) {
                    emit(AgentRunEvent.Notice("Reading the saved GitHub result; the remote operation is not being repeated.", persistent = false))
                    RoundResult(calls = listOf(savedRead), completed = true, observedCalls = 1)
                } else {
                    collectRound(session, roundDefinitions, exchanges, (observedLimit - observedToolCalls).coerceAtLeast(0), searchContext.successful, searchContext.names, consumedCallIds, retainedUnknownOutcome || unknownOutcomes.isNotEmpty())
                }
                observedToolCalls += round.observedCalls
                if (round.batchRejected) {
                    if (limits.combined?.requested?.singlePass == true) {
                        emit(failed(round.failure ?: "Tool batch rejected. Retry this profile explicitly; no calls from this batch were executed."))
                        return
                    }
                    val recovery = recoverRejectedBatch(session, round, exposedDefinitions, observedToolCalls, observedLimit, rounds, batchRepairAttempted, stormFinalizationAttempted, finalResponseRequested) ?: return
                    exposedDefinitions = recovery.definitions
                    if (recovery.repair) {
                        batchRepairAttempted = true
                    } else {
                        stormFinalizationAttempted = true
                        executableToolByName = emptyMap()
                        finalResponseRequested = true
                    }
                    continue
                }
                if (round.toolDefinitionsRejected) {
                    if (limits.githubMode) {
                        emit(AgentRunEvent.Provider(ProviderEvent.Failed(round.failure ?: "This model rejected the required GitHub tool catalog.", errorCode = "GITHUB_TOOL_CONTRACT_UNSUPPORTED", failureDetails = round.failureDetails, invocationId = round.invocationId)))
                        return
                    }
                    if (limits.combined?.requested?.singlePass != true && exposedDefinitions.isNotEmpty() && !toolMayHaveExecuted && !retriedWithoutTools && !round.textEmitted) {
                        retriedWithoutTools = true
                        exposedDefinitions = emptyList()
                        executableToolByName = emptyMap()
                        rounds -= 1
                        emit(AgentRunEvent.Notice(TOOLS_UNAVAILABLE_MESSAGE, persistent = true))
                        continue
                    }
                    emit(failed(round.failure ?: "Tools are unavailable for this model."))
                    return
                }
                if (round.failure != null) {
                    val recovery = recoverFailedRound(round, session, exposedDefinitions, finalResponseRequested, exchanges.isNotEmpty() || round.calls.isNotEmpty(), retainedUnknownOutcome || unknownOutcomes.isNotEmpty(), recoveryState) ?: return
                    exposedDefinitions = recovery.definitions
                    if (recovery.restoreRound) rounds--
                    if (recovery.finalize) {
                        executableToolByName = emptyMap()
                        finalResponseRequested = true
                        instructFinalResponse(exchanges, consumedCallIds)
                    }
                    continue
                }
                // A provider may ignore an empty tool catalog. Offer one correction,
                // then stop instead of spending the observed-call ceiling on refusals.
                if (finalResponseRequested && exposedDefinitions.isEmpty() && round.calls.isNotEmpty()) {
                    if (finalizationToolRepairAttempted) {
                        finishFromRetained(session, ProviderEvent.Failed("The provider kept requesting tools after finalization. Completed results are retained; no additional action was dispatched.", errorCode = "FINAL_RESPONSE_REQUIRED"), exchanges.isNotEmpty())
                        return
                    }
                    finalizationToolRepairAttempted = true
                }
                val resolvedCalls = round.calls.map { call ->
                    // Stamp live calls at the trusted routing boundary; restored journals retain their identity.
                    val identified = if (restoringBatch) call else call.captureProviderInvocation()
                    val resolved = resolveGitHubReadAlias(GitHubRecoveryCalls.resolve(identified, exchanges, executableToolByName), executableToolByName.keys)
                    resolved.copy(arguments = dev.chungjungsoo.gptmobile.data.github.GitHubActionIdentity.canonicalArguments(resolved.name, resolved.arguments))
                }
                resolvedCalls.filterNot { it.callId in restoredCompleted }.forEach { emit(AgentRunEvent.Provider(it)) }
                val calls = routeSearchCalls(resolvedCalls, executableToolByName)
                savedResultRecovery.record(calls)
                if (calls.isEmpty()) {
                    val recovery = recoverRoundWithoutCalls(session, round, exchanges, consumedCallIds, exposedDefinitions, emptyAnswerRecoveryAttempted, finalResponseRequested) ?: return
                    exposedDefinitions = recovery.definitions
                    emptyAnswerRecoveryAttempted = recovery.emptyAnswerRecoveryAttempted
                    finalResponseRequested = recovery.finalResponseRequested
                    rounds += recovery.roundAdjustment
                    if (finalResponseRequested) executableToolByName = emptyMap()
                    continue
                }
                val roundLimitReached = limits.maxRounds < Int.MAX_VALUE && rounds >= limits.maxRounds

                val remainingCalls = if (executionToolCallLimit == Int.MAX_VALUE) {
                    calls.size
                } else {
                    ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount)
                }
                val completed = processToolCalls(
                    calls, toolByName, executableToolByName, exposedDefinitions, exchanges, dynamicTools, selectDefinitions,
                    initialSchemaTokens, replayTokens, toolCallsByName, consecutiveToolFailures, blockedReadScopes,
                    remainingCalls, toolCallCount, restoredDispatched, restoredRetained, restoredCompleted, roundLimitReached, finalResponseRequested,
                    retriedWithoutTools, executionToolCallLimit, suppressedReadRounds, unproductiveDiscoveryRounds, unknownOutcomes, retainedUnknownOutcome, automaticRecovery
                ) ?: return
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
                val stalled = if (limits.githubTask) exchanges.lastOrNull()?.let(investigation::observe) else null
                if ((unsuccessfulGitHubRounds >= 3 || stalled != null) && !finalResponseRequested && limits.combined?.requested?.singlePass != true) {
                    finalResponseRequested = true
                    exposedDefinitions = emptyList()
                    executableToolByName = emptyMap()
                    instructFinalResponse(exchanges, consumedCallIds)
                    emit(AgentRunEvent.Notice(stalled ?: "GitHub operations failed for three consecutive rounds. Finishing with saved results and the remaining blockers.", persistent = false))
                }
            }
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
        val retainedUnknownOutcome: Boolean
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
        var observedToolCalls = 0
        var stormFinalizationAttempted = false
        var batchRepairAttempted = false
        var unproductiveDiscoveryRounds = 0
        var suppressedReadRounds = 0
        var unsuccessfulGitHubRounds = 0
        val investigation = GitHubInvestigationProgress()
        var finalizationToolRepairAttempted = false
        var restoredCalls = recovery?.pending.orEmpty()
        var restoredDispatched = recovery?.pendingDispatchedIds.orEmpty()
        var restoredRetained = recovery?.pendingRetainedIds.orEmpty()
        var restoredCompleted = emptyMap<String, AgentToolResult>()
        val toolCallsByName = mutableMapOf<String, Int>()
        val consecutiveToolFailures = mutableMapOf<String, Int>()
        val blockedReadScopes = mutableSetOf<String>()
        val unknownOutcomes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val consumedCallIds = recovery?.consumedCallIds.orEmpty().toMutableSet()
        val savedResultRecovery = GitHubReplayRecovery.State(recovery?.completedExchanges.orEmpty().flatMap { it.calls } + recovery?.pending.orEmpty())
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
            if (limits.githubTask) {
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
                            unknownOutcomes += call.name + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(call.arguments)
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
                        unknownOutcomes += call.name + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(call.arguments)
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
        emptyAnswerRecoveryAttempted: Boolean,
        finalResponseRequested: Boolean
    ): EmptyRoundRecovery? {
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
                if (event is ProviderEvent.Completed) checkpoint?.finish()
                emit(AgentRunEvent.Provider(event))
            }
        } else {
            emit(AgentRunEvent.Provider(failure))
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.deliverExpiredTask(session: AgentProviderSession) {
        // A diagnostic reply may complete, but it cannot finish the original
        // execution checkpoint or discard its outstanding cleanup obligations.
        session.retainedFailureResponse(dev.chungjungsoo.gptmobile.data.github.GitHubExecutionLimits.expiredTaskFailure()).collect {
            emit(AgentRunEvent.Provider(it))
        }
    }

    private data class RoundRecovery(val definitions: List<AgentToolDefinition>, val restoreRound: Boolean, val finalize: Boolean)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverFailedRound(
        round: RoundResult,
        session: AgentProviderSession,
        definitions: List<AgentToolDefinition>,
        finalResponseRequested: Boolean,
        hasToolWork: Boolean,
        hasUnknownOutcome: Boolean,
        state: RoundRecoveryState
    ): RoundRecovery? {
        val roundFailure = requireNotNull(round.failure)
        if (GatewayOutcomeRecovery.requiresReconciliation(roundFailure, round.errorCode)) {
            // Even a tool-free repair can reach a gateway with server tools enabled.
            // Leave the terminal delivery boundary to report the retained failure without another model request.
            emit(AgentRunEvent.Provider(ProviderEvent.Failed(roundFailure, round.progressDeadline, GatewayOutcomeRecovery.ERROR_CODE, round.failureTransportComplete, round.providerLimit, round.taskOutcome, round.failureDetails, round.invocationId)))
            return null
        }
        if (round.errorCode in ToolFailureMetadata.authorityInvalidationCodes || round.taskOutcome == "CONFIGURATION_INVALIDATED") {
            val details = if (hasUnknownOutcome) {
                JsonObject(
                    round.failureDetails.orEmpty() - "dispatched" + kotlinx.serialization.json.buildJsonObject {
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
                            round.failureDetails.orEmpty() + kotlinx.serialization.json.buildJsonObject {
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
            // its receipt and acceptance delivery even when continuation is forbidden.
            if (limits.githubTask && round.calls.isEmpty()) {
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
            emit(AgentRunEvent.Provider(ProviderEvent.Failed(roundFailure, round.progressDeadline, round.errorCode, round.failureTransportComplete, round.providerLimit, round.taskOutcome, round.failureDetails, round.invocationId)))
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
            val failure = ProviderEvent.Failed(roundFailure, round.progressDeadline, errorCode = round.errorCode ?: "TOOL_PROTOCOL_INVALID_FRAME", failureDetails = round.failureDetails, invocationId = round.invocationId)
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
        exchanges: MutableList<AgentToolExchange>,
        dynamicTools: () -> List<AgentTool>,
        selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>,
        initialSchemaTokens: Long,
        replayTokens: Long,
        toolCallsByName: MutableMap<String, Int>,
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
        unknownOutcomes: MutableSet<String>,
        retainedUnknownOutcome: Boolean,
        savedResultRecovery: GitHubReplayRecovery.State?
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
        val toolsForBatch = if (decisionCalls.isNotEmpty()) executableToolByName.filterKeys { it in questionNames } else executableToolByName
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
        val admission = planToolBatch(calls, batchAllowance, toolsForBatch.keys, toolCallsByName, blockedReadScopes, if (limits.githubTask) limits.maxToolCalls else MAX_SAME_TOOL_CALLS_PER_RUN, restoredRetained, restoredCompleted.keys, ::failureScope)
        val plan = dependencyClosedPlan(admission, dependencyPlan, restoredRetained)
        val executableCalls = plan.executable
        val deferredCalls = plan.deferred
        val perToolSuppressedIds = plan.perToolSuppressedIds
        val hostSuppressedIds = plan.hostSuppressedIds
        plan.acceptedByName.forEach { (name, count) -> toolCallsByName[name] = (toolCallsByName[name] ?: 0) + count }
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
        val deferredResults = deferredToolResults(deferredCalls, hostSuppressedIds, perToolSuppressedIds, plan.unavailableIds, executableToolByName.keys, exchanges, pausedGitHubNames).map { result ->
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
                result.copy(
                    executionReceipt = JsonObject(
                        result.executionReceipt.orEmpty() + buildJsonObject {
                            put("failed_stage", "tool_precheck")
                            put("dependency_admission_reasons", JsonArray(listOf(JsonPrimitive(admissionReason(result.callId).name))))
                        }
                    )
                )
            } else {
                result
            }
        }
        deferredCalls.zip(deferredResults).forEach { (call, result) -> checkpoint?.complete(call, result) }
        val savedReadAvailable = savedResultRecovery != null &&
            GitHubReplayRecovery.nextSavedRead(
                exchanges + AgentToolExchange(calls, executedResults + deferredResults),
                exposedDefinitions.filter { it.name in executableToolByName },
                minOf(limits.maxReplayResultTokens, limits.maxReplayTokens / 2),
                savedResultRecovery
            ) != null
        val canReadOtherSources = savedReadAvailable ||
            (plan.unavailableIds.isNotEmpty() && executableToolByName.isNotEmpty()) ||
            hostSuppressedIds.isNotEmpty() ||
            deferredCalls.any { it.callId in perToolSuppressedIds && it.name in executableToolByName }
        // Give a scoped read one round to choose another source, then stop
        // paying for provider rounds that only repeat already-suppressed work.
        suppressedReadRounds = if (executableCalls.isEmpty() && canReadOtherSources) suppressedReadRounds + 1 else 0
        val completedBatch = finishBatch(
            calls, executedResults + deferredResults, exchanges, executableCalls.isEmpty(),
            canReadOtherSources, suppressedReadRounds >= 2 && !savedReadAvailable,
            roundLimitReached, toolCallCount, executionToolCallLimit,
            unproductiveDiscoveryRounds, finalResponseRequested, executableCalls.mapTo(hashSetOf()) { it.callId },
            executableToolByName.values.any { it.definition.modelVisible && ProgressiveToolCatalog.isGitHubTool(it.definition) }
        )
        finalResponseRequested = completedBatch.finalResponseRequested
        if (finalResponseRequested) {
            exposedDefinitions = emptyList()
            executableToolByName = emptyMap()
        } else if (completedBatch.suppressDiscovery) {
            // Stop the repeated directory operation while retaining useful authorized work.
            exposedDefinitions = exposedDefinitions.filterNot { it.name == ProgressiveToolCatalog.NAME }
            executableToolByName = executableToolByName - ProgressiveToolCatalog.NAME
        }
        return ToolRoundCompletion(executableToolByName, exposedDefinitions, toolCallCount, executedResults.any { it.dispatched }, suppressedReadRounds, completedBatch)
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.completeTool(call: ProviderEvent.ToolCall, result: AgentToolResult, invalidatedAuthority: AtomicReference<AgentToolResult?>): AgentToolResult {
        checkpoint?.complete(call, result)
        healthCatalog?.recordObservation(call.name, result)
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

    private fun deferredToolResults(calls: List<ProviderEvent.ToolCall>, hostSuppressedIds: Set<String>, perToolSuppressedIds: Set<String>, unavailableIds: Set<String>, availableNames: Set<String>, exchanges: List<AgentToolExchange>, pausedGitHubNames: Set<String> = emptySet()): List<AgentToolResult> = calls.map { call ->
        if (call.callId in unavailableIds) {
            if (call.name in pausedGitHubNames) {
                return@map ToolResultEnvelope.error(
                    call.callId,
                    "TOOL_EXECUTION_PAUSED",
                    "This authorized GitHub tool remains visible, but execution is paused for this response after repeated failures or a repeat limit. Nothing was dispatched. Use the retained error's correction or another authorized GitHub operation; resume the paused operation after its blocker is resolved."
                )
            }
            return@map AgentToolResult(
                call.callId,
                ToolResultContent.Text("The requested tool '${call.name}' is not available in this round. Nothing was dispatched. Use an exact advertised tool name and its schema. Available tools: " + availableNames.sorted().take(32).joinToString(", ").ifEmpty { "none; answer using the evidence already collected" }),
                isError = true,
                dispatched = false,
                errorCode = "TOOL_NOT_AVAILABLE"
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
            emit(failed("TOOL_BATCH_OVERSIZE: partial answer and completed evidence retained. The provider did not honor tool-free finalization."))
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

    private fun selectedForRound(definitions: List<AgentToolDefinition>, selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>): List<AgentToolDefinition> {
        val selected = selectDefinitions(definitions)
        if (!limits.githubMode) return selected
        val authorized = definitions.filter { it.modelVisible }.associateBy { it.name }
        return (authorized.values.filter { ProgressiveToolCatalog.isGitHubTool(it) } + selected.mapNotNull { authorized[it.name] }).distinctBy { it.name }
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
            emit(failed("Ambiguous discovered tool aliases; no discovered action dispatched."))
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

    private data class BatchCompletion(val replayTokens: Long, val discoveryRounds: Int, val finalResponseRequested: Boolean, val hasSuccessfulResult: Boolean, val suppressDiscovery: Boolean)

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
        publishedCallIds: Set<String> = emptySet(),
        hasActionableGitHubTools: Boolean = false
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
            maxReplayTokens = limits.maxReplayTokens,
            maxResultTokens = limits.maxReplayResultTokens
        ).compactedTokens.toLong()
        val contextNearLimit = limits.contextTokens != Int.MAX_VALUE &&
            limits.initialContextTokens.toLong() + replayTokens + limits.finalResponseReserveTokens + 256 >= limits.contextTokens
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
                contextNearLimit -> appendInstruction(allResults.last(), "The context limit is approaching. Use the available findings to give a final response now and ask whether the user wants to continue. Do not call more tools.")
                outputBudgetExhausted && toolCallBudgetExhausted ->
                    appendInstruction(allResults.last(), COMBINED_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                outputBudgetExhausted -> appendInstruction(allResults.last(), OUTPUT_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                else -> appendFinalResponseInstruction(allResults.last())
            }
        } else if (repeatedReadSuppression && allResults.isNotEmpty()) {
            allResults[allResults.lastIndex] = appendInstruction(allResults.last(), "This exact read produced no new evidence and remains suppressed. Follow its issued remote continuation, or use a different required action or unread range. Other authorized tools remain available within the current task budget. Workflow definitions do not establish executed checks or repository health.")
            emit(AgentRunEvent.Notice("Repeated reads were skipped. Continuing with remaining checks and unread pages.", persistent = false))
        } else if (suppressDiscovery && allResults.isNotEmpty()) {
            allResults[allResults.lastIndex] = appendInstruction(allResults.last(), "Repeated discovery returned no new schemas. Discovery is paused for this response. The attached authorized GitHub tools remain available; use their exact schemas to continue the requested work. Do not repeat discovery or claim that GitHub tools are unavailable.")
            emit(AgentRunEvent.Notice("Repeated discovery is paused. Continuing with the available GitHub tools.", persistent = false))
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
            limits.maxReplayResultTokens.coerceIn(64, 2048)
        ).exchanges
        exchanges.clear()
        exchanges.addAll(retained)
        return BatchCompletion(replayTokens, unproductiveDiscoveryRounds, finalResponseRequested, allResults.any { !it.isError }, suppressDiscovery)
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
        val invocationId: String? = null
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
        hasUnknownOutcome: Boolean
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
            if (exchanges.isNotEmpty() && limits.maxReplayTokens <= 0) {
                emit(
                    ProviderEvent.Failed(
                        "No actionable tool receipt fits this request. Lower the stage output reservation or narrow the schemas before continuing; saved evidence was retained.",
                        errorCode = "REPLAY_EVIDENCE_REQUIRED",
                        failureDetails = kotlinx.serialization.json.buildJsonObject {
                            put("failed_stage", "planning")
                            put("dispatched", false)
                        }
                    )
                )
                return@flow
            }
            val replay = prepareReplay(
                exchanges = exchanges,
                maxReplayTokens = limits.maxReplayTokens,
                maxResultTokens = limits.maxReplayResultTokens,
                consumedCallIds = consumedCallIds
            )
            val rawReplayTokens = replay.rawTokens
            val replayExchanges = replay.exchanges
            val originalResults = exchanges.flatMap { it.results }.associateBy { it.callId }
            deliveredCallIds = replayExchanges.flatMap { it.results }.filter { originalResults[it.callId]?.content == it.content }.mapTo(mutableSetOf()) { it.callId }
            val compactedReplayTokens = replay.compactedTokens
            val compactionMicros = replay.compactionMicros
            if (rawReplayTokens > compactedReplayTokens && replayCompactionLogGate.shouldLog(rawReplayTokens, exchanges.size, compactionMicros)) {
                AppLogRecorder.record(
                    "Agent",
                    "PRIMARY_REPLAY_COMPACTED · rawTokens=$rawReplayTokens · replayTokens=$compactedReplayTokens · savedTokens=${rawReplayTokens - compactedReplayTokens} · exchanges=${exchanges.size} · budget=${limits.maxReplayTokens} · compactionUs=$compactionMicros · tokenCounts=ESTIMATED · retainedEvidenceIndependent=true · logPolicy=EXPONENTIAL_HIGH_WATER"
                )
            }
            if (compactedReplayTokens > limits.maxReplayTokens) {
                AppLogRecorder.record(
                    "Agent",
                    "PRIMARY_REPLAY_BUDGET_OVERRUN · replayTokens=$compactedReplayTokens · budget=${limits.maxReplayTokens} · exchanges=${exchanges.size}",
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
                            put("retryable", false)
                            put("replay_tokens", compactedReplayTokens)
                            put("replay_allowance", limits.maxReplayTokens)
                        }
                    )
                )
                return@flow
            }
            // Include synchronous stream construction failures in the upstream boundary.
            val preparedExchanges = replayExchanges.map { exchange -> exchange.copy(results = exchange.results.map { dev.chungjungsoo.gptmobile.data.assist.OptionalAssistArtifacts.ready(it) }) }
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
                emitAll(session.streamRound(exposedDefinitions, preparedExchanges).withAssistantTextMarkupGuard(successfulSearch || session.hasPreparedSearchEvidence, searchToolNames, allowControlJsonText = session.allowsControlJsonText))
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
                val typedRejection = AgentFailureEvidence.exception(error, providerPayloadObserved)
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
                        put(
                            "cause_types",
                            kotlinx.serialization.json.JsonArray(
                                generateSequence(error) { cause -> cause.cause?.takeUnless { it === cause } }
                                    .take(16).map { JsonPrimitive(it.javaClass.name) }.toList()
                            )
                        )
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
            if (event is ProviderEvent.Failed && GatewayOutcomeRecovery.requiresReconciliation(event.message, event.errorCode)) {
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
                    failureDetails = event.failureDetails
                    failureInvocationId = event.invocationId
                    providerLimit = event.providerLimit
                }

                is ProviderEvent.Notice -> emit(AgentRunEvent.Notice(event.message, event.persistent, event.researchDelivery))

                is ProviderEvent.Usage -> {
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
                    emit(AgentRunEvent.Provider(event))
                }
            }
        }
        // Do not emit from finally: a cancelled or failed consumer cannot accept
        // another event. Genuine provider failures still retain observed usage.
        emitRoundUsage()
        if (completed && failure == null && deliveredCallIds.any { it !in consumedCallIds }) {
            checkpoint?.markConsumed(deliveredCallIds)
            consumedCallIds += deliveredCallIds
        }
        return RoundResult(calls, completed, failure, textEmitted, toolDefinitionsRejected, batchRejected, maxOf(admission.observed, parserObservedCalls), progressDeadline, batchRejection, failureCode, failureTransportComplete, providerLimit, taskOutcome, failureDetails, failureInvocationId)
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
        unknownOutcomes: MutableSet<String>,
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
        return IntelligentToolBatch(AdaptiveBatchWindow(limits.maxConcurrentTools)).execute(
            nodes,
            dispatch = { call ->
                if (call.callId in blocked) {
                    val dependencies = nodesById.getValue(call.callId).failureDependencies
                    return@execute ToolDependencyDiagnostics.rejection(call.callId, ToolDependencyDiagnostics.State.DEFERRED, dependencies, dependencies - admittedIds, "A prerequisite was deferred by admission. This operation was not dispatched; replan after the missing read completes.")
                }
                val signature = call.name + ":" + dev.chungjungsoo.gptmobile.data.permissions.ScopedToolGrant.canonicalHash(call.arguments)
                suspend fun dispatch(): AgentToolResult = if (!replayableRead(call, tools[call.name]) && synchronized(unknownOutcomes) { signature in unknownOutcomes }) {
                    ToolResultEnvelope.error(call.callId, "RECONCILIATION_REQUIRED", "An earlier identical operation has an unknown outcome. Reconcile it before requesting another write.")
                } else {
                    invalidatedAuthority.get()?.let { ToolFailureMetadata.authorityBlocked(call.callId, call.name, it) }
                        ?: executeBounded(call, tools[call.name]).also { if (ToolFailureMetadata.authorityCode(it) != null) invalidatedAuthority.compareAndSet(null, it) }
                }
                val interaction = AgentToolCapability.USER_DECISION in tools[call.name]?.definition?.capabilities.orEmpty()
                val result = if (interaction) dispatch().also { checkpoint?.complete(call, it) } else checkpoint?.execute(call, replayableRead = replayableRead(call, tools[call.name])) { dispatch() } ?: dispatch()
                if (ToolFailureMetadata.authorityCode(result) != null) invalidatedAuthority.compareAndSet(null, result)
                if (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead(call, tools[call.name])) synchronized(unknownOutcomes) { unknownOutcomes += signature }
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
        unknownOutcomes: MutableSet<String>,
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
                                            val result = if (!replayableRead(call, tools[call.name]) && synchronized(unknownOutcomes) { signature in unknownOutcomes }) {
                                                ToolResultEnvelope.error(call.callId, "RECONCILIATION_REQUIRED", "An earlier identical operation has an unknown outcome. Reconcile it before requesting another write.")
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
                                        if (result.errorCode in ToolResultEnvelope.unknownOutcomeCodes && !replayableRead(call, tools[call.name])) synchronized(unknownOutcomes) { unknownOutcomes += signature }
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
        AgentContractValidation.admission(tool.definition, call.callId)?.let { return it }
        if (!tool.managesExecutionBudget) {
            val errors = ToolArgumentValidator.errors(call.arguments, tool.definition.inputSchema)
            if (errors.isNotEmpty()) return requireNotNull(kotlinx.coroutines.currentCoroutineContext()[ToolArgumentRepairScope]).budget.rejection(call.callId, tool.definition, call.arguments, errors)
        }
        val replayableRead = replayableRead(call, tool)
        var observedReceipt: AgentToolResult? = null
        return try {
            // A delegated child can have different data permissions. Capture only
            // this call's authorized receipt, never its private intermediate calls.
            val result = kotlinx.coroutines.withContext(
                ToolReceiptObserver { observedCall, receipt ->
                    if (observedCall.callId == call.callId && observedCall.name == call.name && observedCall.arguments == call.arguments) {
                        observedReceipt = ToolFailureMetadata.receipt(AgentContractValidation.receipt(call.callId, receipt), call.name, replaySafe = replayableRead)
                        onToolResult?.invoke(call, receipt)
                    }
                }
            ) {
                if (!tool.managesExecutionBudget && limits.toolTimeoutMillis < Long.MAX_VALUE) {
                    withTimeoutOrNull(limits.toolTimeoutMillis) {
                        tool.execute(call.callId, call.arguments)
                    } ?: AgentToolResult(
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

    private fun failed(message: String) = AgentRunEvent.Provider(ProviderEvent.Failed(message))

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
