package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogAcquisitionTest {
    @Test fun configurationReceiptRetainsTypedCauseAndStateChangeRecovery() {
        val state = ToolCatalogAcquisition(listOf(ToolCatalogConnectionDiscovery("selected", ToolCatalogDiscoveryState.CONFIGURATION_REQUIRED, "revision", errorCode = "MCP_DISCOVERY_CONFIGURATION", phase = "protocol_negotiation", validationRule = "SUPPORTED_VERSIONS_REQUIRED", validationField = "supportedVersions", upstreamStatus = 400))).receipt()
        val connection = state.getValue("connections").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("supportedVersions"), connection["validation_field"])
        assertEquals(JsonPrimitive("CONFIGURATION_REVISION_CHANGED_OR_EXPLICIT_RECONNECT"), connection["recovery_condition"])
        assertEquals(JsonPrimitive(400), connection["upstream_status"])
        assertEquals(JsonPrimitive(true), state["ready_tools_remain_usable"])
    }

    private val readyTool = AgentToolDefinition("ready_tool", "Ready authorized tool", JsonObject(emptyMap()))
    private val lateTool = AgentToolDefinition("late_tool", "Late authorized tool", JsonObject(emptyMap()))
    private fun payload(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject

    @Test fun lateProducerSuccessCanBeDiscoveredInTheSameRunWithoutRepeatingPendingReads() = runTest {
        var connection = ToolCatalogConnectionDiscovery("selected", ToolCatalogDiscoveryState.PENDING, "pending")
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(
            listOf(readyTool),
            "Use late_tool",
            acquireCold = { _, _ ->
                acquisitions++
                if (connection.state == ToolCatalogDiscoveryState.READY) listOf(readyTool, lateTool) else listOf(readyTool)
            },
            acquisitionState = { ToolCatalogAcquisition(listOf(connection)) }
        )
        val requested = buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(lateTool.name)))) }
        val pending = payload(catalog.execute("first", requested))
        assertEquals(JsonPrimitive(1), pending.getValue("connection_discovery").jsonObject["pending_connections"])
        assertEquals("ACQUISITION_PENDING", catalog.acquisitionReadinessCode())
        assertTrue(catalog.selected(listOf(readyTool, catalog.definition)).any { it.name == readyTool.name })
        catalog.execute("unchanged", requested)
        assertEquals(1, acquisitions)

        connection = connection.copy(state = ToolCatalogDiscoveryState.READY, revision = "ready", knownSchemaCount = 1)
        val completed = payload(catalog.execute("completed", requested))
        assertEquals(2, acquisitions)
        assertTrue(completed.getValue("available_next_round").jsonArray.any { it.jsonObject["name"] == JsonPrimitive(lateTool.name) })
        assertNull(catalog.acquisitionReadinessCode())
        assertEquals(JsonPrimitive(false), completed.getValue("connection_discovery").jsonObject["details_truncated"])
    }

    @Test fun aVerifiedEmptyCatalogIsDifferentFromPendingAndUnchangedFailureIsNotRefetched() = runTest {
        var connection = ToolCatalogConnectionDiscovery("selected", ToolCatalogDiscoveryState.FAILED, "failed", errorCode = "MCP_CATALOG_TIMEOUT")
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(emptyList(), "Use missing_tool", acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        }, acquisitionState = { ToolCatalogAcquisition(listOf(connection)) })
        val requested = buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("missing_tool")))) }
        catalog.execute("failed", requested)
        catalog.execute("unchanged", requested)
        assertEquals(1, acquisitions)
        assertEquals("ACQUISITION_FAILED", catalog.acquisitionReadinessCode())

        connection = connection.copy(state = ToolCatalogDiscoveryState.READY, revision = "empty-result", knownSchemaCount = 0, errorCode = null)
        val empty = payload(catalog.execute("empty", requested))
        assertEquals(2, acquisitions)
        assertNull(catalog.acquisitionReadinessCode())
        assertEquals(JsonPrimitive(0), empty["authorized_tool_count"])
        assertEquals(JsonPrimitive(0), empty.getValue("connection_discovery").jsonObject.getValue("connections").jsonArray.single().jsonObject["last_observed_schema_count"])
    }

    @Test fun producerCompletingDuringSnapshotReturnDoesNotPinTheOlderResultToTheReadyRevision() = runTest {
        for (existing in listOf(emptyList(), listOf(readyTool))) {
            var connection = ToolCatalogConnectionDiscovery("selected", ToolCatalogDiscoveryState.PENDING, "pending")
            var acquisitions = 0
            val catalog = ProgressiveToolCatalog(existing, "Use late_tool", acquireCold = { _, _ ->
                acquisitions++
                if (acquisitions == 1) {
                    // The short wait already captured the old snapshot when the
                    // application-owned producer publishes its finished catalog.
                    connection = connection.copy(state = ToolCatalogDiscoveryState.READY, revision = "ready", knownSchemaCount = 1)
                    existing
                } else {
                    existing + lateTool
                }
            }, acquisitionState = { ToolCatalogAcquisition(listOf(connection)) })
            val requested = buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(lateTool.name)))) }
            catalog.execute("old-snapshot", requested)
            val completed = payload(catalog.execute("ready-snapshot", requested))
            assertEquals(2, acquisitions)
            assertTrue(completed.getValue("available_next_round").jsonArray.any { it.jsonObject["name"] == JsonPrimitive(lateTool.name) })
        }
    }

    @Test fun failedAcquisitionRetainsItsOutcomeUntilTheConnectionStateChanges() = runTest {
        var connection = ToolCatalogConnectionDiscovery("selected", ToolCatalogDiscoveryState.NOT_DISCOVERED, "before")
        val catalog = ProgressiveToolCatalog(listOf(readyTool), "Use late_tool", acquireCold = { _, _ ->
            error("Local catalog admission failed before a remote observation")
        }, acquisitionState = { ToolCatalogAcquisition(listOf(connection)) })
        assertTrue(runCatching { catalog.execute("failed", JsonObject(emptyMap())) }.isFailure)
        assertEquals("ACQUISITION_FAILED", catalog.acquisitionReadinessCode())
        assertTrue(catalog.selected(listOf(readyTool, catalog.definition)).any { it.name == readyTool.name })
        connection = connection.copy(state = ToolCatalogDiscoveryState.READY, revision = "after", knownSchemaCount = 1)
        assertNull(catalog.acquisitionReadinessCode())
    }

    @Test fun receiptsBoundConnectionDetailsAndNeverTurnCachedSchemasIntoExecutionProof() {
        val states = (1..30).map { ToolCatalogConnectionDiscovery("connection-$it", ToolCatalogDiscoveryState.RECOVERY_BACKOFF, "revision-$it", knownSchemaCount = 200, errorCode = "CONNECT_TIMEOUT", retryAtMillis = 1000) }
        val receipt = ToolCatalogAcquisition(states).receipt()
        assertEquals(JsonPrimitive(30), receipt["connection_count"])
        assertEquals(16, receipt.getValue("connections").jsonArray.size)
        assertEquals(JsonPrimitive(true), receipt["details_truncated"])
        assertEquals(JsonPrimitive(true), receipt["ready_tools_remain_usable"])
        assertFalse(receipt.toString().contains("execution_verified"))
    }
}
