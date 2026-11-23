package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogSelectorRecoveryTest {
    private fun reader(name: String, visible: Boolean = true) = AgentToolDefinition(name, "Read document status", JsonObject(emptyMap()), modelVisible = visible)

    @Test fun catalogOnlySpelledAsAnActionKeepsAllRequestedNamesAndRemainsLocal() = runTest {
        var acquired = false
        val definitions = listOf(reader("github_read"), reader("github_search"))
        val catalog = ProgressiveToolCatalog(definitions, "Read repository instructions", acquireCold = { _, _ ->
            acquired = true
            emptyList()
        })
        val names = JsonArray(definitions.map { JsonPrimitive(it.name) })
        val original = buildJsonObject {
            put("names", names)
            put("action", "catalog_only")
        }
        val canonical = buildJsonObject {
            put("names", names)
            put("catalog_only", true)
        }
        assertEquals(canonical, catalog.canonicalArguments(original))
        val result = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1)).bind(catalog).execute("catalog", original)
        assertFalse(result.toString(), result.isError)
        assertFalse(acquired)
        val otherAction = JsonObject(original + ("action" to JsonPrimitive("write")))
        assertEquals(otherAction, catalog.canonicalArguments(otherAction))
    }

    @Test fun multiNameCatalogActionKeepsEveryNameAndOnlyUsesTheCurrentInventory() = runTest {
        var coldCalls = 0
        val definitions = listOf(reader("github_read"), reader("github_search"), reader("github_context"), reader("private_reader", visible = false))
        val catalog = ProgressiveToolCatalog(definitions, "Read repository instructions", acquireCold = { _, _ ->
            coldCalls++
            listOf(reader("cold_reader"))
        })
        val names = JsonArray(listOf("github_read", "github_search", "github_context", "private_reader", "cold_reader").map(::JsonPrimitive))
        val original = buildJsonObject {
            put("names", names)
            put("action", "catalog")
        }
        val canonical = buildJsonObject {
            put("names", names)
            put("catalog_only", true)
        }
        assertEquals(canonical, catalog.canonicalArguments(original))
        val result = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1)).bind(catalog).execute("catalog-alias", original)
        assertFalse(result.toString(), result.isError)
        assertFalse(result.dispatched)
        assertEquals("catalog-alias", result.callId)
        assertEquals(canonical, result.executionReceipt?.get("corrected_arguments"))
        assertEquals(0, coldCalls)
        val selected = catalog.selected(definitions + catalog.definition).map { it.name }.toSet()
        assertTrue(selected.containsAll(listOf("github_read", "github_search", "github_context")))
        assertFalse("private_reader" in selected)
        assertFalse("cold_reader" in selected)
    }

    @Test fun catalogAliasPreservesSingleToolActionsAndRejectsConflictingOrMalformedIntent() = runTest {
        val catalog = ProgressiveToolCatalog(listOf(reader("repository_workspace")), "Read repository instructions")
        val validSingle = buildJsonObject {
            put("names", JsonArray(listOf(JsonPrimitive("repository_workspace"))))
            put("action", "catalog")
        }
        assertEquals(validSingle, catalog.canonicalArguments(validSingle))
        for (raw in listOf(
            """{"action":"catalog","names":[]}""",
            """{"action":"catalog","names":["github_read","github_search"],"catalog_only":false}""",
            """{"action":"catalog","names":["github_read",false]}""",
            """{"action":"catalog","names":["github_read","github_search"],"inventory_cursor":1}""",
            """{"action":"catalog","names":["github_read","github_search"],"unexpected":true}"""
        )) {
            val original = Json.parseToJsonElement(raw).jsonObject
            assertEquals(raw, original, catalog.canonicalArguments(original))
        }
        val oversized = JsonObject(validSingle + ("names" to JsonArray((1..13).map { JsonPrimitive("reader_$it") })))
        assertEquals(oversized, catalog.canonicalArguments(oversized))
        val otherAction = JsonObject(validSingle + mapOf("action" to JsonPrimitive("write"), "names" to JsonArray(listOf(JsonPrimitive("github_read"), JsonPrimitive("github_search")))))
        assertEquals(otherAction, catalog.canonicalArguments(otherAction))
    }

    @Test fun exactAdvertisedScalarNameKeepsTheOriginalInvocationAndOnlySelectsItsSchema() = runTest {
        val definition = reader("read_document__selected")
        val catalog = ProgressiveToolCatalog(listOf(definition), "Read document")
        catalog.recordSelection(listOf(definition, catalog.definition), listOf(definition, catalog.definition))
        val original = buildJsonObject { put("names", definition.name) }
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2))
        var observed: ProviderEvent.ToolCall? = null
        val result = withContext(ToolReceiptObserver { call, _ -> observed = call }) {
            budget.bind(catalog.withRunContext("catalog-run")).execute("same-provider-call", original)
        }
        assertFalse(result.toString(), result.isError)
        assertFalse(result.dispatched)
        assertEquals("same-provider-call", result.callId)
        assertEquals("same-provider-call", observed?.callId)
        assertEquals(ProgressiveToolCatalog.NAME, observed?.name)
        assertEquals(original, observed?.arguments)
        assertEquals(buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(definition.name)))) }, result.executionReceipt?.get("corrected_arguments"))
        assertTrue(definition in catalog.selected(listOf(definition, catalog.definition)))
    }

    @Test fun scalarSelectionRequiresTheExactAdvertisedProjectionAndPreservesTheSelectedAction() = runTest {
        val definition = GitHubOperationTool.definition("github_pr", "github_pr__native")
        val catalog = ProgressiveToolCatalog(listOf(definition), "Review pull requests", githubMode = true)
        val allowed = listOf(definition, catalog.definition)
        fun selectedArguments(action: String, scalar: Boolean = false) = buildJsonObject {
            put("names", if (scalar) JsonPrimitive(definition.name) else JsonArray(listOf(JsonPrimitive(definition.name))))
            put("action", action)
        }
        assertFalse(catalog.execute("select-list", selectedArguments("list")).isError)
        val listProjection = catalog.selected(allowed)
        assertEquals(GitHubActionSchema.project(definition, "list"), listProjection.single { it.name == definition.name })
        catalog.recordSelection(allowed, listProjection)
        val budgeted = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 3)).bind(catalog.withRunContext("projected-catalog-run"))
        val original = selectedArguments("list", scalar = true)
        assertEquals(selectedArguments("list"), catalog.canonicalArguments(original))
        val result = budgeted.execute("same-projected-call", original)
        assertFalse(result.toString(), result.isError)
        assertFalse(result.dispatched)
        assertEquals(selectedArguments("list"), result.executionReceipt?.get("corrected_arguments"))
        assertEquals(listProjection, catalog.selected(allowed))

        assertFalse(catalog.execute("select-context", selectedArguments("context")).isError)
        val changed = selectedArguments("context", scalar = true)
        assertEquals(changed, catalog.canonicalArguments(changed))
        catalog.recordSelection(allowed, catalog.selected(allowed))
        assertEquals(selectedArguments("context"), catalog.canonicalArguments(changed))
    }

    @Test fun scalarRepairNeverResolvesAliasesPrivateUnadvertisedColdOrRevokedNames() = runTest {
        val advertised = reader("read_document__selected")
        val withheld = reader("read_document__withheld")
        val hidden = reader("private_reader", visible = false)
        var coldCalls = 0
        val catalog = ProgressiveToolCatalog(listOf(advertised, withheld, hidden), "Read document", acquireCold = { _, _ ->
            coldCalls++
            listOf(reader("cold_reader"))
        })
        catalog.recordSelection(listOf(advertised, withheld, catalog.definition), listOf(advertised, catalog.definition))
        val budgeted = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 12)).bind(catalog)
        for ((index, name) in listOf("read_document", "READ_DOCUMENT__SELECTED", "read_document__selected,read_document__withheld", withheld.name, hidden.name, "cold_reader").withIndex()) {
            val original = buildJsonObject { put("names", name) }
            assertEquals(original, catalog.canonicalArguments(original))
            val rejected = budgeted.execute("rejected-$index", original)
            assertTrue(name, rejected.isError)
            assertFalse(rejected.dispatched)
        }
        catalog.recordSelection(listOf(withheld, catalog.definition), listOf(withheld, catalog.definition))
        val revoked = buildJsonObject { put("names", advertised.name) }
        assertEquals(revoked, catalog.canonicalArguments(revoked))
        assertTrue(budgeted.execute("revoked", revoked).isError)
        val changedContract = advertised.copy(inputSchema = buildJsonObject { put("type", "object") })
        catalog.recordSelection(listOf(changedContract, catalog.definition), listOf(changedContract, catalog.definition))
        assertEquals(revoked, catalog.canonicalArguments(revoked))
        assertTrue(budgeted.execute("stale-contract", revoked).isError)
        assertEquals(0, coldCalls)
    }

    @Test fun unusedNullSelectorsUseDocumentedInventoryWithoutChangingPaginationOrActionIntent() = runTest {
        val definition = reader("read_document")
        val catalog = ProgressiveToolCatalog(listOf(definition), "Read document")
        val budgeted = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 8)).bind(catalog)
        val inventory = buildJsonObject {
            put("names", JsonNull)
            put("query", JsonNull)
        }
        val result = budgeted.execute("inventory", inventory)
        assertFalse(result.toString(), result.isError)
        assertEquals(JsonObject(emptyMap()), result.executionReceipt?.get("corrected_arguments"))
        assertTrue("authorized_inventory" in (result.content as ToolResultContent.Json).value.jsonObject)
        for ((index, raw) in listOf(
            """{"action":"status","names":null}""",
            """{"inventory_cursor":12,"inventory_version":"observed","names":null}""",
            """{"query":false,"names":null}""",
            """{"names":null,"unexpected":"value"}"""
        ).withIndex()) {
            val arguments = Json.parseToJsonElement(raw).jsonObject
            assertEquals(arguments, catalog.canonicalArguments(arguments))
            val rejected = budgeted.execute("invalid-null-$index", arguments)
            assertTrue(raw, rejected.isError)
            assertFalse(rejected.dispatched)
        }
    }

    @Test fun actionWithoutAToolOffersOnlyCurrentCatalogDiscoveryAndNeverAcquiresColdTools() = runTest {
        val visible = reader("read_status")
        val hidden = reader("private_status", visible = false)
        var coldCalls = 0
        val catalog = ProgressiveToolCatalog(listOf(visible, hidden), "Read status", acquireCold = { _, _ ->
            coldCalls++
            listOf(reader("cold_status"))
        })
        val budgeted = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 8)).bind(catalog)
        for ((index, names) in listOf(null, JsonNull).withIndex()) {
            val original = buildJsonObject {
                put("action", "status")
                names?.let { put("names", it) }
            }
            val rejection = budgeted.execute("missing-tool-$index", original)
            assertTrue(rejection.isError)
            assertFalse(rejection.dispatched)
            val correction = (rejection.content as ToolResultContent.Json).value.jsonObject.getValue("correction_call").jsonObject
            assertEquals(JsonPrimitive(ProgressiveToolCatalog.NAME), correction["tool"])
            val arguments = correction.getValue("arguments").jsonObject
            assertEquals(
                buildJsonObject {
                    put("query", "status")
                    put("catalog_only", true)
                },
                arguments
            )
            assertTrue(ToolArgumentValidator.errors(arguments, catalog.definition.inputSchema).isEmpty())
            val discovered = budgeted.execute("local-catalog-$index", arguments)
            assertFalse(discovered.toString(), discovered.isError)
            assertFalse(discovered.dispatched)
            assertFalse(discovered.content.toString().contains("private_status"))
            assertFalse(discovered.content.toString().contains("cold_status"))
        }
        assertEquals(0, coldCalls)
    }

    @Test fun catalogWrapperReportsOnlyTheSelectedObservedInvocationCount() = runTest {
        val definitions = (1..20).map { reader("read_document_$it") }
        val catalog = ProgressiveToolCatalog(definitions, "Read document")
        val allowed = definitions + catalog.definition
        var sent = emptyList<AgentToolDefinition>()
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                sent = tools
                emit(ProviderEvent.RequestConfigured(advertisedToolCount = 999))
                emit(ProviderEvent.Completed)
            }
        }
        val events = delegate.withToolCatalog(catalog).streamRound(allowed, emptyList()).toList()
        assertTrue(sent.size < allowed.size)
        assertEquals(sent.count { it.modelVisible }, events.filterIsInstance<ProviderEvent.RequestConfigured>().single().advertisedToolCount)
        val unobserved = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow { emit(ProviderEvent.Completed) }
        }.withToolCatalog(catalog).streamRound(allowed, emptyList()).toList()
        assertTrue(unobserved.none { it is ProviderEvent.RequestConfigured })
    }
}
