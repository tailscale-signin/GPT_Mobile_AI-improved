package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredDiagnosticEventTest {
    @Test fun typedReadinessSurvivesRecorderMetadataAndExportWithoutStringBooleans() {
        val event = structuredDiagnosticEventMessage(
            "LLAMA_READINESS",
            mapOf("healthHttp" to 403, "gatewayReady" to false, "activeGenerations" to 0, "selectedModelListed" to null, "cachedRestrictedSurfaces" to listOf("health", "props"), "runId" to "run-1", "invocationId" to "invoke-1")
        )
        val stored = diagnosticMessageForStorage(event)
        val row = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:19:40Z W/LocalConnection: eventId=event-1 · monoNs=25 · bootMs=10 · processId=42 · processName=dev.app · $stored")).jsonObject
        val fields = row.getValue("fields").jsonObject
        assertEquals(403, fields.getValue("healthHttp").jsonPrimitive.int)
        assertFalse(fields.getValue("healthHttp").jsonPrimitive.isString)
        assertFalse(fields.getValue("gatewayReady").jsonPrimitive.boolean)
        assertFalse(fields.getValue("gatewayReady").jsonPrimitive.isString)
        assertEquals(JsonNull, fields.getValue("selectedModelListed"))
        assertEquals(listOf("health", "props"), fields.getValue("cachedRestrictedSurfaces").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("LLAMA_READINESS", row.getValue("eventName").jsonPrimitive.content)
        assertEquals("run-1", row.getValue("runId").jsonPrimitive.content)
        assertEquals("invoke-1", row.getValue("invocationId").jsonPrimitive.content)
        assertEquals("event-1", fields.getValue("eventId").jsonPrimitive.content)
        assertEquals(42, row.getValue("processId").jsonPrimitive.int)
    }

    @Test fun historicalSemicolonReadinessIsTypedWithoutChangingOrdinaryTextFields() {
        val row = Json.parseToJsonElement(
            structuredDiagnosticLine("2026-11-08T14:19:40Z W/LocalConnection: LLAMA_READINESS · healthHttp=403; gatewayReady=false; activeGenerations=0; cachedRestrictedSurfaces=[health, props]; phase=DEGRADED; modelsFailure=PROBE_TIMEOUT;")
        ).jsonObject
        val fields = row.getValue("fields").jsonObject
        assertEquals(JsonPrimitive(403), fields.getValue("healthHttp"))
        assertEquals(JsonPrimitive(false), fields.getValue("gatewayReady"))
        assertEquals(JsonPrimitive(0), fields.getValue("activeGenerations"))
        assertEquals(JsonPrimitive("DEGRADED"), fields.getValue("phase"))
        assertEquals(JsonPrimitive("PROBE_TIMEOUT"), fields.getValue("modelsFailure"))
        assertEquals(2, fields.getValue("cachedRestrictedSurfaces").jsonArray.size)
        val ordinary = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:19:40Z I/Example: NOTE · reason=retained punctuation;")).jsonObject
        assertEquals("retained punctuation;", ordinary.getValue("fields").jsonObject.getValue("reason").jsonPrimitive.content)
    }

    @Test fun explicitTerminalAndReceiptAliasesFillCanonicalErrorAndDispatchFields() {
        val rejected = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:19:40Z I/Agent: RUN_TERMINAL · status=FAILED · reason=REASONING_OFF_UNSUPPORTED · failedStage=provider_admission · executionState=PRECHECK_REJECTED · dispatched=false")).jsonObject
        assertEquals(JsonPrimitive("REASONING_OFF_UNSUPPORTED"), rejected["errorCode"])
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), rejected["dispatchState"])
        assertEquals(JsonPrimitive(false), rejected["dispatched"])
        val receipt = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:19:40Z I/Model: PROVIDER_RECEIPT · receipt={\"reasonCode\":\"REASONING_CAPABILITY_TIMEOUT\",\"dispatchEvidence\":\"NOT_DISPATCHED\",\"failurePhase\":\"LOCAL_INFERENCE_ADMISSION\",\"invocationId\":\"invoke-2\"}")).jsonObject
        assertEquals(JsonPrimitive("REASONING_CAPABILITY_TIMEOUT"), receipt["errorCode"])
        assertEquals(JsonPrimitive(false), receipt["dispatched"])
        assertEquals(JsonNull, receipt["osExitReason"])
        val lifecycle = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:19:40Z I/Memory: EMBEDDING_ENGINE_RELEASED · reason=explicit_release")).jsonObject
        assertEquals(JsonNull, lifecycle["errorCode"])
    }

    @Test fun githubToolReceiptLiftsRunInvocationAndToolIdentity() {
        val receipt = """{"schema":"github.dispatch.v1","run_id":"run-1","invocation_id":"invoke-1","tool_trace":{"schema":"github.trace.v1","call_id":"call-1","tool":"github_ci","action":"runs"}}"""
        val row = Json.parseToJsonElement(
            structuredDiagnosticLine("2026-11-11T12:00:00Z I/Tool: Finished · callId=call-1 · tool=github_ci · executionState=COMPLETED · executionReceipt=$receipt")
        ).jsonObject
        assertEquals(JsonPrimitive("run-1"), row["runId"])
        assertEquals(JsonPrimitive("invoke-1"), row["invocationId"])
        assertEquals(JsonPrimitive("github_ci"), row["toolName"])
        assertEquals(JsonPrimitive("call-1"), row["toolCallId"])
        assertEquals(JsonPrimitive("runs"), row["toolAction"])
    }

    @Test fun legacyToolLifecycleAndGitHubArgumentFailureKeepCanonicalIdentifiers() {
        val started = Json.parseToJsonElement(
            structuredDiagnosticLine("2026-11-11T12:00:00Z I/Tool: Started github_read__github_api · run run-legacy · call call-legacy")
        ).jsonObject
        assertEquals(JsonPrimitive("run-legacy"), started["runId"])
        assertEquals(JsonPrimitive("github_read__github_api"), started["toolName"])
        assertEquals(JsonPrimitive("call-legacy"), started["toolCallId"])

        val failed = Json.parseToJsonElement(
            structuredDiagnosticLine("2026-11-11T12:00:01Z E/GitHubTool: Request failed · callId=call-legacy · causalIncidentId=github:incident-1 · truncatedAtSource=false · GITHUB_INVALID_ARGUMENT · action=pr_context · Select pull request files, checks, or both.")
        ).jsonObject
        assertEquals(JsonPrimitive("GITHUB_INVALID_ARGUMENT"), failed["errorCode"])
        assertEquals(JsonPrimitive(false), failed.getValue("fields").jsonObject["truncatedAtSource"])
        assertEquals(JsonPrimitive("pr_context"), failed["toolAction"])
        assertEquals(JsonPrimitive("call-legacy"), failed["toolCallId"])
    }

    @Test fun privateFieldsAndUnsupportedObjectsCannotBreakOrEscapeTheStructuredRecord() {
        val event = structuredDiagnosticEventMessage(
            "READINESS",
            mapOf(
                "prompt" to "PRIVATE_PROMPT_CANARY",
                "nested" to mapOf("access_token" to "PRIVATE_TOKEN_CANARY", "ready" to false),
                "unknownObject" to object {
                    override fun toString() = "PRIVATE_OBJECT_CANARY"
                },
                "endpoint" to "http://192.168.1.8/ready?credential=PRIVATE_QUERY_CANARY"
            )
        )
        val retained = diagnosticMessageForStorage(event)
        val parsed = requireNotNull(parseStructuredDiagnosticEvent(retained))
        assertFalse(retained.contains("PRIVATE_"))
        assertFalse(retained.contains("192.168.1.8"))
        assertEquals(JsonPrimitive(false), parsed.fields.getValue("nested").jsonObject["ready"])
        assertTrue(parsed.fields.getValue("_fieldCountOmitted").jsonPrimitive.int >= 3)
    }

    @Test fun oversizedFieldsLeaveCompleteJsonAndExplicitOmissionEvidence() {
        val event = structuredDiagnosticEventMessage("BOUNDED", (1..100).associate { "field$it" to "x".repeat(1500) })
        assertTrue(event.length <= 6000)
        val parsed = requireNotNull(parseStructuredDiagnosticEvent(diagnosticMessageForStorage(event)))
        assertTrue(parsed.fields.getValue("_fieldCountOmitted").jsonPrimitive.int > 0)
        assertEquals("BOUNDED", parsed.name)
    }

    @Test fun ordinaryInformationalEventsAreNotMislabelledAsFailureSymptoms() {
        val row = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T14:19:40Z I/Lifecycle: MainActivity resumed", incidentRoot = false)).jsonObject
        assertEquals(JsonPrimitive("INFORMATIONAL"), row["classification"])
        assertEquals(JsonPrimitive(false), row["observedFailure"])
    }

    @Test fun multibyteMetadataFitsTheDurableByteRingWithoutSplittingJson() {
        val event = structuredDiagnosticEventMessage("MULTIBYTE_METADATA", (1..30).associate { "field$it" to "測定値😀".repeat(150) })
        assertTrue(event.toByteArray(Charsets.UTF_8).size <= 6000)
        val directory = java.nio.file.Files.createTempDirectory("typed-diagnostics-").toFile()
        try {
            val entry = AppLogEntry(1_000, "I", "Model", event)
            BoundedDiagnosticLogStore(directory, "current.log", "previous.log", 64 * 1024).append(entry.line())
            val line = java.io.File(directory, "current.log").readLines().single()
            val exported = Json.parseToJsonElement(structuredDiagnosticLine(line)).jsonObject
            assertEquals(JsonPrimitive("MULTIBYTE_METADATA"), exported["eventName"])
            assertFalse(line.contains("truncatedAtRing"))
            assertTrue(exported.getValue("fields").jsonObject.containsKey("_fieldCountOmitted"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
