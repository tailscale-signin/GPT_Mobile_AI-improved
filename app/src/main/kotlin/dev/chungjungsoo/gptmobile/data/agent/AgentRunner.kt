package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

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
    val combined: CombinedExecutionContext? = null
) {
    companion object {
        const val DEFAULT_MAX_TOOL_CALLS: Int = 50

        fun defaultMaxConcurrentTools(): Int = 6

        fun defaultMaxToolOutputBytes(): Int = 256 * 1024
    }
}

class AgentRunner(
    val limits: AgentRunLimits = AgentRunLimits(),
    private val checkpoint: ToolBatchCheckpoint? = null,
    private val healthCatalog: ProgressiveToolCatalog? = null
) {
    fun run(session: AgentProviderSession, tools: List<AgentTool>, dynamicTools: () -> List<AgentTool> = { emptyList() }, selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition> = { it }): Flow<AgentRunEvent> = flow {
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
        var toolCallCount = checkpoint?.dispatchedCount() ?: 0
        var toolMayHaveExecuted = false
        var retriedWithoutTools = false

        val finishedInTime = if (limits.runTimeoutMillis < Long.MAX_VALUE) {
            withTimeoutOrNull(limits.runTimeoutMillis) {
                executeLoop(guardedSession, toolByName, executableToolByName, exposedDefinitions, exchanges, rounds, toolCallCount, toolMayHaveExecuted, retriedWithoutTools, dynamicTools, selectDefinitions)
            }
        } else {
            executeLoop(guardedSession, toolByName, executableToolByName, exposedDefinitions, exchanges, rounds, toolCallCount, toolMayHaveExecuted, retriedWithoutTools, dynamicTools, selectDefinitions)
            true
        }

        if (finishedInTime == null) {
            emit(failed("Agent run timed out after ${limits.runTimeoutMillis} ms."))
        }
    }.flowOn(limits.combined?.let(::CombinedRunScope) ?: kotlin.coroutines.EmptyCoroutineContext)

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
        dynamicTools: () -> List<AgentTool>,
        selectDefinitions: (List<AgentToolDefinition>) -> List<AgentToolDefinition>
    ) {
        var executableToolByName = initialExecutableToolByName
        var exposedDefinitions = initialExposedDefinitions
        val initialSchemaTokens = selectDefinitions(initialExposedDefinitions).sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() }
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
        var restoredCalls = checkpoint?.pending().orEmpty()
        var restoredDispatched = checkpoint?.pendingDispatchedCount() ?: 0
        val executionToolCallLimit = ToolBudgetPolicy.executionLimit(limits)
        val toolCallsByName = mutableMapOf<String, Int>()
        val consecutiveToolFailures = mutableMapOf<String, Int>()
        val blockedReadScopes = mutableSetOf<String>()

        while (true) {
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
                    emit(failed("Agent could not finalize after ${limits.maxRounds} model/tool rounds."))
                    return
                }
            }
            rounds += 1

            // collectRound catches only upstream provider failures. Cancellation and
            // downstream collector errors must leave the run without retrying or emitting.
            val searchContext = searchRoundContext(toolByName, executableToolByName, exchanges)
            val observedLimit = if (limits.combined == null) limits.maxObservedToolCalls.coerceIn(1, 1024) else limits.maxObservedToolCalls.coerceAtLeast(0)
            val roundDefinitions = fitProviderToolSchemas(selectDefinitions(exposedDefinitions), limits, initialSchemaTokens, replayTokens)
            reconcileDiscoveryExchange(exchanges, roundDefinitions)
            val round = if (restoredCalls.isNotEmpty()) RoundResult(calls = restoredCalls, completed = true, observedCalls = restoredCalls.size).also { restoredCalls = emptyList() } else collectRound(session, roundDefinitions, exchanges, (observedLimit - observedToolCalls).coerceAtLeast(0), searchContext.successful, searchContext.names)
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
                val recovery = recoverFailedRound(round, session, exposedDefinitions, finalResponseRequested, exchanges.isNotEmpty() || round.calls.isNotEmpty(), recoveryState) ?: return
                exposedDefinitions = recovery.definitions
                if (recovery.restoreRound) rounds--
                if (recovery.finalize) {
                    executableToolByName = emptyMap()
                    finalResponseRequested = true
                    instructFinalResponse(exchanges)
                }
                continue
            }
            val resolvedCalls = round.calls.map { resolveGitHubReadAlias(it, executableToolByName.keys) }
            resolvedCalls.forEach { emit(AgentRunEvent.Provider(it)) }
            val calls = routeSearchCalls(resolvedCalls, executableToolByName)
            if (calls.isEmpty()) {
                if (round.completed && !round.textEmitted) {
                    if (limits.combined?.requested?.singlePass != true && !emptyAnswerRecoveryAttempted && !session.handlesToolsInternally && !finalResponseRequested) {
                        emptyAnswerRecoveryAttempted = true
                        if (limits.githubTask) {
                            rounds--
                            exposedDefinitions = exposedDefinitions.map { it.copy(description = it.description + " The previous round was empty. Continue the unfinished task using retained results, or return the verified final outcome.") }
                            emit(AgentRunEvent.Notice("The provider returned an empty round. Resuming the GitHub task with its tools and saved results.", persistent = false))
                        } else {
                            finalResponseRequested = true
                            exposedDefinitions = emptyList()
                            executableToolByName = emptyMap()
                            emit(AgentRunEvent.Notice("The model returned no visible answer. Finalizing once with tools disabled and the results already collected.", persistent = false))
                        }
                        continue
                    }
                    if (limits.githubTask && limits.combined?.requested?.singlePass != true && !session.handlesToolsInternally && !finalResponseRequested) {
                        finalResponseRequested = true
                        exposedDefinitions = emptyList()
                        executableToolByName = emptyMap()
                        instructFinalResponse(exchanges)
                        emit(AgentRunEvent.Notice("The GitHub provider returned another empty round. Generating a text answer from the saved operation results.", persistent = false))
                        continue
                    }
                    emit(AgentRunEvent.Provider(ProviderEvent.Failed("The model returned no visible answer after finalization. No completed tool action was repeated. Please retry or choose another model.", transportComplete = round.completed)))
                    return
                }
                if (round.completed) {
                    checkpoint?.finish()
                    emit(AgentRunEvent.Provider(ProviderEvent.Completed))
                }
                return
            }
            val roundLimitReached = limits.maxRounds < Int.MAX_VALUE && rounds >= limits.maxRounds

            val remainingCalls = if (executionToolCallLimit == Int.MAX_VALUE) {
                calls.size
            } else {
                (executionToolCallLimit - toolCallCount + restoredDispatched).coerceAtLeast(0)
            }
            val completed = processToolCalls(
                calls, toolByName, executableToolByName, exposedDefinitions, exchanges, dynamicTools, selectDefinitions,
                initialSchemaTokens, replayTokens, toolCallsByName, consecutiveToolFailures, blockedReadScopes,
                remainingCalls, toolCallCount, restoredDispatched, roundLimitReached, finalResponseRequested,
                retriedWithoutTools, executionToolCallLimit, suppressedReadRounds, unproductiveDiscoveryRounds
            ) ?: return
            executableToolByName = completed.tools
            exposedDefinitions = completed.definitions
            toolCallCount = completed.toolCallCount
            restoredDispatched = 0
            toolMayHaveExecuted = toolMayHaveExecuted || completed.executedAny
            suppressedReadRounds = completed.suppressedReadRounds
            replayTokens = completed.batch.replayTokens
            unproductiveDiscoveryRounds = completed.batch.discoveryRounds
            finalResponseRequested = completed.batch.finalResponseRequested
            unsuccessfulGitHubRounds = if (limits.githubTask && !completed.batch.hasSuccessfulResult) unsuccessfulGitHubRounds + 1 else 0
            if (unsuccessfulGitHubRounds >= 3 && !finalResponseRequested && limits.combined?.requested?.singlePass != true) {
                finalResponseRequested = true
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                instructFinalResponse(exchanges)
                emit(AgentRunEvent.Notice("GitHub operations failed for three consecutive rounds. Finishing with saved results and the remaining blockers.", persistent = false))
            }
        }
    }

    private class RoundRecoveryState {
        var roundRecoveryAttempted = false
        var protocolRecoveryAttempted = false
        var githubRecoveries = 0
        var githubOutputRepairAttempted = false
        var githubProtocolFinalizationAttempted = false
    }

    private data class RoundRecovery(val definitions: List<AgentToolDefinition>, val restoreRound: Boolean, val finalize: Boolean)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<AgentRunEvent>.recoverFailedRound(
        round: RoundResult,
        session: AgentProviderSession,
        definitions: List<AgentToolDefinition>,
        finalResponseRequested: Boolean,
        hasToolWork: Boolean,
        state: RoundRecoveryState
    ): RoundRecovery? {
        val roundFailure = requireNotNull(round.failure)
        var exposedDefinitions = definitions
        var finalize = false
        if (round.providerLimit != null) {
            emit(AgentRunEvent.Provider(ProviderEvent.Failed(roundFailure, round.progressDeadline, round.errorCode, round.failureTransportComplete, round.providerLimit)))
            return null
        }
        val protocolFailure = round.errorCode == "TOOL_PROTOCOL_INVALID_FRAME" ||
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
            emit(AgentRunEvent.Provider(ProviderEvent.Failed(roundFailure, round.progressDeadline, errorCode = "TOOL_PROTOCOL_INVALID_FRAME")))
            return null
        }
        if (limits.githubTask && !session.handlesToolsInternally && !finalResponseRequested) {
            if (!state.githubOutputRepairAttempted && dev.chungjungsoo.gptmobile.data.agent.tool.isProviderOutputLimitFailure(roundFailure)) {
                state.githubOutputRepairAttempted = true

                exposedDefinitions = exposedDefinitions.map { it.copy(description = it.description + " The previous round reached its output limit. Continue unfinished work with a concise valid function call using retained receipts; do not repeat completed mutations.") }
                emit(AgentRunEvent.Notice("The provider reached its output limit. Repairing the unfinished GitHub round once with its tools and completed results retained.", persistent = false))
                return RoundRecovery(exposedDefinitions, limits.githubTask, finalize)
            }
            val recoveryDelay = GitHubTaskRecovery.delayMillis(roundFailure, state.githubRecoveries)
            if (recoveryDelay != null) {
                state.githubRecoveries++

                emit(AgentRunEvent.Notice("Waiting for the GitHub chat provider; resuming the saved task in ${(recoveryDelay + 999) / 1000}s (${state.githubRecoveries}/${GitHubTaskRecovery.MAX_RECOVERIES}). Completed tool actions are retained.", persistent = false))
                kotlinx.coroutines.delay(recoveryDelay)
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
        emit(AgentRunEvent.Provider(ProviderEvent.Failed(roundFailure, round.progressDeadline, round.errorCode, round.failureTransportComplete, round.providerLimit)))
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
        restoredDispatched: Int,
        roundLimitReached: Boolean,
        initialFinalResponseRequested: Boolean,
        retriedWithoutTools: Boolean,
        executionToolCallLimit: Int,
        previousSuppressedReadRounds: Int,
        unproductiveDiscoveryRounds: Int
    ): ToolRoundCompletion? {
        var executableToolByName = initialTools
        var exposedDefinitions = initialDefinitions
        var toolCallCount = initialToolCallCount
        var finalResponseRequested = initialFinalResponseRequested
        var suppressedReadRounds = previousSuppressedReadRounds
        val toolsForBatch = executableToolByName
        val plan = planToolBatch(calls, remainingCalls, toolsForBatch.keys, toolCallsByName, blockedReadScopes, MAX_SAME_TOOL_CALLS_PER_RUN, ::failureScope)
        val executableCalls = plan.executable
        val deferredCalls = plan.deferred
        val perToolSuppressedIds = plan.perToolSuppressedIds
        val hostSuppressedIds = plan.hostSuppressedIds
        plan.acceptedByName.forEach { (name, count) -> toolCallsByName[name] = (toolCallsByName[name] ?: 0) + count }
        if (plan.disabledNames.isNotEmpty()) {
            executableToolByName = executableToolByName.filterKeys { it !in plan.disabledNames }
            exposedDefinitions = exposedDefinitions.filterNot { it.name in plan.disabledNames }
            AppLogRecorder.record("Agent", "TOOL_REPEAT_SUPPRESSED · tools=${plan.disabledNames.size} · calls=${plan.perToolSuppressedIds.size}", "W")
        }

        checkpoint?.enqueue(calls)
        executableCalls.forEach { emit(AgentRunEvent.ToolStarted(it)) }
        val executedResults = mutableListOf<AgentToolResult>()
        for (batch in executableCalls.chunked(limits.maxToolCallsPerRound.coerceIn(1, 32))) {
            val results = executeToolBatch(batch, toolsForBatch)
            executedResults += results
            batch.zip(results).forEach { (call, result) ->
                healthCatalog?.recordObservation(call.name, result)
                emit(AgentRunEvent.ToolFinished(call, result))
            }
        }
        if (!finalResponseRequested && !retriedWithoutTools) {
            val update = admitDiscoveredTools(dynamicTools(), toolByName, executableToolByName, exposedDefinitions, initialSchemaTokens, consecutiveToolFailures, toolCallsByName, replayTokens, selectDefinitions) ?: return null
            executableToolByName = update.tools
            exposedDefinitions = update.definitions
        }
        toolCallCount += (executableCalls.size - restoredDispatched).coerceAtLeast(0)

        val newlyBlockedTools = updateFailureCircuits(executableCalls, executedResults, consecutiveToolFailures, blockedReadScopes, ::failureScope)
        if (newlyBlockedTools.isNotEmpty()) {
            executableToolByName = executableToolByName.filterKeys { it !in newlyBlockedTools }
            exposedDefinitions = exposedDefinitions.filterNot { it.name in newlyBlockedTools }
            newlyBlockedTools.forEach { toolName ->
                AppLogRecorder.record("Agent", "TOOL_FAILURE_CIRCUIT_OPEN · tool=$toolName · failures=${consecutiveToolFailures[toolName]}", "W")
            }
        }

        val deferredResults = deferredToolResults(deferredCalls, hostSuppressedIds, perToolSuppressedIds, plan.unavailableIds, executableToolByName.keys, exchanges)
        deferredCalls.zip(deferredResults).forEach { (call, result) -> checkpoint?.complete(call, result) }
        val canReadOtherSources = (plan.unavailableIds.isNotEmpty() && executableToolByName.isNotEmpty()) ||
            hostSuppressedIds.isNotEmpty() ||
            deferredCalls.any { it.callId in perToolSuppressedIds && it.name in executableToolByName }
        // Give a scoped read one round to choose another source, then stop
        // paying for provider rounds that only repeat already-suppressed work.
        suppressedReadRounds = if (executableCalls.isEmpty() && canReadOtherSources) suppressedReadRounds + 1 else 0
        val completedBatch = finishBatch(
            calls, executedResults + deferredResults, exchanges, executableCalls.isEmpty(),
            canReadOtherSources, suppressedReadRounds >= 2,
            roundLimitReached, toolCallCount, executionToolCallLimit,
            unproductiveDiscoveryRounds, finalResponseRequested, executableCalls.mapTo(hashSetOf()) { it.callId }
        )
        finalResponseRequested = completedBatch.finalResponseRequested
        if (finalResponseRequested) {
            exposedDefinitions = emptyList()
            executableToolByName = emptyMap()
        }
        return ToolRoundCompletion(executableToolByName, exposedDefinitions, toolCallCount, executableCalls.isNotEmpty(), suppressedReadRounds, completedBatch)
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

    private fun deferredToolResults(calls: List<ProviderEvent.ToolCall>, hostSuppressedIds: Set<String>, perToolSuppressedIds: Set<String>, unavailableIds: Set<String>, availableNames: Set<String>, exchanges: List<AgentToolExchange>): List<AgentToolResult> = calls.map { call ->
        if (call.callId in unavailableIds) {
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
            val batchSize = minOf(4, limits.maxToolCallsPerRound, observedLimit - observedToolCalls).coerceAtLeast(1)
            val repaired = exposedDefinitions.map { definition ->
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
        } else {
            call
        }
    }

    private data class ToolCatalogUpdate(val tools: Map<String, AgentTool>, val definitions: List<AgentToolDefinition>)

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
        val additions = discovered.filter { it.definition.name !in toolByName && it.definition.name !in executableToolByName && (consecutiveToolFailures[it.definition.name] ?: 0) < 2 && (toolCallsByName[it.definition.name] ?: 0) < MAX_SAME_TOOL_CALLS_PER_RUN }
        if (additions.groupBy { it.definition.name }.any { it.value.size > 1 }) {
            emit(failed("Ambiguous discovered tool aliases; no discovered action dispatched."))
            return null
        }
        val selectedSchemas = selectDefinitions((exposedDefinitions + additions.map { it.definition }).distinctBy { it.name })
        val selectedNames = selectedSchemas.mapTo(HashSet()) { it.name }
        var schemaTokens = selectedSchemas.filter { it.name in executableToolByName }.sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() }
        val contextWithoutInitialSchemas = (limits.initialContextTokens.toLong() - initialSchemaTokens).coerceAtLeast(0)
        val schemaAllowance = minOf(ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE.toLong(), (limits.contextTokens.toLong() - contextWithoutInitialSchemas - replayTokens - limits.finalResponseReserveTokens).coerceAtLeast(0))
        val admitted = additions.filter { tool ->
            val cost = ProgressiveToolCatalog.schemaTokens(tool.definition)
            val fits = tool.definition.name in selectedNames && schemaTokens + cost <= schemaAllowance
            if (fits) schemaTokens += cost
            if (!fits) AppLogRecorder.record("ToolCatalog", "TOOL_OMITTED · stage=dynamic_admission · name=${tool.definition.name} · reason=${if (tool.definition.name !in selectedNames) "CATALOG_NOT_SELECTED" else "REMAINING_CONTEXT_BUDGET"} · schemaTokens=$cost · schemaAllowance=$schemaAllowance")
            fits
        }
        if (admitted.size < additions.size) emit(AgentRunEvent.Notice("Some discovered tool schemas exceed the remaining context allowance. Existing conversation context was preserved.", persistent = false))
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
        publishedCallIds: Set<String> = emptySet()
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
        // After two such rounds, preserve evidence and force a tool-free answer.
        val noNewTools = calls.isNotEmpty() &&
            calls.all { it.name == ProgressiveToolCatalog.NAME } &&
            allResults.all { result ->
                result.isError || (((result.content as? ToolResultContent.Json)?.value as? kotlinx.serialization.json.JsonObject)?.get("catalog_unchanged")?.toString() == "true")
            }
        val unproductiveDiscoveryRounds = if (noNewTools) previousDiscoveryRounds + 1 else 0
        val repeatedDiscoveryStalled = unproductiveDiscoveryRounds >= 2
        val outputBudgetExhausted = allResults.any { it.outputBudgetExhausted }
        val toolCallBudgetExhausted = allResults.any { it.toolCallBudgetExhausted }
        val sharedCallBudgetResult = allResults
            .filter { it.toolCallBudgetExhausted }
            .maxByOrNull { it.toolCallBudgetUsed ?: -1 }
        val sharedOutputBudgetResult = allResults
            .filter { it.outputBudgetExhausted }
            .maxByOrNull { it.toolResultBudgetUsedBytes ?: -1 }
        val projectedExchanges = exchanges + AgentToolExchange(calls, allResults)
        val projectedReplayTokens = ToolExchangeCompactor.estimateTokens(projectedExchanges)
        val replayTokens = ToolExchangeCompactor.estimateTokens(
            ToolExchangeCompactor.compact(
                exchanges = projectedExchanges,
                maxReplayTokens = limits.maxReplayTokens,
                maxResultTokens = if (projectedReplayTokens <= limits.maxReplayTokens) limits.maxReplayTokens else limits.maxReplayResultTokens
            )
        ).toLong()
        val contextNearLimit = limits.contextTokens != Int.MAX_VALUE &&
            limits.initialContextTokens.toLong() + replayTokens + limits.finalResponseReserveTokens + 256 >= limits.contextTokens
        val repeatLimited = allResults.any { toolResultText(it).contains("reached the per-response repeat limit", ignoreCase = true) }
        val unavailableCallsOnly = noExecutableCalls && calls.isNotEmpty() && !canReadOtherSources
        val mustFinalize = unavailableCallsOnly ||
            repeatedReadSuppression ||
            repeatedDiscoveryStalled ||
            roundLimitReached ||
            contextNearLimit ||
            outputBudgetExhausted ||
            toolCallBudgetExhausted ||
            (executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit)
        if (unavailableCallsOnly) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice(if (repeatLimited) "Repeated calls were stopped. Finishing with the findings already collected." else "Requested tools are unavailable for this response. Finishing with the findings already collected.", persistent = false))
        } else if (repeatedReadSuppression) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice("Repeated reads produced no new work. Finishing with the results already collected.", persistent = false))
        } else if (repeatedDiscoveryStalled) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice("No new tools became available after repeated discovery. Summarizing the evidence already collected.", persistent = false))
        } else if (roundLimitReached) {
            finalResponseRequested = true
            emit(AgentRunEvent.Notice(ROUND_LIMIT_FINAL_RESPONSE_NOTICE, persistent = false))
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
                repeatedReadSuppression -> appendInstruction(allResults.last(), "Repeated reads were skipped without acquiring new evidence. Summarize the results already collected and identify remaining gaps. Do not request more tools in this response.")
                repeatedDiscoveryStalled -> appendInstruction(allResults.last(), "No more tools became available. Do not call discovery again; summarize completed findings and clearly identify any gaps.")
                roundLimitReached -> appendInstruction(allResults.last(), ROUND_LIMIT_FINAL_RESPONSE_INSTRUCTION)
                contextNearLimit -> appendInstruction(allResults.last(), "The context limit is approaching. Use the available findings to give a final response now and ask whether the user wants to continue. Do not call more tools.")
                outputBudgetExhausted && toolCallBudgetExhausted ->
                    appendInstruction(allResults.last(), COMBINED_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                outputBudgetExhausted -> appendInstruction(allResults.last(), OUTPUT_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                else -> appendFinalResponseInstruction(allResults.last())
            }
        } else if (shouldInjectWrapUp && allResults.isNotEmpty()) {
            val wrapUpPrompt = ToolBudgetPolicy.buildWrapUpPrompt(remainingAllowance, combined = limits.combined != null)
            allResults[allResults.lastIndex] = appendInstruction(allResults.last(), wrapUpPrompt)
        }

        calls.zip(allResults).forEach { (call, result) ->
            if (call.callId !in publishedCallIds) emit(AgentRunEvent.ToolFinished(call, result))
        }
        // Retain bounded observations on ingress, including trace/retained payloads.
        val retentionBudget = limits.maxRetainedEvidenceTokens.coerceIn(1024, 32_000)
        val observations = exchanges + AgentToolExchange(calls, allResults.map { it.copy(traceContent = null, retainedContent = null) })
        val retained = ToolExchangeCompactor.compact(
            observations,
            retentionBudget,
            // Preserve complete evidence while it fits the retained budget.
            // Provider replay still enforces its independent, smaller allowance.
            if (ToolExchangeCompactor.estimateTokens(observations) <= retentionBudget) retentionBudget else limits.maxReplayResultTokens.coerceIn(64, 2048)
        )
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
        val providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null
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
        searchToolNames: Set<String>
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
        var providerLimit: dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision? = null
        var textEmitted = false
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
                        phase = if (listOf(roundInput, roundOutput, roundTotal).filter { it.value != null }.all { it.phase == ProviderUsagePhase.FINAL }) ProviderUsagePhase.FINAL else ProviderUsagePhase.INTERIM
                    )
                )
            )
        }

        // Keep provider failures separate from downstream collector failures. In
        // particular, never emit usage from finally after cancellation or a failed emit.
        flow {
            val rawReplayTokens = ToolExchangeCompactor.estimateTokens(exchanges)
            val replayExchanges = ToolExchangeCompactor.compact(
                exchanges = exchanges,
                maxReplayTokens = limits.maxReplayTokens,
                maxResultTokens = if (rawReplayTokens <= limits.maxReplayTokens) limits.maxReplayTokens else limits.maxReplayResultTokens
            )
            val compactedReplayTokens = ToolExchangeCompactor.estimateTokens(replayExchanges)
            if (rawReplayTokens > compactedReplayTokens) {
                AppLogRecorder.record(
                    "Agent",
                    "PRIMARY_REPLAY_COMPACTED · rawTokens=$rawReplayTokens · replayTokens=$compactedReplayTokens · savedTokens=${rawReplayTokens - compactedReplayTokens} · exchanges=${exchanges.size} · budget=${limits.maxReplayTokens}"
                )
            }
            if (compactedReplayTokens > limits.maxReplayTokens) {
                AppLogRecorder.record(
                    "Agent",
                    "PRIMARY_REPLAY_BUDGET_OVERRUN · replayTokens=$compactedReplayTokens · budget=${limits.maxReplayTokens} · exchanges=${exchanges.size}",
                    "W"
                )
            }
            // Include synchronous stream construction failures in the upstream boundary.
            emitAll(session.streamRound(exposedDefinitions, replayExchanges.map { exchange -> exchange.copy(results = exchange.results.map { dev.chungjungsoo.gptmobile.data.assist.OptionalAssistArtifacts.ready(it) }) }).withAssistantTextMarkupGuard(successfulSearch || session.hasPreparedSearchEvidence, searchToolNames, allowControlJsonText = session.allowsControlJsonText))
        }.catch { error ->
            if (error is CancellationException || error is VirtualMachineError || error is ThreadDeath || error is IllegalStateException && error.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true) throw error
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
                failure = if (toolDefinitionsRejected) {
                    error.message ?: "Tools are unavailable for this model."
                } else {
                    ErrorClassification.classify(error).userMessage
                }
            }
        }.transformWhile { event ->
            if (event is ProviderEvent.ToolCall && !admission.admit(event)) {
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

                is ProviderEvent.ToolResult -> emit(AgentRunEvent.ToolFinished(event.call, ToolObservationRetention.bound(event.result)))

                is ProviderEvent.Failed -> {
                    failure = event.message
                    progressDeadline = event.progressDeadline
                    failureCode = event.errorCode
                    failureTransportComplete = event.transportComplete
                    providerLimit = event.providerLimit
                }

                is ProviderEvent.Notice -> emit(AgentRunEvent.Notice(event.message, event.persistent, event.researchDelivery))

                is ProviderEvent.Usage -> {
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
        return RoundResult(calls, completed, failure, textEmitted, toolDefinitionsRejected, batchRejected, maxOf(admission.observed, parserObservedCalls), progressDeadline, batchRejection, failureCode, failureTransportComplete, providerLimit)
    }

    private fun toolResultText(result: AgentToolResult): String = when (val content = result.content) {
        is ToolResultContent.Text -> content.text
        is ToolResultContent.Json -> content.value.toString()
        is ToolResultContent.ResourceLinks -> content.links.joinToString("\n") { it.uri }
    }

    private fun countsTowardToolFailureCircuit(result: AgentToolResult): Boolean {
        if (!result.isError || result.outputBudgetExhausted || result.toolCallBudgetExhausted) return false
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
                    roundResults.any { result -> result.isError && terminalToolFailure(toolResultText(result)) } -> MAX_CONSECUTIVE_TOOL_FAILURES
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

    private suspend fun executeToolBatch(calls: List<ProviderEvent.ToolCall>, tools: Map<String, AgentTool>): List<AgentToolResult> {
        val semaphore = Semaphore(limits.maxConcurrentTools.coerceIn(1, 32))
        val perTool = calls.map { it.name }.distinct().associateWith { Semaphore(1) }
        val order = calls.mapIndexed { index, call -> call.callId to index }.toMap()
        fun ownerOf(call: ProviderEvent.ToolCall): AgentToolExecutionOwner =
            (tools[call.name] as? OwnedAgentTool)?.executionOwner ?: AgentToolExecutionOwner.CLIENT
        return coroutineScope {
            calls.groupBy(::ownerOf)
                .flatMap { (_, ownedCalls) ->
                    ownedCalls.map { call ->
                        async {
                            perTool.getValue(call.name).withPermit {
                                semaphore.withPermit {
                                    val result = checkpoint?.execute(call) { executeBounded(call, tools[call.name]) } ?: executeBounded(call, tools[call.name])
                                    call to result
                                }
                            }
                        }
                    }.awaitAll()
                }
                .sortedBy { order.getValue(it.first.callId) }
                .map { it.second }
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

    private fun instructFinalResponse(exchanges: MutableList<AgentToolExchange>) {
        val previous = exchanges.lastOrNull() ?: return
        val result = previous.results.lastOrNull() ?: return
        exchanges[exchanges.lastIndex] = previous.copy(results = previous.results.dropLast(1) + appendInstruction(result, "Continue with a text answer and no further tool calls. Report verified outcomes from saved receipts, the concrete failure and any unfinished work. Do not claim changes were applied without a successful receipt or repeat a completed action."))
    }

    private suspend fun executeBounded(
        call: ProviderEvent.ToolCall,
        tool: AgentTool?
    ): AgentToolResult {
        if (tool == null) {
            return AgentToolResult(
                callId = call.callId,
                content = ToolResultContent.Text("Tool '${call.name}' is not assigned to this profile."),
                isError = true
            )
        }

        return try {
            val result = if (!tool.managesExecutionBudget && limits.toolTimeoutMillis < Long.MAX_VALUE) {
                withTimeoutOrNull(limits.toolTimeoutMillis) {
                    tool.execute(call.callId, call.arguments)
                } ?: AgentToolResult(
                    callId = call.callId,
                    content = ToolResultContent.Text("Tool '${call.name}' timed out after ${limits.toolTimeoutMillis} ms."),
                    isError = true
                )
            } else {
                tool.execute(call.callId, call.arguments)
            }
            if (result.callId != call.callId) {
                return AgentToolResult(
                    callId = call.callId,
                    content = ToolResultContent.Text("The tool returned a receipt for a different call. This operation may have executed; inspect its external state before retrying."),
                    isError = true,
                    errorCode = "TOOL_RESULT_ID_MISMATCH",
                    dispatched = result.dispatched
                )
            }
            ToolObservationRetention.bound(if (tool.managesExecutionBudget) result else result.copy(content = boundContent(result.content)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: dev.chungjungsoo.gptmobile.data.research.ResearchProviderDeferred) {
            throw error
        } catch (error: Exception) {
            AgentToolResult(
                callId = call.callId,
                content = ToolResultContent.Text(error.message ?: "Tool '${call.name}' failed."),
                isError = true
            ).let { it.copy(content = boundContent(it.content)) }
        }
    }

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
