package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.AgentToolWorkflowLink
import dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
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

class GitHubCatalogRetentionTest {
    private fun definition(name: String, links: Int = 0) = AgentToolDefinition(
        name,
        "Authorized operation metadata ".repeat(6),
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray(listOf(JsonPrimitive("read")))) }) })
            put("additionalProperties", false)
        },
        workflowLinks = (0 until links).map { index ->
            AgentToolWorkflowLink("action_$index", name, "source_$index", "Optional evidence from an authorized operation. ".repeat(4))
        }
    )

    private class RetainedCatalog(val catalog: ProgressiveToolCatalog) {
        val originals = linkedMapOf<String, JsonObject>()
        val book = newBook()
        fun newBook() = GitHubEvidenceBook(
            "task",
            "owner/repo",
            "main",
            setOf(catalog.definition.name),
            load = { originals[it] },
            save = { id, receipt -> originals[id] = receipt },
            retainedIds = { originals.keys.filter { it.startsWith("ev_") } },
            runtimeIdentity = { JsonObject(emptyMap()) }
        )

        suspend fun observe(id: String, arguments: JsonObject = JsonObject(emptyMap()), result: AgentToolResult? = null) {
            val observed = result ?: catalog.execute(id, arguments)
            assertFalse("Local discovery must not dispatch a repository operation", observed.dispatched)
            book.observe(ProviderEvent.ToolCall(id, catalog.definition.name, arguments), observed)
        }
    }

    @Test fun thirteenToolDiscoveryKeepsVersionAndFingerprintsThroughLargeReceiptProjection() = runBlocking {
        val definitions = (0 until 13).map { definition("github_operation_$it", links = 32) }
        for (enveloped in listOf(false, true)) {
            val fixture = RetainedCatalog(ProgressiveToolCatalog(definitions, "test all tools", githubMode = true))
            val result = fixture.catalog.execute("discovery", JsonObject(emptyMap()))
            val payload = (result.content as ToolResultContent.Json).value
            assertTrue(payload.toString().encodeToByteArray().size > 64 * 1024)
            val retainedPayload = if (enveloped) buildJsonObject { put("data", payload) } else payload
            fixture.observe("discovery", result = result.copy(content = ToolResultContent.Json(retainedPayload)))
            val tools = definitions + fixture.catalog.definition
            val originalState = GitHubAuthorizedCatalog.snapshot(fixture.originals, tools)
            assertEquals(JsonPrimitive(true), originalState["complete"])

            val retained = fixture.book.snapshot().first
            val projected = retained.values.single()
            assertEquals(JsonPrimitive("EXACT_VALUES_WITH_DURABLE_ORIGINAL"), projected["review_projection"])
            assertEquals(JsonPrimitive(false), projected["review_projection_complete"])
            assertEquals("Projection must preserve the exact catalog proof", originalState, GitHubAuthorizedCatalog.snapshot(retained, tools))
            assertEquals(JsonPrimitive(13), originalState["observed_github_tool_count"])
            assertEquals(JsonPrimitive(1), originalState["inventory_pages_verified"])
            assertEquals(retainedPayload, fixture.originals.values.single()["result"])
            assertNull(GitHubAuthorizedCatalog.next(tools, retained))

            val restored = fixture.newBook()
            restored.restore()
            assertTrue(restored.complete())
            assertEquals(originalState, GitHubAuthorizedCatalog.snapshot(restored.snapshot().first, tools))
        }
    }

    @Test fun largeInventoryRetainsExactContinuationAndFinishesAfterRestore() = runBlocking {
        val definitions = (0 until 13).map { definition("github_operation_$it") } +
            (0 until 257).map { definition("other_operation_$it") }
        val fixture = RetainedCatalog(ProgressiveToolCatalog(definitions, "test all tools", githubMode = true))
        val tools = definitions + fixture.catalog.definition
        fixture.observe("page-one")
        val originalState = GitHubAuthorizedCatalog.snapshot(fixture.originals, tools)
        assertEquals(JsonPrimitive("PARTIAL"), originalState["status"])
        assertEquals(JsonPrimitive(256), originalState["observed_tool_count"])
        assertEquals(originalState, GitHubAuthorizedCatalog.snapshot(fixture.book.snapshot().first, tools))

        val resumed = fixture.newBook()
        resumed.restore()
        val call = requireNotNull(GitHubAuthorizedCatalog.next(tools, resumed.snapshot().first))
        val returnedRecipe = originalState.getValue("continuation_call").jsonObject
        assertEquals(returnedRecipe.getValue("arguments"), call.arguments)
        assertEquals(JsonPrimitive(fixture.catalog.definition.name), returnedRecipe["tool"])
        val result = fixture.catalog.execute(call.callId, call.arguments)
        assertFalse(result.dispatched)
        resumed.observe(call, result)
        val complete = GitHubAuthorizedCatalog.snapshot(resumed.snapshot().first, tools)
        assertEquals(JsonPrimitive(true), complete["denominator_final"])
        assertEquals(JsonPrimitive(270), complete["observed_tool_count"])
        assertEquals(JsonPrimitive(13), complete["observed_github_tool_count"])
        assertEquals(JsonPrimitive(2), complete["inventory_pages_verified"])
        assertNull(GitHubAuthorizedCatalog.next(tools, resumed.snapshot().first))

        val restarted = fixture.newBook()
        restarted.restore()
        assertTrue(restarted.complete())
        assertEquals(complete, GitHubAuthorizedCatalog.snapshot(restarted.snapshot().first, tools))
    }

    @Test fun largeProjectedCatalogStillRejectsARealInvocationContractChange() = runBlocking {
        val definitions = (0 until 13).map { definition("github_operation_$it", links = 32) }
        val fixture = RetainedCatalog(ProgressiveToolCatalog(definitions, "test all tools", githubMode = true))
        fixture.observe("discovery")
        val changed = definitions.last().copy(
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", JsonArray(listOf(JsonPrimitive("new_argument"))))
            }
        )
        val tools = definitions.dropLast(1) + changed + fixture.catalog.definition
        val state = GitHubAuthorizedCatalog.snapshot(fixture.book.snapshot().first, tools)
        assertEquals(JsonPrimitive(false), state["complete"])
        assertEquals(JsonPrimitive("CATALOG_CONTRACT_CHANGED"), state["blocker"])
        val change = state.getValue("contract_changes").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(changed.name), change["name"])
        assertEquals(JsonPrimitive(ToolContractFingerprint.schema(definitions.last().inputSchema)), change["observed_schema_fingerprint"])
        assertEquals(JsonPrimitive(ToolContractFingerprint.schema(changed.inputSchema)), change["expected_schema_fingerprint"])
        assertEquals(JsonPrimitive(false), change["schema_fingerprint_invalid"])
        assertNotNull(GitHubAuthorizedCatalog.next(tools, fixture.book.snapshot().first))
    }

    @Test fun projectionDoesNotRepairDuplicateNamesOrAnInvalidTerminalRecipe() = runBlocking {
        val definitions = (0 until 13).map { definition("github_operation_$it", links = 32) }
        for (duplicate in listOf(true, false)) {
            val fixture = RetainedCatalog(ProgressiveToolCatalog(definitions, "test all tools", githubMode = true))
            val result = fixture.catalog.execute("invalid", JsonObject(emptyMap()))
            val payload = (result.content as ToolResultContent.Json).value.jsonObject
            val inventory = payload.getValue("authorized_inventory").jsonArray
            val changed = if (duplicate) {
                JsonObject(payload + ("authorized_inventory" to JsonArray(inventory.dropLast(1) + inventory.first())))
            } else {
                JsonObject(
                    payload + (
                        "continuation_call" to buildJsonObject {
                            put("tool", fixture.catalog.definition.name)
                            put("arguments", JsonObject(emptyMap()))
                        }
                        )
                )
            }
            fixture.observe("invalid", result = result.copy(content = ToolResultContent.Json(changed)))
            val tools = definitions + fixture.catalog.definition
            val expected = GitHubAuthorizedCatalog.snapshot(fixture.originals, tools)
            assertEquals(JsonPrimitive(false), expected["complete"])
            assertEquals(JsonPrimitive(if (duplicate) "CATALOG_PAGE_INCONSISTENT" else "CATALOG_TERMINAL_COUNT_MISMATCH"), expected["blocker"])
            assertEquals(expected, GitHubAuthorizedCatalog.snapshot(fixture.book.snapshot().first, tools))
            assertNull(GitHubAuthorizedCatalog.next(tools, fixture.book.snapshot().first))
        }
    }
}
