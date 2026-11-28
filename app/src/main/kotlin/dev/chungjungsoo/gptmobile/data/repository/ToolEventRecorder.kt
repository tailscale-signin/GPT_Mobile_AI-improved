package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.agent.AgentResourceLink
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.captureIssueReport
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import dev.chungjungsoo.gptmobile.data.health.HealthConnectTools
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolEventRecorder @Inject constructor(
    private val dao: AgentPersistenceDao,
    private val agentRunDao: AgentRunDao
) {
    private var eventIdFactory: () -> String = { UUID.randomUUID().toString() }

    internal constructor(
        dao: AgentPersistenceDao,
        agentRunDao: AgentRunDao,
        eventIdFactory: () -> String
    ) : this(dao, agentRunDao) {
        this.eventIdFactory = eventIdFactory
    }

    suspend fun startTool(
        runId: String,
        sequence: Int,
        callId: String,
        toolName: String,
        modelToolName: String,
        arguments: JsonObject,
        connectionUid: String? = null,
        connectionName: String? = null,
        startedAt: Long
    ): ToolEvent {
        val event = ToolEvent(
            eventId = eventIdFactory(),
            runId = runId,
            sequence = sequence,
            callId = callId,
            connectionUidSnapshot = connectionUid,
            connectionNameSnapshot = connectionName,
            toolName = toolName,
            modelToolName = modelToolName,
            arguments = if (HealthConnectTools.isHealthTool(modelToolName) || HealthConnectTools.isHealthTool(toolName)) "{}" else arguments.toString(),
            result = null,
            resultType = null,
            status = ToolEventStatus.RUNNING,
            startedAt = startedAt
        )
        val persisted = dao.startToolEvent(event)
        if (persisted.eventId == event.eventId) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Tool", "Started $modelToolName · run $runId · call $callId")
        }
        return persisted
    }

    private fun precheckArgumentShape(arguments: String?, result: AgentToolResult): String {
        if (result.dispatched || !result.isError || arguments == null) return ""
        val keys = (runCatching { Json.parseToJsonElement(arguments) }.getOrNull() as? JsonObject)?.keys ?: return ""
        return " · argumentShapeHash=${dev.chungjungsoo.gptmobile.data.research.researchHash(keys.sorted().joinToString("\u0000"))}"
    }

    suspend fun finishTool(
        eventId: String,
        result: AgentToolResult,
        completedAt: Long,
        error: String? = null
    ): ToolEvent? {
        val startedEvent = dao.getToolEventById(eventId)
        val healthEvent = HealthConnectTools.isHealthTool(startedEvent?.modelToolName.orEmpty()) ||
            HealthConnectTools.isHealthTool(startedEvent?.toolName.orEmpty())
        val safeError = if (healthEvent && result.isError) {
            "Health Connect: ${dev.chungjungsoo.gptmobile.data.health.HealthReadFailure.safeCode(result.errorCode)}. Check the indicated permission, time range or service state."
        } else {
            error
        }
        val isDelegationEvent = startedEvent?.modelToolName == "delegate_to_model" || startedEvent?.toolName == "delegate_to_model"
        val normalizedResult = (
            if (healthEvent) {
                // traceContent alone is not enough: retainedContent otherwise becomes a recoverable checkpoint.
                result.copy(
                    content = ToolResultContent.Text(HealthConnectTools.REDACTED),
                    retainedContent = null,
                    traceContent = ToolResultContent.Text(HealthConnectTools.REDACTED)
                )
            } else if (isDelegationEvent && result.isError && result.hasSuccessfulDelegationMarker()) {
                dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                    "Delegation",
                    "Corrected contradictory outer tool error after successful delegated handoff · call=${result.callId}",
                    "W"
                )
                result.copy(isError = false)
            } else {
                result
            }
            ).let { normalized ->
            if (healthEvent) {
                dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.normalize(normalized)
            } else {
                dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.receipt(normalized, startedEvent?.modelToolName.orEmpty()).let { receipt ->
                    if (receipt.executionReceipt == null) {
                        receipt
                    } else {
                        receipt.copy(
                            executionReceipt = JsonObject(
                                receipt.executionReceipt + buildJsonObject {
                                    put("run_id", startedEvent?.runId.orEmpty())
                                    put("event_id", eventId)
                                    startedEvent?.startedAt?.let { put("started_at", it) }
                                    put("completed_at", completedAt)
                                }
                            )
                        )
                    }
                }
            }
        }
        val content = (normalizedResult.retainedContent ?: normalizedResult.content).serialized()
        val retail = runCatching { Json.parseToJsonElement(content.value) as? JsonObject }.getOrNull()
        if (retail != null && AmazonProducts.text(retail, "schema") == AmazonProducts.SCHEMA) {
            val products = (retail["products"] as? JsonArray).orEmpty()
            val unverified = (retail["unverifiedProducts"] as? JsonArray).orEmpty()
            val prices = (products + unverified).filterIsInstance<JsonObject>().count { AmazonProducts.text(it, "price") != null }
            val codes = (retail["errors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .mapNotNull { AmazonProducts.text(it, "code") }.distinct().joinToString(",")
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
                "Amazon",
                "PRODUCT_RESULT · call=${normalizedResult.callId} · status=${AmazonProducts.text(retail, "status")} · products=${products.size} · unverified=${unverified.size} · withPrice=$prices · codes=$codes"
            )
        }
        val display = (normalizedResult.traceContent ?: normalizedResult.content).serialized()
        val checkpoint = content.value != display.value || normalizedResult.personalData.isNotEmpty() || normalizedResult.executionReceipt != null
        val stored = if (checkpoint) dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint.encode(content.value, content.type, display.value, normalizedResult.personalData, normalizedResult.executionReceipt) else content.value
        val resultType = when {
            checkpoint -> ToolEventResultType.CHECKPOINT
            !normalizedResult.isError && content.value.isBlank() -> ToolEventResultType.EMPTY
            else -> content.type
        }
        val waitingForChoice = !normalizedResult.dispatched &&
            normalizedResult.errorCode == dev.chungjungsoo.gptmobile.data.agent.tool.GitHubDecisionTool.PENDING &&
            startedEvent?.takeIf { it.callId == normalizedResult.callId }?.copy(result = stored, resultType = resultType, isError = normalizedResult.isError)
                ?.let(dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint::waitingForUserChoice) == true
        val failure = normalizedResult.isError && !waitingForChoice

        val affectedRows = dao.finishToolEvent(
            eventId = eventId,
            callId = normalizedResult.callId,
            result = stored,
            resultType = resultType,
            status = when {
                waitingForChoice -> ToolEventStatus.WAITING_FOR_USER
                failure -> ToolEventStatus.FAILED
                else -> ToolEventStatus.COMPLETED
            },
            isError = normalizedResult.isError,
            completedAt = completedAt,
            error = if (failure) safeError else null,
            expectedWaitingResult = startedEvent?.takeIf(dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint::waitingForUserChoice)?.result
        )
        if (affectedRows != 1) return null
        val archivedSourceFailure = ToolReportDiagnostics.archivedSourceFailure(startedEvent, normalizedResult)
        val withheldRead = ((normalizedResult.content as? ToolResultContent.Json)?.value as? JsonObject)
            ?.get("result_delivery") == JsonPrimitive("WITHHELD_BY_RESULT_BUDGET") &&
            !normalizedResult.isError &&
            normalizedResult.outputBudgetExhausted
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "Tool",
            "Finished · runId=${startedEvent?.runId.orEmpty()} · callId=${normalizedResult.callId} · tool=${startedEvent?.toolName.orEmpty()} · error=$failure · dispatched=${normalizedResult.dispatched}" +
                " · errorCode=${normalizedResult.errorCode ?: "none"}" +
                ToolPrecheckEvidence.reasonCode(normalizedResult)?.let { " · reasonCode=$it" }.orEmpty() +
                precheckArgumentShape(startedEvent?.arguments, normalizedResult) +
                " · executionState=${dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.executionState(normalizedResult)} · retryable=${dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.retryable(normalizedResult)}" +
                (if (waitingForChoice) " · waitingForChoice=true" else "") +
                (normalizedResult.executionReceipt?.let { " · executionReceipt=$it · causalIncidentId=${(it["incident_id"] as? JsonPrimitive)?.content.orEmpty()}" }.orEmpty()) +
                if (failure) " · diagnosticPayloadRef=toolEvent:$eventId · displayPreviewTruncated=${safeError.orEmpty().length > 240} · reasonPreview=${dev.chungjungsoo.gptmobile.data.security.DiagnosticRedactor.redact(safeError.orEmpty()).take(240)}" else "",
            if (failure && !archivedSourceFailure || withheldRead) "E" else "I",
            reportCode = (
                if (withheldRead) {
                    "BUDGET_RESTRICTED"
                } else {
                    captureIssueReport { ToolReportDiagnostics.reportCode(startedEvent, normalizedResult) }
                }
                ).takeUnless { healthEvent || archivedSourceFailure || waitingForChoice },
            reportFields = if (healthEvent || archivedSourceFailure || waitingForChoice || !normalizedResult.isError && !withheldRead) {
                emptyMap()
            } else {
                buildMap<String, Any?> {
                    // Local call diagnostics must remain unavailable when the persisted call cannot supply them.
                    // The authoritative receipt above is preserved; only this report projection drops its replacements.
                    putAll(
                        normalizedResult.executionReceipt.orEmpty().filterKeys {
                            it !in setOf(
                                "run_id", "runId", "call_id", "callId", "toolCallId", "tool", "tool_name", "operation", "retryable", "requires_reconciliation",
                                "local_precheck_identity", "precheck_rejection_count", "precheck_call_hashes", "precheck_span_ms",
                                "diagnostic_area", "diagnostic_capture_mode", "run_id_hash", "call_id_hash", "tool_name_hash", "tool_action_hash",
                                "tool_action", "tool_error_code", "tool_reason_code", "tool_dispatch_phase",
                                "argument_shape_hash", "original_error_code_hash", "original_reason_code_hash",
                                "graph_index_attempt_hash", "graph_transport_kind", "graph_local_phase", "graph_local_reason", "tool_budget_bytes_available", "sqlite_constraint_kind", "url_reader_availability",
                                "url_reader_refusal", "round_catalog_discovery_available", "catalog_selector_state", "search_failure_observation",
                                "search_attempt_count", "search_dispatched_attempt_count", "search_budget_rejected_attempt_count",
                                "tool_event_persistence_operation", "tool_event_persistence_phase", "tool_event_persistence_failure",
                                "tool_event_requested_sequence", "tool_event_assigned_sequence", "tool_event_previous_max_sequence", "tool_event_write_attempted"
                            ) &&
                                it !in dev.chungjungsoo.gptmobile.data.agent.ToolRepeatAdmissionEvidence.REPORT_FIELD_NAMES &&
                                it !in dev.chungjungsoo.gptmobile.data.agent.ToolInventoryPaginationEvidence.REPORT_FIELD_NAMES &&
                                it !in dev.chungjungsoo.gptmobile.data.agent.GitHubSearchValidationEvidence.REPORT_FIELD_NAMES
                        }
                    )
                    if ("reason_code" !in this && "reasonCode" !in this) {
                        ToolPrecheckEvidence.reasonCode(result)?.let { put("reason_code", it) }
                    }
                    // These identities belong to the recorded call, never a provider-supplied replacement.
                    put("run_id", startedEvent?.runId)
                    put("call_id", normalizedResult.callId)
                    put("tool", startedEvent?.modelToolName)
                    captureIssueReport {
                        ToolReportDiagnostics.fields(startedEvent, result, ToolPrecheckEvidence.reasonCode(result))
                    }?.let { putAll(it) }
                    captureIssueReport {
                        dev.chungjungsoo.gptmobile.data.agent.ToolRepeatAdmissionEvidence.reportFields(result, startedEvent?.callId, startedEvent?.modelToolName, startedEvent?.arguments, startedEvent?.runId)
                    }?.let { putAll(it) }
                    put("dispatched", normalizedResult.dispatched)
                    if (withheldRead) put("failed_stage", "delivery")
                    result.executionReceipt?.get("retryable")?.let { put("retryable", it) }
                    if (normalizedResult.errorCode in dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.unknownOutcomeCodes) {
                        put("requires_reconciliation", true)
                    } else {
                        result.executionReceipt?.get("requires_reconciliation")?.let { put("requires_reconciliation", it) }
                    }
                }
            },
            reportSource = "TOOL_RESULT"
        )
        return dao.getToolEventById(eventId)
    }

    suspend fun applyGatewayProgress(runId: String, progress: GatewayProgress) {
        val seq = progress.sequence ?: return
        val jobId = progress.jobId ?: return
        agentRunDao.advanceGatewayJobSequence(runId, jobId, seq)
    }

    suspend fun cancelRun(runId: String, completedAt: Long) {
        dao.cancelActiveToolEvents(runId, completedAt)
    }

    suspend fun getEvents(runId: String): List<ToolEvent> = dao.getToolEventsForRun(runId)

    suspend fun getEvents(runIds: Set<String>): List<ToolEvent> {
        if (runIds.isEmpty()) return emptyList()
        return dao.getToolEvents(runIds.toList())
    }

    fun observeChat(chatId: Int): Flow<List<ToolEvent>> {
        if (chatId <= 0) return flowOf(emptyList())
        return dao.observeToolEventsForChat(chatId)
    }

    private fun AgentToolResult.hasSuccessfulDelegationMarker(): Boolean =
        (content as? ToolResultContent.Text)?.text?.trimStart()?.let { text ->
            text.startsWith("<!-- delegation:local -->") || text.startsWith("<!-- delegation:remote -->")
        } == true
    private fun ToolResultContent.serialized(): SerializedResult = when (this) {
        is ToolResultContent.Text -> SerializedResult(text, ToolEventResultType.TEXT)
        is ToolResultContent.Json -> SerializedResult(Json.encodeToString(value), ToolEventResultType.JSON)
        is ToolResultContent.ResourceLinks -> SerializedResult(links.serialize(), ToolEventResultType.RESOURCE_LINKS)
    }

    private fun List<AgentResourceLink>.serialize(): String = buildJsonArray {
        forEach { link ->
            add(
                buildJsonObject {
                    put("uri", link.uri)
                    if (link.name == null) {
                        put("name", JsonNull)
                    } else {
                        put("name", link.name)
                    }
                    if (link.mimeType == null) {
                        put("mimeType", JsonNull)
                    } else {
                        put("mimeType", link.mimeType)
                    }
                }
            )
        }
    }.toString()

    private data class SerializedResult(val value: String, val type: String)
}
