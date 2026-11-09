package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunToolAdmissionSnapshotTest {
    @Test fun precheckFailureRecordsAnUnreachedCatalogInsteadOfAnEmptySuccessfulDiscovery() {
        val unreached = capture(RunToolAdmissionStage.STAGE_NOT_REACHED)
        val empty = capture(RunToolAdmissionStage.ADMITTED, emptyList(), emptyList())
        assertEquals(JsonPrimitive("STAGE_NOT_REACHED"), unreached["catalogStatus"])
        assertEquals(JsonNull, unreached["inventoryCount"])
        assertEquals(JsonNull, unreached["acceptedCount"])
        assertEquals(JsonNull, unreached["catalogDigest"])
        assertEquals(JsonPrimitive("OBSERVED"), empty["catalogStatus"])
        assertEquals(JsonPrimitive(0), empty["acceptedCount"])
        assertNotEquals(JsonNull, empty["catalogDigest"])
        assertEquals(JsonPrimitive(false), empty["executionEvidence"])
    }

    @Test fun schemaAndConnectionChangesAlterTheCatalogDigestButOrderAndDocumentationDoNot() {
        val read = tool("read")
        val write = tool("write")
        val first = capture(RunToolAdmissionStage.RESOLVED, listOf(read, write))
        val reordered = capture(RunToolAdmissionStage.RESOLVED, listOf(write.copy(description = "new docs"), read))
        assertEquals(first["catalogDigest"], reordered["catalogDigest"])
        val changedSchema = capture(RunToolAdmissionStage.RESOLVED, listOf(read, tool("write", "number")))
        assertNotEquals(first["catalogDigest"], changedSchema["catalogDigest"])
        val connection = RunToolAdmissionSnapshot.capture("run", "profile", "provider", RunToolAdmissionStage.RESOLVED, listOf(read, write), toolConnections = mapOf("read" to "connection"))
        assertNotEquals(first["catalogDigest"], connection["catalogDigest"])
    }

    @Test fun boundedDetailsRetainExactCountsAndDigestsCoverTheOmittedTail() {
        val inventory = (1..300).map { tool("tool_${it.toString().padStart(3, '0')}") }
        val omissions = inventory.takeLast(20).associate { it.name to "CONTEXT_BUDGET" }
        val snapshot = RunToolAdmissionSnapshot.capture("run", "profile", "provider", RunToolAdmissionStage.ADMITTED, inventory, inventory.take(280), omissions)
        assertEquals(JsonPrimitive(300), snapshot["inventoryCount"])
        assertEquals(JsonPrimitive(280), snapshot["acceptedCount"])
        assertEquals(JsonPrimitive(20), snapshot["omittedCount"])
        assertEquals(256, (snapshot["inventory"] as JsonArray).size)
        assertEquals(256, (snapshot["acceptedTools"] as JsonArray).size)
        assertEquals(JsonPrimitive(true), snapshot["detailsTruncated"])
        val changedTail = inventory.dropLast(1) + tool("tool_300", "number")
        assertNotEquals(snapshot["catalogDigest"], capture(RunToolAdmissionStage.RESOLVED, changedTail)["catalogDigest"])
        assertTrue(snapshot.toString().length < 256 * 1024)
    }

    @Test fun untrustedDescriptionsAndSchemaValuesRemainHashesNotExportedContent() {
        val secret = "private credential canary"
        val definition = tool("read").copy(
            description = secret,
            inputSchema = buildJsonObject {
                put("description", secret)
                put("const", secret)
            }
        )
        val snapshot = RunToolAdmissionSnapshot.capture("run", "profile", "provider", RunToolAdmissionStage.RESOLVED, listOf(definition), omissions = mapOf("unsafe\n$secret" to secret))
        assertFalse(snapshot.toString().contains(secret))
        assertEquals(JsonPrimitive("profile"), snapshot["profileUid"])
        assertEquals(JsonPrimitive("provider"), snapshot["providerConnectionUid"])
    }

    @Test fun perRunStageWindowKeepsDistinctRunsAndAcceptsASerializedSnapshot() {
        val first = capture(RunToolAdmissionStage.STAGE_NOT_REACHED)
        val admitted = capture(RunToolAdmissionStage.ADMITTED, listOf(tool("read")), listOf(tool("read")))
        val other = JsonObject(first + ("runId" to JsonPrimitive("other")))
        val window = ToolCatalogDiagnostics.AdmissionWindow(3)
        window.observe(first)
        window.observe(admitted)
        window.observe(other)
        assertEquals(listOf(first, admitted, other), window.snapshot())
        window.clear()
        assertTrue(window.snapshot().isEmpty())
        // The serialized payload is self-contained; restoring it needs no process inventory.
        val restored = kotlinx.serialization.json.Json.parseToJsonElement(admitted.toString()) as JsonObject
        val restarted = ToolCatalogDiagnostics.AdmissionWindow(3)
        restarted.observe(restored)
        assertEquals(admitted, restarted.snapshot().single())
    }

    private fun capture(stage: RunToolAdmissionStage, inventory: List<AgentToolDefinition>? = null, accepted: List<AgentToolDefinition>? = null) =
        RunToolAdmissionSnapshot.capture("run", "profile", "provider", stage, inventory, accepted, observedAtMillis = 123)

    private fun tool(name: String, type: String = "string") = AgentToolDefinition(name, "documentation", buildJsonObject { put("type", type) })
}
