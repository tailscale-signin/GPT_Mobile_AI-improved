package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReportIncidentProjectionTest {
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

    private fun event(arguments: String) = ToolEvent("private-event", "private-run", 1, "private-call", null, null, "github_issue", "github_issue", arguments, null, null, "FAILED")
}
