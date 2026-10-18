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
    /** Maximum budget retained from any one consumed tool result on later rounds. */
    val maxReplayResultTokens: Int = 512,
    val maxObservedToolCalls: Int = 256,
    val maxToolCallsPerRound: Int = 8,
    val maxRetainedEvidenceTokens: Int = 16_000,
    val allowToolStormFinalization: Boolean = true,
    val maxGeneratedCharacters: Int = 0,
    val detectRepetition: Boolean = false,
    val researchAcquisition: Boolean = false
) {
    companion object {
        const val DEFAULT_MAX_TOOL_CALLS: Int = 50

        fun defaultMaxConcurrentTools(): Int = 6

        fun defaultMaxToolOutputBytes(): Int = 256 * 1024
    }
}

class AgentRunner(
    val limits: AgentRunLimits = AgentRunLimits()
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
        var toolCallCount = 0
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
    }

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
        var rounds = initialRounds
        var toolCallCount = initialToolCallCount
        var toolMayHaveExecuted = initialToolMayHaveExecuted
        var retriedWithoutTools = initialRetriedWithoutTools
        var roundRecoveryAttempted = false
        var emptyAnswerRecoveryAttempted = false
        var finalResponseRequested = false
        var roundLimitFinalizationAttempted = false
        var wrapUpNoticeEmitted = false
        var replayTokens = 0L
        var observedToolCalls = 0
        var stormFinalizationAttempted = false
        var unproductiveDiscoveryRounds = 0
        val executionToolCallLimit = ToolBudgetPolicy.executionLimit(limits)
        val toolCallsByName = mutableMapOf<String, Int>()
        val consecutiveToolFailures = mutableMapOf<String, Int>()
        val blockedReadScopes = mutableSetOf<String>()
        fun failureScope(call: ProviderEvent.ToolCall): String {
            if (call.name == "read_url" || call.name.endsWith("_read_url")) {
                val url = (call.arguments["url"] as? JsonPrimitive)?.content.orEmpty()
                val host = runCatching { URI(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
                return "${call.name}|host=${host ?: "invalid-url"}"
            }
            return call.name
        }

        while (true) {
            if (executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit) {
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                if (!finalResponseRequested) {
                    finalResponseRequested = true
                    val reserve = limits.finalResponseToolCallReserve.coerceAtLeast(0)
                    emit(
                        AgentRunEvent.Notice(
                            "Tool-call limit reached ($toolCallCount/$executionToolCallLimit executable calls; " +
                                "${limits.maxToolCalls} configured, $reserve reserved). Generating a final response with completed results.",
                            persistent = true
                        )
                    )
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
            val searchNames = (toolByName.values + executableToolByName.values).filter {
                AgentToolCapability.WEB_SEARCH in it.definition.capabilities
            }.mapTo(HashSet()) { it.definition.name }
            val successfulSearch = exchanges.any { exchange ->
                exchange.results.any { result -> !result.isError && result.dispatched && exchange.calls.any { it.callId == result.callId && it.name in searchNames } }
            }
            val round = collectRound(session, exposedDefinitions, exchanges, (limits.maxObservedToolCalls.coerceIn(1, 1024) - observedToolCalls).coerceAtLeast(0), successfulSearch, searchNames)
            observedToolCalls += round.observedCalls
            if (round.batchRejected) {
                AppLogRecorder.record("Agent", "TOOL_BATCH_OVERSIZE · rejectedBatchObserved=${round.observedCalls} · permittedPerRound=${limits.maxToolCallsPerRound} · cumulativeObserved=$observedToolCalls · rejection=${round.batchRejection ?: "UNKNOWN_THRESHOLD"} · dispatched=0", "W")
                emit(AgentRunEvent.Notice("An oversized or invalid tool batch was stopped before dispatch. Retry with at most ${limits.maxToolCallsPerRound} calls per batch; completed evidence remains available.", persistent = true))
                if (stormFinalizationAttempted || session.handlesToolsInternally || !limits.allowToolStormFinalization) {
                    emit(failed("TOOL_BATCH_OVERSIZE: partial answer and completed evidence retained. The provider did not honor tool-free finalization."))
                    return
                }
                stormFinalizationAttempted = true
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                finalResponseRequested = true
                continue
            }
            if (round.toolDefinitionsRejected) {
                if (exposedDefinitions.isNotEmpty() && !toolMayHaveExecuted && !retriedWithoutTools && !round.textEmitted) {
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
            val roundFailure = round.failure
            if (roundFailure != null) {
                // An incomplete tool call is never executed. Recover once from its
                // existing evidence with tools disabled, without replaying an action.
                if (!roundRecoveryAttempted && !round.textEmitted && !session.handlesToolsInternally && recoverableRoundFailure(roundFailure, exchanges.isNotEmpty() || round.calls.isNotEmpty())) {
                    roundRecoveryAttempted = true
                    exposedDefinitions = emptyList()
                    executableToolByName = emptyMap()
                    finalResponseRequested = true
                    emit(AgentRunEvent.Notice("The provider interrupted this round. Finishing once with the results already collected.", persistent = false))
                    kotlinx.coroutines.delay(1000)
                    continue
                }
                emit(AgentRunEvent.Provider(ProviderEvent.Failed(roundFailure, round.progressDeadline)))
                return
            }
            round.calls.forEach { emit(AgentRunEvent.Provider(it)) }
            val calls = round.calls.map { call ->
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
            if (calls.isEmpty()) {
                if (round.completed && !round.textEmitted) {
                    if (!emptyAnswerRecoveryAttempted && !session.handlesToolsInternally && !finalResponseRequested) {
                        emptyAnswerRecoveryAttempted = true
                        finalResponseRequested = true
                        exposedDefinitions = emptyList()
                        executableToolByName = emptyMap()
                        emit(AgentRunEvent.Notice("The model returned no visible answer. Finalizing once with tools disabled and the results already collected.", persistent = false))
                        continue
                    }
                    emit(failed("The model returned no visible answer after finalization. No completed tool action was repeated. Please retry or choose another model."))
                    return
                }
                if (round.completed) emit(AgentRunEvent.Provider(ProviderEvent.Completed))
                return
            }
            val roundLimitReached = limits.maxRounds < Int.MAX_VALUE && rounds >= limits.maxRounds

            val remainingCalls = if (executionToolCallLimit == Int.MAX_VALUE) {
                calls.size
            } else {
                (executionToolCallLimit - toolCallCount).coerceAtLeast(0)
            }
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

            executableCalls.forEach { emit(AgentRunEvent.ToolStarted(it)) }
            if (executableCalls.isNotEmpty()) toolMayHaveExecuted = true
            val executedResults = executeToolBatch(executableCalls, toolsForBatch)
            if (!finalResponseRequested && !retriedWithoutTools) {
                val additions = dynamicTools().filter { it.definition.name !in toolByName && it.definition.name !in executableToolByName && (consecutiveToolFailures[it.definition.name] ?: 0) < 2 && (toolCallsByName[it.definition.name] ?: 0) < MAX_SAME_TOOL_CALLS_PER_RUN }
                if (additions.groupBy { it.definition.name }.any { it.value.size > 1 }) {
                    emit(failed("Ambiguous discovered tool aliases; no discovered action dispatched."))
                    return
                }
                val selectedSchemas = selectDefinitions((exposedDefinitions + additions.map { it.definition }).distinctBy { it.name })
                val selectedNames = selectedSchemas.mapTo(HashSet()) { it.name }
                var schemaTokens = selectedSchemas.filter { it.name in executableToolByName }.sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() }
                val initialSchemas = initialExposedDefinitions.sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() }
                val contextWithoutInitialSchemas = (limits.initialContextTokens.toLong() - initialSchemas).coerceAtLeast(0)
                val schemaAllowance = minOf(ProgressiveToolCatalog.SCHEMA_TOKEN_ALLOWANCE.toLong(), (limits.contextTokens.toLong() - contextWithoutInitialSchemas - replayTokens - limits.finalResponseReserveTokens).coerceAtLeast(0))
                val admitted = additions.filter { tool ->
                    val cost = ProgressiveToolCatalog.schemaTokens(tool.definition)
                    val fits = tool.definition.name in selectedNames && schemaTokens + cost <= schemaAllowance
                    if (fits) schemaTokens += cost
                    fits
                }
                if (admitted.size < additions.size) emit(AgentRunEvent.Notice("Some discovered tool schemas exceed the remaining context allowance. Existing conversation context was preserved.", persistent = false))
                executableToolByName = executableToolByName + admitted.associateBy { it.definition.name }
                exposedDefinitions = (exposedDefinitions + admitted.map { it.definition }).distinctBy { it.name }
            }
            toolCallCount += executableCalls.size

            val newlyBlockedTools = updateFailureCircuits(executableCalls, executedResults, consecutiveToolFailures, blockedReadScopes, ::failureScope)
            if (newlyBlockedTools.isNotEmpty()) {
                executableToolByName = executableToolByName.filterKeys { it !in newlyBlockedTools }
                exposedDefinitions = exposedDefinitions.filterNot { it.name in newlyBlockedTools }
                newlyBlockedTools.forEach { toolName ->
                    AppLogRecorder.record("Agent", "TOOL_FAILURE_CIRCUIT_OPEN · tool=$toolName · failures=${consecutiveToolFailures[toolName]}", "W")
                }
            }

            val deferredResults = deferredCalls.map { call ->
                val message = if (call.callId in hostSuppressedIds) {
                    "This website failed repeatedly and is paused for this response. read_url remains available for other hosts; choose another source."
                } else if (call.callId in perToolSuppressedIds) {
                    "This operation reached the per-response repeat limit. Use results already collected; do not repeat these arguments. Other authorized sources may still be read within the remaining budget."
                } else {
                    FINAL_RESPONSE_INSTRUCTION
                }
                AgentToolResult(
                    callId = call.callId,
                    content = ToolResultContent.Text(message),
                    isError = true,
                    dispatched = false,
                    errorCode = "NOT_DISPATCHED"
                )
            }
            val producedResults = executedResults + deferredResults
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
            unproductiveDiscoveryRounds = if (noNewTools) unproductiveDiscoveryRounds + 1 else 0
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
            replayTokens = ToolExchangeCompactor.estimateTokens(
                ToolExchangeCompactor.compact(
                    exchanges = projectedExchanges,
                    maxReplayTokens = limits.maxReplayTokens,
                    maxResultTokens = if (projectedReplayTokens <= limits.maxReplayTokens) limits.maxReplayTokens else limits.maxReplayResultTokens
                )
            ).toLong()
            val contextNearLimit = limits.contextTokens != Int.MAX_VALUE &&
                limits.initialContextTokens.toLong() + replayTokens + limits.finalResponseReserveTokens + 256 >= limits.contextTokens
            val unavailableCallsOnly = executableCalls.isEmpty() && calls.isNotEmpty() && hostSuppressedIds.isEmpty()
            val mustFinalize = unavailableCallsOnly ||
                repeatedDiscoveryStalled ||
                roundLimitReached ||
                contextNearLimit ||
                outputBudgetExhausted ||
                toolCallBudgetExhausted ||
                (executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit)
            if (unavailableCallsOnly) {
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                finalResponseRequested = true
                emit(AgentRunEvent.Notice("Requested tools are unavailable for this response. Finishing with the findings already collected.", persistent = false))
            } else if (repeatedDiscoveryStalled) {
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                finalResponseRequested = true
                emit(AgentRunEvent.Notice("No new tools became available after repeated discovery. Summarizing the evidence already collected.", persistent = false))
            } else if (roundLimitReached) {
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                finalResponseRequested = true
                emit(AgentRunEvent.Notice(ROUND_LIMIT_FINAL_RESPONSE_NOTICE, persistent = false))
            } else if (toolCallBudgetExhausted && outputBudgetExhausted) {
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
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
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
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
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
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
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                finalResponseRequested = true
                emit(AgentRunEvent.Notice("Context limit approaching. Finishing with the results already available.", persistent = false))
            }
            val remainingAllowance = ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount)
            val shouldInjectWrapUp = ToolBudgetPolicy.shouldInjectWrapUpPrompt(executionToolCallLimit, limits, toolCallCount)

            if (mustFinalize && allResults.isNotEmpty()) {
                allResults[allResults.lastIndex] = when {
                    unavailableCallsOnly -> appendInstruction(allResults.last(), "These tools are disabled or unavailable. Use successful findings already collected to answer now. Do not request more tools in this response.")
                    repeatedDiscoveryStalled -> appendInstruction(allResults.last(), "No more tools became available. Do not call discovery again; summarize completed findings and clearly identify any gaps.")
                    roundLimitReached -> appendInstruction(allResults.last(), ROUND_LIMIT_FINAL_RESPONSE_INSTRUCTION)
                    contextNearLimit -> appendInstruction(allResults.last(), "The context limit is approaching. Use the available findings to give a final response now and ask whether the user wants to continue. Do not call more tools.")
                    outputBudgetExhausted && toolCallBudgetExhausted ->
                        appendInstruction(allResults.last(), COMBINED_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                    outputBudgetExhausted -> appendInstruction(allResults.last(), OUTPUT_BUDGET_FINAL_RESPONSE_INSTRUCTION)
                    else -> appendFinalResponseInstruction(allResults.last())
                }
            } else if (shouldInjectWrapUp && allResults.isNotEmpty()) {
                val wrapUpPrompt = ToolBudgetPolicy.buildWrapUpPrompt(remainingAllowance)
                allResults[allResults.lastIndex] = appendInstruction(allResults.last(), wrapUpPrompt)
            }

            calls.zip(allResults).forEach { (call, result) ->
                emit(AgentRunEvent.ToolFinished(call, result))
            }
            // Retain bounded observations on ingress, including trace/retained payloads.
            val retained = ToolExchangeCompactor.compact(
                exchanges + AgentToolExchange(calls, allResults.map { it.copy(traceContent = null, retainedContent = null) }),
                limits.maxRetainedEvidenceTokens.coerceIn(1024, 32_000),
                limits.maxReplayResultTokens.coerceIn(64, 2048)
            )
            exchanges.clear()
            exchanges.addAll(retained)
        }
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
        val batchRejection: String? = null
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
        val admission = ToolRoundAdmission(minOf(limits.maxToolCallsPerRound, remainingObserved))
        var batchRejected = false
        var batchRejection: String? = null
        var completed = false
        var failure: String? = null
        var progressDeadline: ProgressDeadlineOutcome? = null
        var textEmitted = false
        val answerCandidate = StringBuilder()
        var toolDefinitionsRejected = false
        var roundInputTokens = 0L
        var roundOutputTokens = 0L
        var roundTotalTokens = 0L
        var hasRoundInputUsage = false
        var hasRoundOutputUsage = false
        var hasRoundTotalUsage = false
        var roundDecodeSpeed: Double? = null
        var roundFinishReason: String? = null
        var roundReasoningTokens: Long? = null
        var roundSettingsConfirmed = false

        suspend fun emitRoundUsage() {
            if (!hasRoundInputUsage && !hasRoundOutputUsage && !hasRoundTotalUsage && roundDecodeSpeed == null && roundFinishReason == null && !roundSettingsConfirmed && roundReasoningTokens == null) return
            emit(
                AgentRunEvent.Provider(
                    ProviderEvent.Usage(
                        inputTokens = roundInputTokens.takeIf { hasRoundInputUsage }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        outputTokens = roundOutputTokens.takeIf { hasRoundOutputUsage }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        totalTokens = roundTotalTokens.takeIf { hasRoundTotalUsage }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                        cumulative = false,
                        decodeTokensPerSecond = roundDecodeSpeed,
                        finishReason = roundFinishReason,
                        effectiveSettingsConfirmed = roundSettingsConfirmed,
                        reasoningTokens = roundReasoningTokens?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
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
            emitAll(session.streamRound(exposedDefinitions, replayExchanges.map { exchange -> exchange.copy(results = exchange.results.map { dev.chungjungsoo.gptmobile.data.assist.OptionalAssistArtifacts.ready(it) }) }).withAssistantTextMarkupGuard(successfulSearch, searchToolNames))
        }.catch { error ->
            if (error is CancellationException || error is VirtualMachineError || error is ThreadDeath || error is IllegalStateException && error.message?.startsWith("DISPATCH_POLICY_CHANGED:") == true) throw error
            if (error is ToolBatchOversizeException) {
                batchRejection = error.message
                batchRejected = true
                return@catch
            }
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
            if (failure != null && event !is ProviderEvent.Usage) return@collect
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
                }

                is ProviderEvent.Notice -> emit(AgentRunEvent.Notice(event.message, event.persistent))

                is ProviderEvent.Usage -> {
                    event.reasoningTokens?.let { tokens ->
                        roundReasoningTokens = if (event.cumulative) maxOf(roundReasoningTokens ?: 0L, tokens.toLong()) else (roundReasoningTokens ?: 0L) + tokens
                    }
                    event.decodeTokensPerSecond?.let { roundDecodeSpeed = it }
                    event.finishReason?.let { roundFinishReason = it }
                    roundSettingsConfirmed = roundSettingsConfirmed || event.effectiveSettingsConfirmed
                    event.inputTokens?.let { tokens ->
                        roundInputTokens = if (event.cumulative) {
                            maxOf(roundInputTokens, tokens.toLong())
                        } else {
                            roundInputTokens + tokens
                        }
                        hasRoundInputUsage = true
                    }
                    event.outputTokens?.let { tokens ->
                        roundOutputTokens = if (event.cumulative) {
                            maxOf(roundOutputTokens, tokens.toLong())
                        } else {
                            roundOutputTokens + tokens
                        }
                        hasRoundOutputUsage = true
                    }
                    event.totalTokens?.let { tokens ->
                        roundTotalTokens = if (event.cumulative) {
                            maxOf(roundTotalTokens, tokens.toLong())
                        } else {
                            roundTotalTokens + tokens
                        }
                        hasRoundTotalUsage = true
                    }
                }

                ProviderEvent.Completed -> completed = true

                else -> {
                    if (event is ProviderEvent.TextDelta && !textEmitted) {
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
        return RoundResult(calls, completed, failure, textEmitted, toolDefinitionsRejected, batchRejected, admission.observed, progressDeadline, batchRejection)
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
                    if ("|host=" in toolName) {
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
                                semaphore.withPermit { call to executeBounded(call, tools[call.name]) }
                            }
                        }
                    }.awaitAll()
                }
                .sortedBy { order.getValue(it.first.callId) }
                .map { it.second }
        }
    }

    private fun appendInstruction(result: AgentToolResult, instruction: String): AgentToolResult {
        val existing = when (val content = result.content) {
            is ToolResultContent.Text -> content.text
            is ToolResultContent.Json -> Json.encodeToString(content.value)
            is ToolResultContent.ResourceLinks -> Json.encodeToString(content.links.map { it.uri })
        }
        if (existing.contains(instruction)) return result
        return result.copy(content = ToolResultContent.Text("$existing\n\n$instruction"))
    }

    private fun appendFinalResponseInstruction(result: AgentToolResult): AgentToolResult {
        val existing = when (val content = result.content) {
            is ToolResultContent.Text -> content.text
            is ToolResultContent.Json -> Json.encodeToString(content.value)
            is ToolResultContent.ResourceLinks -> Json.encodeToString(content.links.map { it.uri })
        }
        if (existing.contains(FINAL_RESPONSE_INSTRUCTION)) return result
        return result.copy(content = ToolResultContent.Text("$existing\n\n$FINAL_RESPONSE_INSTRUCTION"))
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
            ToolObservationRetention.bound(if (tool.managesExecutionBudget) result else result.copy(content = boundContent(result.content)))
        } catch (error: CancellationException) {
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
