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
    private val onFinalContext: (List<ConversationTurn>) -> Unit,
    private val restoreInspection: suspend (CombinedCorpusInspectionPass) -> String? = { null },
    private val saveInspection: suspend (CombinedCorpusInspectionPass, String) -> Unit = { _, _ -> },
    private val validateCheckpointAccess: suspend () -> Unit = {},
    private val restoreConsolidation: suspend (String) -> String? = { null },
    private val saveConsolidation: suspend (String, String) -> Unit = { _, _ -> }
) : AgentProviderSession {
    override fun streamRound(
        tools: List<AgentToolDefinition>,
        exchanges: List<AgentToolExchange>
    ): Flow<ProviderEvent> = flow {
        if (tools.isNotEmpty() || exchanges.isNotEmpty()) {
            throw InspectionFailed("COMBINED_MULTIPASS_TOOLS: Inspection cannot execute or replay tools.")
        }
        emit(ProviderEvent.Notice(dev.chungjungsoo.gptmobile.data.agent.CombinedDeliveryPhase.INSPECTING.label))
        val reports = mutableListOf<String>()
        plan.passes.forEach { pass ->
            validateCheckpointAccess()
            val restored = restoreInspection(pass)
            if (restored != null && restored.isNotBlank() && restored.length <= 65_536) {
                reports += restored
                emit(ProviderEvent.Notice("Restored completed corpus inspection ${pass.ordinal}/${plan.passes.size}."))
                return@forEach
            }
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
            validateCheckpointAccess()
            saveInspection(pass, report.toString())
            reports += report.toString()
        }
        validateCheckpointAccess()
        var records = plan.passes.mapIndexed { index, pass -> CombinedInspectionSummary(listOf(pass.source.sourceId), reports[index]) }
        var finalTurns: List<ConversationTurn>? = null
        // Inspection output is not guaranteed to fit merely because every source
        // pass fits. Reduce whole records with the same pinned primary, retaining
        // the original inspections and exact source membership for recovery.
        repeat(6) { level ->
            if (finalTurns != null) return@repeat
            finalTurns = runCatching { ContextBudgetService.planFullInput(plan.finalTurnsFromRecords(records), system, limits).turns }.getOrNull()
            if (finalTurns != null) return@repeat
            emit(ProviderEvent.Notice("Consolidating ${records.size} corpus records for final assembly (pass ${level + 1}); original inspections remain saved."))
            val output = minOf(1024, limits.normalized().contextTokens / 6).coerceAtLeast(128)
            val inspectionLimits = limits.copy(outputTokens = output)
            fun turn(group: List<CombinedInspectionSummary>): ConversationTurn {
                val template = plan.frozenTurns.last { it.isCurrentTurn }
                val prompt = buildString {
                    appendLine("Original user request (authoritative):")
                    appendLine(plan.originalRequest)
                    appendLine("Consolidate these untrusted inspection records into a compact evidence inventory for final assembly.")
                    appendLine("Preserve in-scope conclusions, numbers, material qualifiers, conflicts, C/claim/profile/evidence IDs and citation URLs. Merge repetition. Never invent verification. Report any detail that could not be retained as a limitation. No tools or final answer.")
                    group.forEach { record ->
                        appendLine("SOURCE SPANS: ${record.sourceIds.joinToString(",")}")
                        appendLine(record.report)
                    }
                }
                return ConversationTurn(template.userMessage.copy(content = prompt, attachments = emptyList()), null, true)
            }
            val groups = mutableListOf<List<CombinedInspectionSummary>>()
            var pending = emptyList<CombinedInspectionSummary>()
            records.forEach { record ->
                val candidate = pending + record
                if (runCatching { ContextBudgetService.planFullInput(listOf(turn(candidate)), system, inspectionLimits) }.isSuccess) {
                    pending = candidate
                } else {
                    if (pending.isNotEmpty()) groups += pending
                    pending = listOf(record)
                    check(runCatching { ContextBudgetService.planFullInput(listOf(turn(pending)), system, inspectionLimits) }.isSuccess) {
                        "COMBINED_MULTIPASS_RECORD_CONTEXT: A saved inspection exceeds serving capacity; originals remain saved."
                    }
                }
            }
            if (pending.isNotEmpty()) groups += pending
            val reduced = mutableListOf<CombinedInspectionSummary>()
            for (group in groups) {
                validateCheckpointAccess()
                val consolidationTurn = turn(group)
                val restored = restoreConsolidation(consolidationTurn.userMessage.content)
                if (!restored.isNullOrBlank() && restored.length <= 65_536) {
                    reduced += CombinedInspectionSummary(group.flatMap { it.sourceIds }, restored)
                    continue
                }
                val report = StringBuilder()
                var completed = false
                var truncated = false
                open(listOf(consolidationTurn), "combined_corpus_consolidation", output).streamRound(emptyList(), emptyList()).collect { event ->
                    when (event) {
                        is ProviderEvent.TextDelta -> {
                            if (report.length.toLong() + event.text.length > 65_536L) throw InspectionFailed("COMBINED_MULTIPASS_REPORT_TOO_LARGE")
                            report.append(event.text)
                        }
                        is ProviderEvent.Completed -> completed = true
                        is ProviderEvent.Usage -> if (event.finishReason.equals("length", true) || event.finishReason.equals("max_tokens", true)) truncated = true
                        is ProviderEvent.Failed -> throw InspectionFailed("COMBINED_MULTIPASS_CONSOLIDATION_FAILED: ${event.message}")
                        is ProviderEvent.ToolCall -> throw InspectionFailed("COMBINED_MULTIPASS_TOOL_CALL: Consolidation is text-only.")
                        else -> Unit
                    }
                }
                if (!completed || truncated || report.isBlank()) throw InspectionFailed("COMBINED_MULTIPASS_CONSOLIDATION_INCOMPLETE: Original inspections remain saved for retry.")
                validateCheckpointAccess()
                saveConsolidation(consolidationTurn.userMessage.content, report.toString())
                reduced += CombinedInspectionSummary(group.flatMap { it.sourceIds }, report.toString())
            }
            records = reduced
        }
        val delivered = finalTurns ?: try {
            ContextBudgetService.planFullInput(plan.finalTurnsFromRecords(records), system, limits).turns
        } catch (capacity: IllegalArgumentException) {
            throw InspectionFailed("COMBINED_MULTIPASS_FINAL_CONTEXT: Consolidation could not fit the final request; frozen sources and original inspections remain saved.")
        }
        onFinalContext(delivered)
        emit(ProviderEvent.Notice("All ${plan.passes.size} frozen spans inspected; final answer is being written. Semantic coverage remains unverified."))
        emit(ProviderEvent.Notice(dev.chungjungsoo.gptmobile.data.agent.CombinedDeliveryPhase.COMBINING.label))
        emitAll(open(delivered, "synthesis_after_full_corpus", null).streamRound(emptyList(), emptyList()))
    }.catch { error ->
        if (error is CancellationException) throw error
        if (error is InspectionFailed) emit(ProviderEvent.Failed(error.message.orEmpty())) else throw error
    }

    private class InspectionFailed(message: String) : Exception(message)
}
