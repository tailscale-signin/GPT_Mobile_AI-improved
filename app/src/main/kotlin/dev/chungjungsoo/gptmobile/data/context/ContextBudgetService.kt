package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.incrementalSynthesis
import kotlinx.coroutines.asContextElement
import kotlinx.serialization.Serializable

/** Zero outputTokens means no explicit user output cap; dispatch still admits a finite per-request context reserve. */
@Serializable
data class TokenBudgetSettings(val contextTokens: Int = Int.MAX_VALUE, val outputTokens: Int = 0, val totalRunTokens: Int = Int.MAX_VALUE, val profileContextCeilings: Map<String, Int> = emptyMap(), val generationTimeoutMinutes: Int = 30, val responseCharacterLimit: Int = 1_048_576, val repetitionGuardEnabled: Boolean = true, val adaptiveToolCatalog: Boolean = true) {
    fun normalized() = copy(contextTokens = if (contextTokens == 0 || contextTokens == Int.MAX_VALUE) Int.MAX_VALUE else contextTokens.coerceIn(256, 1048576), outputTokens = outputTokens.coerceAtLeast(0), totalRunTokens = if (totalRunTokens == 0 || totalRunTokens == Int.MAX_VALUE) Int.MAX_VALUE else totalRunTokens.coerceIn(4096, 2097152), generationTimeoutMinutes = generationTimeoutMinutes.coerceIn(0, 1440), responseCharacterLimit = if (responseCharacterLimit == 0) 0 else responseCharacterLimit.coerceIn(4096, 16_777_216))
}

data class ContextPlan(val turns: List<ConversationTurn>, val system: String, val tools: List<AgentToolDefinition>, val toolResultBytes: Int, val outputTokens: Int?, val notice: String, val promptTokens: Int = 0, val multipass: CombinedCorpusMultipassPlan? = null, val authorizedTools: List<AgentToolDefinition> = tools, val toolOmissions: Map<String, ToolOmissionReason> = emptyMap(), val stagedGitHubOperations: List<String> = emptyList(), val capacity: PlannedContextCapacity? = null)

enum class ToolOmissionReason { NOT_NEEDED, USER_DISABLED, UNSUPPORTED, CONTEXT_BUDGET, REQUIRED_UNAVAILABLE }

/** Conservative byte-based estimates. Provider-reported counts remain the accounting authority. */
object ContextBudgetService {
    private val voiceSchemaCosts = ThreadLocal<Map<AgentToolDefinition, Int>?>()
    internal fun withVoiceSchemaCosts(costs: Map<AgentToolDefinition, Int>) = voiceSchemaCosts.asContextElement(costs)
    internal fun toolSchemaTokens(tool: AgentToolDefinition): Int = voiceSchemaCosts.get()?.get(tool) ?: estimate(tool.name + tool.description + tool.inputSchema.toString())

    fun estimate(text: String): Int = TextTokenEstimate.estimate(text)

    /** Combined's frozen history is required input, so ordinary history-window eviction is forbidden. */
    fun planFullInput(turns: List<ConversationTurn>, system: String, settings: TokenBudgetSettings): ContextPlan {
        val config = settings.normalized()
        val cost = breakdown(turns, system, emptyList())
        val outputLimit = config.outputTokens.takeIf { it > 0 }
        val outputReserve = outputLimit ?: minOf(4096, config.contextTokens / 4)
        val safetyReserve = minOf(256, config.contextTokens / 16)
        val required = cost.total.toLong() + outputReserve + safetyReserve
        require(config.contextTokens == Int.MAX_VALUE || required <= config.contextTokens.toLong()) {
            "COMBINED_CONTEXT_CAPACITY: The complete frozen input needs approximately ${cost.total} prompt tokens, " +
                "$outputReserve output tokens and $safetyReserve safety tokens; capacity is ${config.contextTokens}. " +
                "No history or contribution was clipped and no substitute primary was selected. " +
                "Increase the pinned primary's context capacity or reduce the selected input explicitly. " +
                "Full-corpus multipass is not yet available."
        }
        return ContextPlan(
            turns = turns,
            system = system,
            tools = emptyList(),
            toolResultBytes = 0,
            outputTokens = outputLimit,
            notice = "Combined workflow: FULL_INPUT. Complete frozen history and contributions delivered; " +
                "${cost.total} estimated prompt tokens. Delivery does not establish semantic coverage.",
            promptTokens = cost.total,
            capacity = PlannedContextCapacity(config.contextTokens, if (config.contextTokens == Int.MAX_VALUE) CapacityProvenance.UNKNOWN else CapacityProvenance.DECLARED)
        )
    }

    /** Keep strict full-input planning available while allowing an explicit, bounded fallback. */
    fun planFullInputOrMultipass(
        turns: List<ConversationTurn>,
        system: String,
        settings: TokenBudgetSettings,
        combined: dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext? = null
    ): ContextPlan =
        try {
            planFullInput(turns, system, settings)
        } catch (overflow: IllegalArgumentException) {
            if (combined?.requested?.let { it.singlePass && !it.incrementalSynthesis } == true || overflow.message?.startsWith("COMBINED_CONTEXT_CAPACITY:") != true) throw overflow
            val stages = CombinedCorpusMultipass.plan(turns, system, settings, combined)
            ContextPlan(
                turns = turns,
                system = system,
                tools = emptyList(),
                toolResultBytes = 0,
                outputTokens = settings.normalized().outputTokens.takeIf { it > 0 },
                notice = "Combined workflow: FULL_CORPUS_MULTIPASS; ${stages.passes.size} exact text inspections by the pinned primary. " +
                    "Every frozen span remains accessible; semantic coverage is not assumed.",
                promptTokens = stages.passes.first().estimatedPromptTokens,
                multipass = stages,
                capacity = PlannedContextCapacity(settings.normalized().contextTokens, CapacityProvenance.DECLARED)
            )
        }

    fun breakdown(turns: List<ConversationTurn>, system: String, tools: List<AgentToolDefinition>): ContextCostBreakdown = ContextCostBreakdown(
        system = estimate(system),
        toolSchemas = tools.sumOf { toolSchemaTokens(it).toLong() + 16 }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        currentMessage = turns.filter { it.isCurrentTurn }.sumOf { estimate(it.userMessage.content).toLong() + estimate(it.assistantMessage?.content.orEmpty()) + 16 }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        history = turns.filterNot { it.isCurrentTurn }.sumOf { estimate(it.userMessage.content).toLong() + estimate(it.assistantMessage?.content.orEmpty()) + 16 }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        attachments = turns.sumOf { (it.userMessage.attachments.size.toLong() + (it.assistantMessage?.attachments?.size ?: 0)) * 2048 }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    )
    fun plan(turns: List<ConversationTurn>, system: String, tools: List<AgentToolDefinition>, settings: TokenBudgetSettings, toolResultReserveCap: Int? = null, planningProvenance: CapacityProvenance = CapacityProvenance.UNKNOWN, safetyReserveTokens: Int? = null, reserveEstimatedSafety: Boolean = false, continuityRequiredSchemas: Set<String>? = null): ContextPlan {
        val config = settings.normalized()
        val outputLimit = config.outputTokens.takeIf { it > 0 }
        if (config.contextTokens == Int.MAX_VALUE) {
            return ContextPlan(
                turns,
                system,
                tools,
                256 * 1024,
                outputLimit,
                "Context: no app-imposed limit. The provider or local model's actual context capacity still applies.",
                promptTokens = breakdown(turns, system, tools).total,
                capacity = PlannedContextCapacity(config.contextTokens, planningProvenance)
            )
        }
        val requestedOutput = outputLimit ?: minOf(4096, config.contextTokens / 4)
        val resultReserve = if (tools.isEmpty()) 0 else minOf(config.contextTokens / 8, 8192, toolResultReserveCap?.coerceAtLeast(0) ?: Int.MAX_VALUE)
        val safety = safetyReserveTokens ?: minOf(256, config.contextTokens / 16)
        fun cost(turn: ConversationTurn): Int {
            val attachments = turn.userMessage.attachments.size.toLong() + (turn.assistantMessage?.attachments?.size ?: 0)
            return (estimate(turn.userMessage.content).toLong() + estimate(turn.assistantMessage?.content.orEmpty()) + attachments * 2048 + 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val current = turns.filter { it.isCurrentTurn }
        val currentTokens = estimate(system).toLong() + current.sumOf { cost(it).toLong() }
        val inputSafety = if (reserveEstimatedSafety) maxOf(RequestAdmission.estimateSafety(currentTokens + resultReserve), safetyReserveTokens?.toLong() ?: 0L) else safety.toLong()
        val headroom = config.contextTokens.toLong() - currentTokens - resultReserve - inputSafety
        val output = if (outputLimit == null && headroom >= minOf(requestedOutput, 512)) {
            minOf(requestedOutput.toLong(), headroom).toInt()
        } else {
            requestedOutput
        }
        if (output > headroom) {
            throw RequestCapacityException(
                "CONTEXT_CAPACITY_EXCEEDED",
                "The current message, system instructions and reserved output exceed the planning context.",
                currentTokens,
                inputSafety,
                output.toLong(),
                config.contextTokens.toLong(),
                TokenMeasurementKind.UTF8_ESTIMATE,
                planningToolResultReserveTokens = resultReserve,
                planningInput = RequestCapacityException.PlanningInput(
                    breakdown(current, system, emptyList()),
                    if (config.contextTokens == settings.contextTokens) planningProvenance else CapacityProvenance.UNKNOWN
                )
            )
        }
        val promptLimit = if (reserveEstimatedSafety) {
            minOf(RequestAdmission.maximumEstimatedPrompt(config.contextTokens.toLong() - output), config.contextTokens.toLong() - output - (safetyReserveTokens ?: 0)).minus(resultReserve).coerceAtLeast(0).toInt()
        } else {
            config.contextTokens - output - resultReserve - safety
        }
        var used = currentTokens.toInt()
        val historical = turns.filterNot { it.isCurrentTurn }
        val question = current.lastOrNull()?.userMessage?.content.orEmpty()
        val requiredSchemaCost = tools.filter { it.name in continuityRequiredSchemas.orEmpty() }
            .sumOf { toolSchemaTokens(it).toLong() + 16 }
        val continuity = if (continuityRequiredSchemas == null) {
            null
        } else {
            MemoryTokenWindow.continuityTurn(
                historical,
                minOf(1024L, config.contextTokens / 8L, (promptLimit.toLong() - used - requiredSchemaCost).coerceAtLeast(0)).toInt(),
                question,
                ::cost
            )
        }
        val continuityReserve = continuity?.let(::cost) ?: 0
        val selectedTools = tools.filter { tool ->
            val size = toolSchemaTokens(tool) + 16
            (size <= promptLimit - used - continuityReserve).also { if (it) used += size }
        }
        val historyCandidates = historical.mapIndexed { index, turn -> if (index == historical.lastIndex) continuity ?: turn else turn }
        val history = MemoryTokenWindow.forQuestion(historyCandidates, promptLimit - used, question, ::cost)
        used += history.sumOf(::cost)
        val omitted = turns.size - current.size - history.size
        return ContextPlan(
            history + current,
            system,
            selectedTools,
            resultReserve * 3,
            outputLimit ?: output.takeIf { it < requestedOutput },
            "Context estimate: $used prompt tokens, $output output reserved, $resultReserve tool-result tokens reserved. $omitted earlier turns and ${tools.size - selectedTools.size} tools omitted. Attachment costs are estimates; server limits may differ." +
                if (continuity != null && continuity !== historical.lastOrNull() && continuity in history) " The previous response is a labelled partial excerpt; its saved original is unchanged." else "",
            promptTokens = used,
            toolOmissions = tools.filterNot { it in selectedTools }.associate { it.name to ToolOmissionReason.CONTEXT_BUDGET },
            capacity = PlannedContextCapacity(config.contextTokens, planningProvenance)
        )
    }
}
