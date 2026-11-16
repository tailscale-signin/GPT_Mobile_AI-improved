package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolInventoryPaginationEvidenceTest {
    private val fields = ToolInventoryPaginationEvidence.REPORT_FIELD_NAMES

    @Test fun actualCatalogPreservesContinuationAndOneRestartWhileDistinguishingBothRefusals() = runBlocking {
        val catalog = catalog()
        val first = catalog.execute("first-page", JsonObject(emptyMap()))
        val continuation = payload(first).getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
        val continued = catalog.execute("next-page", continuation)
        assertFalse(continued.isError)
        assertNull(continued.inventoryPaginationEvidence)

        val missing = JsonObject(continuation - "inventory_version")
        val firstRefusal = catalog.execute("missing-version", missing)
        assertEquals(mapOf("inventory_pagination_reason" to "MISSING_VERSION", "inventory_restart_offered" to true), project(firstRefusal, missing))
        assertEquals(JsonPrimitive(true), payload(firstRefusal)["restart_offered"])
        assertEquals(JsonObject(emptyMap()), payload(firstRefusal).getValue("correction_call").jsonObject["arguments"])

        val mismatched = JsonObject(continuation + ("inventory_version" to JsonPrimitive("private-former-version")))
        val secondRefusal = catalog.execute("changed-version", mismatched)
        assertEquals(mapOf("inventory_pagination_reason" to "VERSION_MISMATCH", "inventory_restart_offered" to false), project(secondRefusal, mismatched))
        assertEquals(JsonPrimitive(false), payload(secondRefusal)["restart_offered"])
        assertFalse("correction_call" in payload(secondRefusal))
        for (result in listOf(firstRefusal, secondRefusal)) {
            assertEquals("STALE_INVENTORY", result.errorCode)
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("retryable"))
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("permissions_changed"))
            assertTrue(fields.none { it in result.executionReceipt.orEmpty() })
        }
    }

    @Test fun sameCallNormalizationCopiesPreservePrivateEvidenceWithoutChangingPublicCategory() = runBlocking {
        val arguments = mismatchArguments()
        val original = catalog().execute("owned-call", arguments)
        val normalized = ToolFailureMetadata.receipt(AgentContractValidation.receipt(original.callId, original.copy()), ProgressiveToolCatalog.NAME)
        assertSame(original.inventoryPaginationEvidence, normalized.inventoryPaginationEvidence)
        val event = event(original.callId, arguments)
        val observation = ToolReportDiagnostics.fields(event, normalized)
        assertEquals("VERSION_MISMATCH", observation["inventory_pagination_reason"])
        assertEquals(true, observation["inventory_restart_offered"])
        assertEquals(ToolReportDiagnostics.reportCode(event, normalized.copy(inventoryPaginationEvidence = null)), ToolReportDiagnostics.reportCode(event, normalized))
        assertEquals(project(normalized, arguments), project(normalized, JsonObject(arguments.entries.reversed().associate { it.toPair() })))
        assertEquals("ToolInventoryPaginationEvidence", normalized.inventoryPaginationEvidence.toString())
        assertFalse(observation.toString().contains("private-former-version"))
        assertFalse(normalized.inventoryPaginationEvidence.toString().contains(original.callId))
    }

    @Test fun rawRestoredAndForeignCallOrArgumentEvidenceNeverProjects() = runBlocking {
        val arguments = mismatchArguments()
        val original = catalog().execute("owned-call", arguments)
        val injected = JsonObject(original.executionReceipt.orEmpty() + mapOf("inventory_pagination_reason" to JsonPrimitive("VERSION_MISMATCH"), "inventory_restart_offered" to JsonPrimitive(true)))
        val raw = AgentToolResult(original.callId, original.content, true, errorCode = original.errorCode, dispatched = false, executionReceipt = injected)
        assertTrue(project(raw, arguments).isEmpty())
        assertTrue(ToolReportDiagnostics.fields(event(raw.callId, arguments), raw).keys.intersect(fields).isEmpty())

        val serialized = ToolResultCheckpoint.encode(payload(original).toString(), ToolEventResultType.JSON, "retained", executionReceipt = injected)
        val restoredReceipt = ToolResultCheckpoint.execution(event(original.callId, arguments).copy(resultType = ToolEventResultType.CHECKPOINT, result = serialized, isError = true))
        assertTrue(project(raw.copy(executionReceipt = restoredReceipt), arguments).isEmpty())
        assertTrue(project(original.copy(callId = "foreign-call"), arguments).isEmpty())
        assertTrue(ToolInventoryPaginationEvidence.reportFields(original, "foreign-call", ProgressiveToolCatalog.NAME, arguments.toString()).isEmpty())
        assertTrue(ToolInventoryPaginationEvidence.reportFields(original, original.callId, "github_read", arguments.toString()).isEmpty())
        assertTrue(project(original, JsonObject(arguments + ("inventory_version" to JsonPrimitive("different-private-version")))).isEmpty())
        assertTrue(ToolInventoryPaginationEvidence.reportFields(original, original.callId, ProgressiveToolCatalog.NAME, null).isEmpty())
    }

    @Test fun contradictoryCopiesAndReasonCodesCannotBorrowTheNativeObservation() = runBlocking {
        val arguments = mismatchArguments()
        val original = catalog().execute("owned-call", arguments)
        val contradictions = listOf(
            original.copy(isError = false),
            original.copy(dispatched = true),
            original.copy(sharedResult = true),
            original.copy(errorCode = "DEPENDENCY_FAILED"),
            original.copy(executionReceipt = null)
        ) + listOf(
            "requires_argument_correction" to JsonPrimitive(false),
            "retryable" to JsonPrimitive(true),
            "permissions_changed" to JsonPrimitive(true),
            "catalog_pagination_invalidated" to JsonPrimitive(false),
            "failed_stage" to JsonPrimitive("tool_precheck"),
            "dispatched" to JsonPrimitive(true),
            "execution_state" to JsonPrimitive("COMPLETED"),
            "phase" to JsonPrimitive("tool_execution"),
            "requires_reconciliation" to JsonPrimitive(true),
            "native_dependency_state" to JsonPrimitive("FAILED"),
            "authority_invalidation_code" to JsonPrimitive("DISPATCH_POLICY_CHANGED"),
            "call_id" to JsonPrimitive("foreign-call"),
            "tool" to JsonPrimitive("github_read"),
            "error_code" to JsonPrimitive("APP_ERROR"),
            "reason_code" to JsonPrimitive("INVENTORY_VERSION_MISSING")
        ).map { (key, value) -> original.copy(executionReceipt = JsonObject(original.executionReceipt.orEmpty() + (key to value))) }
        contradictions.forEach { assertTrue(project(it, arguments).isEmpty()) }
        val currentReason = original.copy(executionReceipt = JsonObject(original.executionReceipt.orEmpty() + ("reason_code" to JsonPrimitive("INVENTORY_VERSION_CHANGED"))))
        assertEquals(project(original, arguments), project(currentReason, arguments))
        val missingArguments = buildJsonObject { put("inventory_cursor", 1) }
        val missing = catalog().execute("missing-call", missingArguments)
        val missingReason = missing.copy(executionReceipt = JsonObject(missing.executionReceipt.orEmpty() + ("reason_code" to JsonPrimitive("INVENTORY_VERSION_MISSING"))))
        assertEquals(project(missing, missingArguments), project(missingReason, missingArguments))
    }

    @Test fun oversizedBindingOmitsMetadataWithoutChangingTheCatalogRefusal() = runBlocking {
        val arguments = buildJsonObject {
            put("inventory_cursor", 1)
            put("inventory_version", "x".repeat(65_537))
        }
        val result = catalog().execute("oversized-call", arguments)
        assertEquals("STALE_INVENTORY", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(JsonPrimitive(true), payload(result)["restart_offered"])
        assertNull(result.inventoryPaginationEvidence)
        assertTrue(project(result, arguments).isEmpty())
    }

    @Test fun modelEnvelopeStripsReservedFieldsEvenFromAnUnqualifiedRawReceipt() = runBlocking {
        val original = catalog().execute("owned-call", mismatchArguments())
        val rawReceipt = JsonObject(original.executionReceipt.orEmpty() + mapOf("inventory_pagination_reason" to JsonPrimitive("VERSION_MISMATCH"), "inventory_restart_offered" to JsonPrimitive(true)))
        val raw = original.copy(inventoryPaginationEvidence = null, executionReceipt = rawReceipt)
        val rendered = Json.parseToJsonElement(ToolResultEnvelope.encode(ProgressiveToolCatalog.NAME, raw)).jsonObject
        val exposed = rendered.getValue("execution").jsonObject.getValue("receipt").jsonObject
        assertTrue(fields.none { it in exposed })
        assertEquals(rawReceipt - fields, exposed)
        assertEquals(rawReceipt, raw.executionReceipt)
    }

    @Test fun dependentFailureAndRestoredCallLookupIgnoreDiagnosticMetadata() = runBlocking {
        val arguments = mismatchArguments()
        val discovery = ProviderEvent.ToolCall("catalog-call", ProgressiveToolCatalog.NAME, arguments)
        val dependent = ProviderEvent.ToolCall("read-call", "github_read", JsonObject(emptyMap()))
        val nodes = listOf(ToolBatchNode(discovery, emptySet(), false), ToolBatchNode(dependent, setOf(discovery.callId), true))
        val outcomes = mutableListOf<List<AgentToolResult>>()
        for (retainEvidence in listOf(false, true)) {
            val original = catalog().execute(discovery.callId, arguments)
            val restored = if (retainEvidence) original.copy() else original.copy(inventoryPaginationEvidence = null)
            var dispatches = 0
            val completed = mutableListOf<String>()
            val result = IntelligentToolBatch(AdaptiveBatchWindow(2), { 0L }).execute(
                nodes,
                dispatch = {
                    dispatches++
                    error("A retained failed dependency must not execute again or dispatch its dependent")
                },
                completed = { call, _ -> completed += call.callId },
                progress = {},
                restored = mapOf(discovery.callId to restored)
            )
            assertEquals(0, dispatches)
            assertEquals(listOf(dependent.callId), completed)
            assertSame(restored, result.first())
            val refused = result.last()
            assertEquals("DEPENDENCY_FAILED", refused.errorCode)
            assertFalse(refused.dispatched)
            assertEquals(JsonPrimitive(1), refused.executionReceipt?.get("dependency_count"))
            assertEquals(JsonPrimitive(1), refused.executionReceipt?.get("dependency_failed_count"))
            assertEquals(JsonArray(listOf(JsonPrimitive(IssueReportContext.identifierHash("call", discovery.callId)))), refused.executionReceipt?.get("dependency_call_id_hashes"))
            assertNull(refused.inventoryPaginationEvidence)
            outcomes += result.map { it.copy(inventoryPaginationEvidence = null) }
        }
        assertEquals(outcomes.first(), outcomes.last())
    }

    private fun catalog() = ProgressiveToolCatalog(List(13) { AgentToolDefinition("inventory_tool_$it", "Inventory fixture", JsonObject(emptyMap())) }, "Inspect tool inventory")
    private fun mismatchArguments() = buildJsonObject {
        put("inventory_cursor", 1)
        put("inventory_version", "private-former-version")
    }
    private fun payload(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject
    private fun project(result: AgentToolResult, arguments: JsonObject) = ToolInventoryPaginationEvidence.reportFields(result, result.callId, ProgressiveToolCatalog.NAME, arguments.toString())
    private fun event(callId: String, arguments: JsonObject) = ToolEvent(
        eventId = "event-$callId",
        runId = "owned-run",
        sequence = 0,
        callId = callId,
        connectionUidSnapshot = null,
        connectionNameSnapshot = null,
        toolName = ProgressiveToolCatalog.NAME,
        modelToolName = ProgressiveToolCatalog.NAME,
        arguments = arguments.toString(),
        result = null,
        resultType = null,
        status = ToolEventStatus.RUNNING
    )
}
