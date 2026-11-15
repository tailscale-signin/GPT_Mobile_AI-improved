package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphAdmissionException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphHttpFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReportIncidentProjectionTest {
    @Test fun savedIndexPublicAliasRequiresTheExactPersistedCallAndTypedPriorCheckpointReceipt() = runBlocking {
        val event = event("""{"action":"index"}""").copy(modelToolName = "repository_graph__selected")
        val tool = RepositoryGraphTool(event.modelToolName) {
            throw GatewayGraphOperationException(GatewayGraphAdmissionException("GRAPH_INDEX_OUTCOME_UNKNOWN", "private earlier request", "CHECK_SAVED_INDEX"), "private-host", "private name", "CHECK_SAVED_INDEX", true)
        }
        val result = tool.execute(event.callId, buildJsonObject { put("action", "index") })
        assertEquals("RECONCILIATION_REQUIRED", ToolReportDiagnostics.reportCode(event, result))
        assertEquals("GRAPH_INDEX_OUTCOME_UNKNOWN", result.errorCode)
        assertFalse(result.dispatched)
        val original = requireNotNull(result.executionReceipt)
        for (wrongEvent in listOf(
            null,
            event.copy(callId = "other-call"),
            event.copy(modelToolName = "repository_graph_other"),
            event.copy(modelToolName = "github_context"),
            event.copy(arguments = """{"action":"status"}"""),
            event.copy(arguments = "{incomplete"),
            event.copy(arguments = " ".repeat(65_537))
        )) {
            assertNull(ToolReportDiagnostics.reportCode(wrongEvent, result))
        }
        for (key in listOf("schema", "call_id", "error_code", "phase", "dispatched", "retryable", "index_outcome_unknown", "requires_reconciliation", "repair_action", "recovery_action")) {
            assertNull(key, ToolReportDiagnostics.reportCode(event, result.copy(executionReceipt = JsonObject(original - key))))
        }
        for ((key, value) in listOf(
            "schema" to JsonPrimitive("github.dispatch.v1"),
            "call_id" to JsonPrimitive("another-call"),
            "phase" to JsonPrimitive("OUTCOME_UNKNOWN"),
            "dispatched" to JsonPrimitive(true),
            "retryable" to JsonPrimitive(true),
            "index_outcome_unknown" to JsonPrimitive(false),
            "requires_reconciliation" to JsonPrimitive(false),
            "repair_action" to JsonPrimitive("RESTORE_SELECTED_GITHUB_CONNECTION")
        )) {
            assertNull(key, ToolReportDiagnostics.reportCode(event, result.copy(executionReceipt = JsonObject(original + (key to value)))))
        }
        assertNull(ToolReportDiagnostics.reportCode(event, result.copy(dispatched = true)))
        assertNull(ToolReportDiagnostics.reportCode(event, result.copy(isError = false)))
        assertEquals("REPOSITORY_GRAPH_UNAVAILABLE", ToolReportDiagnostics.reportCode(event, result.copy(errorCode = "REPOSITORY_GRAPH_UNAVAILABLE")))
        assertNull(ToolReportDiagnostics.reportCode(event, result.copy(executionReceipt = null, content = ToolResultContent.Text("GRAPH_INDEX_OUTCOME_UNKNOWN CHECK_SAVED_INDEX requires_reconciliation=true"))))
        assertEquals(original, result.executionReceipt)
    }

    @Test fun http500IndexReportsExactBoundedGraphReasonWithoutDisclosingGatewayIdentity() = runBlocking {
        val event = event("""{"action":"index"}""").copy(modelToolName = "repository_graph__selected")
        val attempted = "a".repeat(64)
        val tool = RepositoryGraphTool(event.modelToolName) {
            throw GatewayGraphOperationException(
                GatewayGraphHttpFailure(500),
                "private-gateway-host",
                "private gateway name",
                "CHECK_SAVED_INDEX",
                true,
                indexAttemptHash = attempted,
                currentIndexOutcomeUnknown = true
            )
        }
        val result = tool.execute(event.callId, buildJsonObject { put("action", "index") })
        val fields = ToolReportDiagnostics.fields(event, result)
        assertEquals("REPOSITORY_GRAPH_UNAVAILABLE", result.errorCode)
        assertEquals("GRAPH_HTTP_REJECTED", fields["tool_reason_code"])
        val report = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(JsonPrimitive("GRAPH_HTTP_REJECTED"), report.values["tool_reason_code"])
        assertEquals(JsonPrimitive(attempted), report.values["graph_index_attempt_hash"])
        assertFalse(report.toString().contains("private-gateway"))
        assertFalse(ToolReportDiagnostics.fields(event.copy(callId = "different-call"), result).containsKey("tool_reason_code"))
        val forged = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) + ("call_id" to JsonPrimitive("other-call"))))
        assertFalse(ToolReportDiagnostics.fields(event, forged).containsKey("tool_reason_code"))
        val forgedReason = result.copy(executionReceipt = JsonObject(requireNotNull(result.executionReceipt) + ("reason_code" to JsonPrimitive("UNTRUSTED_GRAPH_CAUSE"))))
        assertFalse(ToolReportDiagnostics.fields(event, forgedReason).containsKey("tool_reason_code"))
    }

    @Test fun transportAndSavedCursorRejectionsKeepTypedReasonsWithoutArgumentValues() {
        val event = event("""{"action":"status","per_page":101,"view_cursor":"private-cursor"}""").copy(modelToolName = "github_context")
        val result = AgentToolResult(
            event.callId,
            ToolResultContent.Text("private error"),
            true,
            errorCode = "INVALID_ARGUMENTS",
            executionReceipt = buildJsonObject {
                put("reason_code", "SAVED_VIEW_CONTEXT_MISMATCH")
                put("github_transport_kind", "DNS")
                put("github_transport_wait_ms", 30000)
            }
        )
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", ToolReportDiagnostics.fields(event, result), 1_800_000_000_000))
        assertEquals(JsonPrimitive("SAVED_VIEW_CONTEXT_MISMATCH"), snapshot.values["tool_reason_code"])
        assertEquals(JsonPrimitive("DNS"), snapshot.values["github_transport_kind"])
        assertEquals(JsonPrimitive(101), snapshot.values["argument_page_size"])
        assertFalse(snapshot.toString().contains("private"))
    }

    @Test fun persistedCallAndTypedResolvedReceiptProvideSafeActionShapesWithoutPrivateValues() {
        val arguments = buildJsonObject {
            put("action", "create")
            put("issue_number", "private-wrong-type")
            put("private_field", "private-token")
        }
        val event = event(arguments.toString())
        val receipt = buildJsonObject {
            put(
                "resolved_arguments",
                buildJsonObject {
                    put("action", "create_issue")
                    put("title", "private-title")
                    put("body", "private-body")
                }
            )
            put("invalid_fields", JsonArray(listOf(JsonPrimitive("issue_number"), JsonPrimitive("private_field"))))
        }
        val result = AgentToolResult(event.callId, ToolResultContent.Text("private-result"), true, errorCode = "INVALID_ARGUMENTS", executionReceipt = receipt)
        val fields = ToolReportDiagnostics.fields(event, result)
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
        assertEquals(3, snapshot.schemaVersion)
        assertEquals(JsonPrimitive("create"), snapshot.values["native_tool_action"])
        assertEquals(JsonArray(listOf(JsonPrimitive("action"), JsonPrimitive("issue_number"))), snapshot.values["argument_field_names"])
        assertEquals(JsonArray(listOf(JsonPrimitive("STRING"), JsonPrimitive("STRING"))), snapshot.values["argument_field_types"])
        assertEquals(JsonArray(listOf(JsonPrimitive("issue_number"))), snapshot.values["validation_field_names"])
        assertTrue(snapshot.values["resolved_argument_shape_hash"] is JsonPrimitive)
        assertFalse(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).contains("private"))
        assertEquals(receipt, result.executionReceipt)
    }

    @Test fun unknownActionAndMissingOrWrongCallDoNotInventSchemaEvidence() {
        val event = event("""{"action":"private-action","private_field":"private-value"}""")
        val result = AgentToolResult(event.callId, ToolResultContent.Text("private-result"), true, errorCode = "INVALID_ARGUMENTS")
        val fields = ToolReportDiagnostics.fields(event, result)
        assertNull(fields["native_tool_action"])
        assertNull(fields["argument_field_names"]?.let { (it as List<*>).firstOrNull { name -> name == "private_field" } })
        assertTrue(ToolReportDiagnostics.fields(null, result).isEmpty())
        assertTrue(ToolReportDiagnostics.fields(event, result.copy(callId = "other-call")).isEmpty())
    }

    @Test fun dnsCooldownProjectsWithoutTheRequestedUrl() {
        val event = event("""{"url":"https://example.com/private-source"}""").copy(modelToolName = "read_url")
        val receipt = buildJsonObject { put("schema", "gptmobile.search-operation.v1") }
        val result = AgentToolResult(event.callId, ToolResultContent.Text("private-source"), true, errorCode = "DNS_RECOVERY", executionReceipt = receipt, dispatched = false)
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", ToolReportDiagnostics.fields(event, result), 1_800_000_000_000))
        assertEquals(JsonPrimitive("RECOVERY_COOLDOWN"), snapshot.values["url_reader_dns_state"])
        assertEquals(JsonPrimitive("DNS_RESOLUTION_FAILED"), snapshot.values["tool_error_code"])
        assertFalse(snapshot.toString().contains("private-source"))
        assertTrue("RECOVERY_OBSERVATIONS_V1" in snapshot.requiredServiceFeatures())
    }

    @Test fun missingUrlProjectionDistinguishesNetworkFromNegativeCache() {
        val event = event("""{"url":"https://example.com/private-source"}""").copy(modelToolName = "read_url")
        for (dispatched in listOf(true, false)) {
            val receipt = buildJsonObject {
                put("schema", "gptmobile.search-operation.v1")
                put("provider", "read_url")
                put("callId", event.callId)
                put("errorCode", "SOURCE_UNAVAILABLE")
                put("requestSent", dispatched)
                if (dispatched) put("httpStatus", 404) else put("source_availability_observation", "NEGATIVE_CACHE")
            }
            val result = AgentToolResult(event.callId, ToolResultContent.Text("private-source"), true, errorCode = "SOURCE_UNAVAILABLE", executionReceipt = receipt, dispatched = dispatched)
            val fields = ToolReportDiagnostics.fields(event, result)
            val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("TOOL_RESULT", fields, 1_800_000_000_000))
            assertEquals(JsonPrimitive(if (dispatched) "HTTP_UNAVAILABLE" else "NEGATIVE_CACHE"), snapshot.values["url_reader_availability"])
            assertTrue("URL_READER_AVAILABILITY_V1" in snapshot.requiredServiceFeatures())
            assertFalse(snapshot.toString().contains("private-source"))
            assertNull(ToolReportDiagnostics.fields(event.copy(callId = "other-call"), result)["url_reader_availability"])
        }
    }

    private fun event(arguments: String) = ToolEvent("private-event", "private-run", 1, "private-call", null, null, "github_issue", "github_issue", arguments, null, null, "FAILED")
}
