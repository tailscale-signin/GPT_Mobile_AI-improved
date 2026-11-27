package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.provider.modelCompatibleGitHubToolSchema
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ReplayAllowance
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import dev.chungjungsoo.gptmobile.data.context.TextTokenEstimate
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import dev.chungjungsoo.gptmobile.data.dto.openai.common.Role
import dev.chungjungsoo.gptmobile.data.dto.openai.common.TextContent
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatCompletionRequest
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatFunctionTool
import dev.chungjungsoo.gptmobile.data.dto.openai.request.ChatMessage
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import kotlinx.serialization.json.JsonPrimitive

/** Plan a fresh execution turn against the actual stage instructions, schemas, output and wire safety margin. */
internal class GitHubContinuationBudget(private val capacity: Int, private val output: Int, system: String, tools: List<AgentToolDefinition>, private val replay: Int, gatewayModel: String? = null) {
    // Measure the Gateway message/tool framing and escaped contract once. The provider still admits the final payload.
    private val fixedInput = if (gatewayModel == null) {
        ContextBudgetService.breakdown(emptyList(), system, tools).total.toLong() + 16
    } else {
        val functions = tools.map { tool ->
            val schema = if (GitHubOperationTool.operationName(tool.name) != null) modelCompatibleGitHubToolSchema(tool.inputSchema) else tool.inputSchema
            ChatFunctionTool(tool.name, tool.description, schema)
        }.takeIf { it.isNotEmpty() }
        val request = ChatCompletionRequest(
            model = gatewayModel,
            messages = listOf(ChatMessage(Role.SYSTEM, listOf(TextContent(system))), ChatMessage(Role.USER, listOf(TextContent("")))),
            tools = functions,
            toolChoice = functions?.let { "auto" },
            maxTokens = output
        )
        TextTokenEstimate.estimate(NetworkClient.openAIJson.encodeToString(request)).toLong()
    }
    private val textAndReplay = RequestAdmission.maximumEstimatedPrompt(capacity.toLong() - output) - fixedInput
    private val minimumReplay = minOf(ReplayAllowance.MINIMUM_TOOL_REPLAY, replay)

    fun textTokens(text: String) = TextTokenEstimate.estimate(JsonPrimitive(text).toString())

    fun fits(text: String) = textTokens(text) <= textAndReplay - minimumReplay

    fun fitsWithPlannedReplay(text: String) = textTokens(text) <= textAndReplay - replay

    /** The last turn's replay ceiling is a maximum. Share newly available space between saved rows and new tool evidence. */
    fun projectionTextLimit(required: String): Long {
        requireFits(required)
        val remaining = textAndReplay - textTokens(required)
        val reserved = minOf(replay.toLong(), maxOf(minimumReplay.toLong(), remaining / 2))
        return textAndReplay - reserved
    }

    fun requireFits(text: String) {
        if (fits(text)) return
        val prompt = fixedInput + textTokens(text)
        throw RequestCapacityException(
            "CONTEXT_CAPACITY_EXCEEDED",
            "The accepted GitHub task and current instruction exceed the remaining continuation context. Saved receipts are retained.",
            prompt,
            RequestAdmission.estimateSafety(prompt + minimumReplay),
            output.toLong(),
            capacity.toLong(),
            TokenMeasurementKind.UTF8_ESTIMATE,
            planningToolResultReserveTokens = minimumReplay
        )
    }
}
