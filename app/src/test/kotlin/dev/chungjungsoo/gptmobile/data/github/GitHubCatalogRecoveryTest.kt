package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog
import dev.chungjungsoo.gptmobile.data.agent.ResponseRecoveryContext
import dev.chungjungsoo.gptmobile.data.agent.ToolContractFingerprint
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCatalogRecoveryTest {
    private val discovery = AgentToolDefinition("discover_available_tools", "Discover", buildJsonObject { put("type", "object") })
    private fun tool(description: String, writable: Boolean = false) = AgentToolDefinition(
        "github_read",
        "Read",
        buildJsonObject {
            put("type", "object")
            put("description", description)
            put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray((if (writable) listOf("read", "write") else listOf("read")).map(::JsonPrimitive))) }) })
        }
    )
    private fun receipt(definition: AgentToolDefinition, semantic: Boolean, callId: String = "catalog") = buildJsonObject {
        put("tool", "discover_available_tools")
        put("call_id", callId)
        put("receipt_id", callId)
        put("binding", "task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", true)
        put("arguments", JsonObject(emptyMap()))
        put(
            "result",
            buildJsonObject {
                put("inventory_version", "snapshot")
                put("authorized_tool_count", 1)
                put("inventory_offset", 0)
                put("returned", 1)
                put("has_more", false)
                put(
                    "authorized_inventory",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("name", definition.name)
                                put("schema_fingerprint", GitHubEvidenceBook.digest(definition.inputSchema.toString()))
                                if (semantic) put("semantic_schema_fingerprint", ToolContractFingerprint.schema(definition.inputSchema))
                                put("invocation_contract_version", definition.contractVersion.toString())
                            }
                        )
                    )
                )
            }
        )
    }

    @Test fun dynamicDescriptionDoesNotInvalidateSemanticCatalog() {
        val receipts = mapOf("catalog" to receipt(tool("old head"), true))
        val tools = listOf(discovery, tool("new head and progress"))
        assertEquals(JsonPrimitive(true), GitHubAuthorizedCatalog.snapshot(receipts, tools)["complete"])
        assertNull(GitHubAuthorizedCatalog.next(tools, receipts))
    }

    @Test fun malformedRemoteContractFieldsNeverEnterMismatchDiagnostics() {
        val definition = tool("Read")
        val original = receipt(definition, true)
        val result = original.getValue("result").jsonObject
        val entry = result.getValue("authorized_inventory").jsonArray.single().jsonObject
        val privateMarker = "PRIVATE_REMOTE_MARKER_DO_NOT_EXPORT"
        val invalidEntry = JsonObject(
            entry + mapOf(
                "semantic_schema_fingerprint" to JsonPrimitive("$privateMarker-schema"),
                "invocation_contract_version" to JsonPrimitive("$privateMarker-version")
            )
        )
        val invalid = JsonObject(original + ("result" to JsonObject(result + ("authorized_inventory" to JsonArray(listOf(invalidEntry))))))
        val snapshot = GitHubAuthorizedCatalog.snapshot(mapOf("catalog" to invalid), listOf(discovery, definition))
        assertEquals(JsonPrimitive("CATALOG_CONTRACT_CHANGED"), snapshot["blocker"])
        assertFalse(snapshot.toString().contains(privateMarker))
        val change = snapshot.getValue("contract_changes").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("INVALID"), change["observed_schema_fingerprint"])
        assertEquals(JsonPrimitive("INVALID"), change["observed_contract_version"])
        assertEquals(JsonPrimitive(true), change["schema_fingerprint_invalid"])
        assertEquals(JsonPrimitive(true), change["contract_version_invalid"])
    }

    @Test fun recoveryWrappedExecutionContractMatchesActualDiscoveryWithoutAddingAuthorization() = runBlocking {
        val source = object : AgentTool {
            override val definition = tool("Source schema")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Schema discovery must not execute repository work")
        }
        val recovery = ResponseRecoveryContext(emptyList(), emptyList(), emptyList(), rootWorkId = "task")
        val wrapped = recovery.reuseCompletedTool(source, source.definition.name, "github-connection")
        assertTrue(wrapped.definition.inputSchema.getValue("properties").jsonObject.containsKey("_recovery_effect_id"))
        val catalog = ProgressiveToolCatalog(listOf(source.definition), "test all tools", githubMode = true)
        suspend fun observations(directory: ProgressiveToolCatalog, id: String): Map<String, JsonObject> {
            val result = directory.execute(id, JsonObject(emptyMap()))
            return mapOf(
                id to buildJsonObject {
                    put("tool", directory.definition.name)
                    put("call_id", id)
                    put("receipt_id", id)
                    put("binding", "task")
                    put("repository", "owner/repo")
                    put("trusted", true)
                    put("success", !result.isError)
                    put("arguments", JsonObject(emptyMap()))
                    put("result", (result.content as ToolResultContent.Json).value)
                }
            )
        }
        val tools = listOf(catalog.definition, wrapped.definition)
        val before = GitHubAuthorizedCatalog.snapshot(observations(catalog, "before"), tools)
        assertEquals(JsonPrimitive(true), before["complete"])
        assertNull(GitHubAuthorizedCatalog.next(tools, observations(catalog, "retained")))
        assertEquals(ToolContractFingerprint.definition(source.definition), ToolContractFingerprint.definition(wrapped.definition))
        assertEquals(JsonPrimitive(true), GitHubAuthorizedCatalog.snapshot(mapOf("legacy" to receipt(source.definition, false)), tools)["complete"])
        assertEquals(JsonPrimitive(true), GitHubAuthorizedCatalog.snapshot(mapOf("legacy" to receipt(wrapped.definition, false)), tools)["complete"])

        val unauthorized = source.definition.copy(name = "github_unassigned")
        catalog.bindExecutionContracts(listOf(wrapped.definition, unauthorized))
        val after = GitHubAuthorizedCatalog.snapshot(observations(catalog, "after"), tools)
        assertEquals(JsonPrimitive(true), after["complete"])
        assertEquals(JsonArray(listOf(JsonPrimitive(source.definition.name))), after["observed_names"])

        var acquired = source.definition
        var coldCalls = 0
        val cold = ProgressiveToolCatalog(listOf(source.definition), "test all tools", acquireCold = { _, _ ->
            coldCalls++
            listOf(acquired)
        }, githubMode = true)
        cold.bindExecutionContracts(listOf(wrapped.definition))
        assertEquals(JsonPrimitive(true), GitHubAuthorizedCatalog.snapshot(observations(cold, "cold"), tools)["complete"])
        assertEquals(1, coldCalls)

        // A genuine new source contract must not be hidden by the old recovery binding.
        acquired = tool("Changed source schema", writable = true)
        cold.execute("refresh-source", buildJsonObject { put("query", "new contract") })
        assertEquals(2, coldCalls)
        assertEquals(JsonPrimitive("CATALOG_CONTRACT_CHANGED"), GitHubAuthorizedCatalog.snapshot(observations(cold, "changed"), tools)["blocker"])
    }

    @Test fun legacyOrChangedContractRefreshesOnceWithoutErasingCompatibleReceipts() {
        val original = receipt(tool("old"), false)
        val receipts = linkedMapOf("catalog" to original, "read-proof" to buildJsonObject { put("proof", "retained") })
        val tools = listOf(discovery, tool("new", true))
        assertEquals(JsonPrimitive("CATALOG_CONTRACT_CHANGED"), GitHubAuthorizedCatalog.snapshot(receipts, tools)["blocker"])
        val next = requireNotNull(GitHubAuthorizedCatalog.next(tools, receipts))
        assertEquals(JsonObject(emptyMap()), next.arguments)
        assertNotNull(receipts["read-proof"])
        receipts["failed-refresh"] = buildJsonObject {
            put("tool", next.name)
            put("call_id", next.callId + "-3")
            put("receipt_id", "failed-refresh")
            put("trusted", true)
            put("success", false)
            put("arguments", next.arguments)
        }
        assertNull(GitHubAuthorizedCatalog.next(tools, receipts))
        receipts["new-catalog"] = receipt(tools.last(), true, "new-catalog")
        assertEquals(JsonPrimitive(true), GitHubAuthorizedCatalog.snapshot(receipts, tools)["complete"])
        assertEquals(original, receipts["catalog"])
        assertNotNull(receipts["read-proof"])
    }
}
