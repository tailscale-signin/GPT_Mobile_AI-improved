package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.model.AgentToolResult
import dev.chungjungsoo.gptmobile.data.model.ProviderEvent
import dev.chungjungsoo.gptmobile.data.model.ToolResultContent
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class AgentRunner(
    private val limits: AgentExecutionLimits = AgentExecutionLimits()
) {

    fun run(
        session: AgentModelSession,
        tools: List<AgentTool>,
        exchanges: List<AgentExchange>,
        initialRounds: Int = 0,
        initialToolCallCount: Int = 0,
        initialToolMayHaveExecuted: Boolean = false,
        initialRetriedWithoutTools: Boolean = false,
        initialExecutableToolByName: Map<String, AgentTool> = tools.associateBy { it.definition.name },
        initialExposedDefinitions: List<AgentToolDefinition> = tools.map { it.definition }
    ): Flow<AgentRunEvent> = flow {
        var executableToolByName = initialExecutableToolByName
        var exposedDefinitions = initialExposedDefinitions
        var rounds = initialRounds
        var toolCallCount = initialToolCallCount
        var toolMayHaveExecuted = initialToolMayHaveExecuted
        var retriedWithoutTools = initialRetriedWithoutTools
        var finalResponseRequested = false
        var wrapUpNoticeEmitted = false
        val executionToolCallLimit = ToolBudgetPolicy.executionLimit(limits)

        while (true) {
            if (executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit) {
                exposedDefinitions = emptyList()
                executableToolByName = emptyMap()
                if (!finalResponseRequested) {
                    finalResponseRequested = true
                    emit(AgentRunEvent.Notice(FINAL_RESPONSE_NOTICE, persistent = false))
                }
            } else if (ToolBudgetPolicy.shouldEmitWrapUpNotice(executionToolCallLimit, limits, toolCallCount, wrapUpNoticeEmitted)) {
                wrapUpNoticeEmitted = true
                val remainingAllowance = ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount)
                emit(AgentRunEvent.Notice("Approaching tool limit ($remainingAllowance remaining). Wrapping up.", persistent = false))
            }
            if (limits.maxRounds < Int.MAX_VALUE && rounds >= limits.maxRounds) {
                emit(failed("Agent stopped after ${limits.maxRounds} model/tool rounds."))
                return@flow
            }
            rounds += 1

            val calls = mutableListOf<ProviderEvent.ToolCall>()
            var completed = false
            var failed = false
            try {
                session.streamRound(exposedDefinitions, exchanges)
                    .collect { event ->
                        if (failed) return@collect
                        when (event) {
                            is ProviderEvent.ToolCall -> {
                                if (!session.handlesToolsInternally) {
                                    calls += event
                                }
                                emit(AgentRunEvent.Provider(event))
                            }
                            is ProviderEvent.Failed -> {
                                failed = true
                                emit(AgentRunEvent.Provider(event))
                            }
                            is ProviderEvent.Completed -> {
                                completed = true
                                emit(AgentRunEvent.Provider(event))
                            }
                            else -> emit(AgentRunEvent.Provider(event))
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emit(failed(error.message ?: "Agent round execution failed."))
                return@flow
            }

            if (failed) return@flow
            if (calls.isEmpty()) {
                if (completed) return@flow
                emit(failed("Model stopped without completing response."))
                return@flow
            }

            val deferredResults = mutableListOf<AgentToolResult>()
            val executedResults = mutableListOf<AgentToolResult>()
            for (call in calls) {
                if (executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit) {
                    deferredResults += AgentToolResult(
                        callId = call.callId,
                        content = ToolResultContent.Text("Tool call skipped: tool-call limit reached."),
                        isError = true
                    )
                    continue
                }
                val tool = executableToolByName[call.name]
                if (tool == null) {
                    deferredResults += AgentToolResult(
                        callId = call.callId,
                        content = ToolResultContent.Text("Tool '${call.name}' not found."),
                        isError = true
                    )
                    continue
                }
                toolCallCount += 1
                val result = executeTool(tool, call)
                if (result.mayHaveExecuted) {
                    toolMayHaveExecuted = true
                }
                executedResults += result
            }
            val allResults = (executedResults + deferredResults).toMutableList()
            val mustFinalize = executionToolCallLimit < Int.MAX_VALUE && toolCallCount >= executionToolCallLimit
            val remainingAllowance = ToolBudgetPolicy.remainingAllowance(executionToolCallLimit, toolCallCount)
            val shouldInjectWrapUp = ToolBudgetPolicy.shouldInjectWrapUpPrompt(executionToolCallLimit, limits, toolCallCount)

            if (mustFinalize && allResults.isNotEmpty()) {
                allResults[allResults.lastIndex] = appendFinalResponseInstruction(allResults.last())
            } else if (shouldInjectWrapUp && allResults.isNotEmpty()) {
                val wrapUpPrompt = ToolBudgetPolicy.buildWrapUpPrompt(remainingAllowance)
                allResults[allResults.lastIndex] = appendInstruction(allResults.last(), wrapUpPrompt)
            }

            calls.zip(allResults).forEach { (call, result) ->
                emit(AgentRunEvent.ToolResult(call, result))
            }
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

    private suspend fun executeTool(tool: AgentTool, call: ProviderEvent.ToolCall): AgentToolResult {
        return try {
            val result = tool.execute(call.arguments)
            result.copy(content = boundContent(result.content))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AgentToolResult(
                callId = call.callId,
                content = ToolResultContent.Text(error.message ?: "Tool '${call.name}' failed without a message."),
                isError = true
            )
        }
    }

    private fun boundContent(content: ToolResultContent): ToolResultContent {
        if (limits.maxToolOutputBytes == Int.MAX_VALUE) return content
        val raw = when (content) {
            is ToolResultContent.Text -> content.text
            is ToolResultContent.Json -> Json.encodeToString(content.value)
            is ToolResultContent.ResourceLinks -> Json.encodeToString(content.links.map { it.uri })
        }
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= limits.maxToolOutputBytes) return content
        val truncated = String(bytes.copyOf(limits.maxToolOutputBytes), StandardCharsets.UTF_8)
        return ToolResultContent.Text(truncated)
    }

    private fun failed(message: String): AgentRunEvent =
        AgentRunEvent.Provider(ProviderEvent.Failed(message))

    companion object {
        const val TOOLS_UNAVAILABLE_MESSAGE: String = "Tools unavailable for this model."
        const val FINAL_RESPONSE_INSTRUCTION: String =
            "Execution allowance exhausted. Do not make any more tool calls. Synthesize your final answer now."
        const val FINAL_RESPONSE_NOTICE: String =
            "Execution allowance reached. Requesting final response; reply \"continue\" to resume."
    }
}
