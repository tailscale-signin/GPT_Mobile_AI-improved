package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolResultEnvelopeTest {
    @Test fun structuredAndWireReceiptsPreserveRawEscapedValuesAndExecutionSemantics() {
        val content = buildJsonObject {
            put("quoted", "Acme \"Special\"")
            put("path", "folder\\project")
            put("multiline", "first\nsecond")
        }
        val cases = listOf(
            Triple<String?, Boolean, String>(null, true, "COMPLETED"),
            Triple("PRIVACY_SERVICE_UNVERIFIED", false, "PRECHECK_REJECTED"),
            Triple("TOOL_OUTCOME_UNKNOWN", true, "UNKNOWN_OUTCOME")
        )
        for ((code, dispatched, expectedState) in cases) {
            val result = AgentToolResult(
                "owned-call",
                ToolResultContent.Json(content),
                isError = code != null,
                errorCode = code,
                dispatched = dispatched,
                executionReceipt = buildJsonObject {
                    put("call_id", "owned-call")
                    put("retained_remote_continuations", "kept only on device")
                }
            )
            val structured = ToolResultEnvelope.document("read_file_slice", result)
            val wire = Json.parseToJsonElement(ToolResultEnvelope.encode("read_file_slice", result)).jsonObject
            assertEquals(structured, wire)
            assertEquals(content, structured["data"])
            assertEquals(content, wire["data"])
            assertEquals(JsonPrimitive("gptmobile.tool-result.v1"), wire["schema"])
            assertEquals(JsonPrimitive("owned-call"), wire["callId"])
            val execution = wire.getValue("execution").jsonObject
            assertEquals(JsonPrimitive(expectedState), execution["state"])
            assertEquals(JsonPrimitive(dispatched), execution["dispatched"])
            assertEquals(JsonPrimitive("owned-call"), execution.getValue("receipt").jsonObject["call_id"])
            assertFalse(execution.getValue("receipt").jsonObject.containsKey("retained_remote_continuations"))
            if (code != null) assertEquals(JsonPrimitive(code), wire.getValue("error").jsonObject["code"])
        }
    }
}
