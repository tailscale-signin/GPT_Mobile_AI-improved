package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ToolContractFingerprint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
