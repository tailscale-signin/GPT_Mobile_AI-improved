package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Every physical inspection uses the same already-pinned primary and existing
 * provider/ledger/permission boundary. Only the final response reaches the chat
 * answer stream; intermediate inventories stay labeled as inspection artifacts.
 */
internal class CombinedCorpusMultipassSession(
    private val plan: CombinedCorpusMultipassPlan,
    private val system: String,
    private val limits: TokenBudgetSettings,
    private val open: suspend (List<ConversationTurn>, String, Int?) -> AgentProviderSession,
    private val onFinalContext: (List<ConversationTurn>) -> Unit
) : AgentProviderSession {
    override fun streamRound(
        tools: List<AgentToolDefinition>,
        exchanges: List<AgentToolExchange>
    ): Flow<ProviderEvent> = flow {
        if (tools.isNotEmpty() || exchanges.isNotEmpty()) {
            throw InspectionFailed("COMBINED_MULTIPASS_TOOLS: Inspection cannot execute or replay tools.")
        }
        val reports = mutableListOf<String>()
        plan.passes.forEach { pass ->
            emit(ProviderEvent.Notice("Inspecting frozen corpus ${pass.ordinal}/${plan.passes.size} with the pinned primary."))
            val physical = open(listOf(pass.turn), "combined_corpus_inspection", plan.inspectionOutputTokens)
            val report = StringBuilder()
            var finished = false
            var lengthStopped = false
            physical.streamRound(emptyList(), emptyList()).collect { event ->
                when (event) {
                    is ProviderEvent.TextDelta -> {
                        if (report.length.toLong() + event.text.length > 65_536L) {
                            throw InspectionFailed("COMBINED_MULTIPASS_REPORT_TOO_LARGE: Original source remains available.")
                        }
                        report.append(event.text)
                    }
                    is ProviderEvent.Usage -> {
                        if (event.finishReason.equals("length", true) ||
                            event.finishReason.equals("max_tokens", true)
                        ) {
                            lengthStopped = true
                        }
                    }
                    is ProviderEvent.ToolCall -> {
                        throw InspectionFailed("COMBINED_MULTIPASS_TOOL_CALL: Frozen-corpus passes are text-only.")
                    }
                    is ProviderEvent.Failed -> {
                        throw InspectionFailed("COMBINED_MULTIPASS_STAGE_FAILED: ${event.message}")
                    }
                    is ProviderEvent.Completed -> finished = true
                    else -> Unit
                }
            }
            if (!finished || lengthStopped || report.isBlank()) {
                throw InspectionFailed(
                    "COMBINED_MULTIPASS_INCOMPLETE: Inspection ${pass.ordinal}/${plan.passes.size} " +
                        "did not provide a complete visible report; original content was retained."
                )
            }
            reports += report.toString()
        }
        val finalTurns = try {
            ContextBudgetService.planFullInput(plan.finalTurns(reports), system, limits).turns
        } catch (capacity: IllegalArgumentException) {
            throw InspectionFailed(
                "COMBINED_MULTIPASS_FINAL_CONTEXT: Inspection completed but the full collected " +
                    "reports cannot fit the final primary request. The frozen corpus was not discarded."
            )
        }
        onFinalContext(finalTurns)
        emit(ProviderEvent.Notice("All ${plan.passes.size} frozen spans inspected; final answer is being written. Semantic coverage remains unverified."))
        emitAll(open(finalTurns, "synthesis_after_full_corpus", null).streamRound(emptyList(), emptyList()))
    }.catch { error ->
        if (error is CancellationException) throw error
        if (error is InspectionFailed) emit(ProviderEvent.Failed(error.message.orEmpty())) else throw error
    }

    private class InspectionFailed(message: String) : Exception(message)
}
