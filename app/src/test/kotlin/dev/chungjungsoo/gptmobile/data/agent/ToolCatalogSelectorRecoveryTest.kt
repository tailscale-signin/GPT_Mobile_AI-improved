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

    @Test fun searchActionWithoutNamesPreservesQueryAndUsesOnlyLocalDiscovery() = runTest {
        val definitions = listOf(reader("read_document"), reader("private_reader", visible = false))
        var coldCalls = 0
        val catalog = ProgressiveToolCatalog(definitions, "Read repository instructions", acquireCold = { _, _ ->
            coldCalls++
            listOf(reader("cold_reader"))
        })
        val budgeted = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 4)).bind(catalog.withRunContext("query-alias-run"))
        for ((index, query) in listOf("  document status  ", "document" + " ".repeat(4088)).withIndex()) {
            val original = buildJsonObject {
                put("action", "search")
                put("query", query)
                if (index == 1) {
                    put("names", JsonNull)
                    put("catalog_only", true)
                }
            }
            val canonical = buildJsonObject {
                put("query", query)
                put("catalog_only", true)
            }
            assertEquals(canonical, catalog.canonicalArguments(original))
            var observed: ProviderEvent.ToolCall? = null
            val result = withContext(ToolReceiptObserver { call, _ -> observed = call }) {
                budgeted.execute("query-search-$index", original)
            }
            assertFalse(result.toString(), result.isError)
            assertFalse(result.dispatched)
            assertEquals("query-search-$index", result.callId)
            assertEquals(result.callId, observed?.callId)
            assertEquals(original, observed?.arguments)
            assertEquals(canonical, result.executionReceipt?.get("canonical_arguments"))
            assertFalse(result.executionReceipt.orEmpty().containsKey("corrected_arguments"))
            val normalization = result.executionReceipt?.get("argument_normalization")?.jsonObject
            assertEquals(JsonPrimitive(false), normalization?.get("semantic_repair"))
            assertEquals(JsonPrimitive("SUCCEEDED"), normalization?.get("outcome"))
            val selected = catalog.selected(definitions + catalog.definition).map { it.name }.toSet()
            assertTrue("read_document" in selected)
            assertFalse("private_reader" in selected)
            assertFalse("cold_reader" in selected)
        }
        assertEquals(0, coldCalls)
    }

    @Test fun searchAliasPreservesNamedActionsAndRejectsConflictingOrMalformedSelectors() = runTest {
        val definition = GitHubOperationTool.definition("github_search", "github_search__native")
        val catalog = ProgressiveToolCatalog(listOf(definition), "Read repository instructions", githubMode = true)
        val allowed = listOf(definition, catalog.definition)
        val namedAction = buildJsonObject {
            put("names", JsonArray(listOf(JsonPrimitive(definition.name))))
            put("action", "search")
            put("query", "  document status  ")
            put("catalog_only", true)
        }
        assertEquals(namedAction, catalog.canonicalArguments(namedAction))
        assertFalse(catalog.execute("select-search", namedAction).isError)
        assertEquals(GitHubActionSchema.project(definition, "search"), catalog.selected(allowed).single { it.name == definition.name })
        catalog.recordSelection(allowed, catalog.selected(allowed))
        for (localOnly in listOf(true, false)) {
            val selected = JsonObject(namedAction + ("catalog_only" to JsonPrimitive(localOnly)))
            val scalar = JsonObject(selected + ("names" to JsonPrimitive(definition.name)))
            assertEquals(selected, catalog.canonicalArguments(scalar))
        }

        val search = buildJsonObject {
            put("action", "search")
            put("query", "document")
        }
        val invalid = listOf(
            JsonObject(search + ("names" to JsonArray(emptyList()))),
            JsonObject(search + ("names" to JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"))))),
            JsonObject(search + ("names" to JsonObject(emptyMap()))),
            JsonObject(search + ("names" to JsonPrimitive(false))),
            JsonObject(search + ("names" to JsonPrimitive("unadvertised_reader"))),
            JsonObject(search + ("catalog_only" to JsonPrimitive(false))),
            JsonObject(search + ("catalog_only" to JsonPrimitive("true"))),
            JsonObject(search + ("catalog_only" to JsonNull)),
            JsonObject(search + ("query" to JsonPrimitive(""))),
            JsonObject(search + ("query" to JsonPrimitive(" "))),
            JsonObject(search + ("query" to JsonNull)),
            JsonObject(search + ("query" to JsonPrimitive(false))),
            JsonObject(search + ("query" to JsonPrimitive("x".repeat(4097)))),
            JsonObject(search + ("action" to JsonPrimitive("SEARCH"))),
            JsonObject(search + ("action" to JsonPrimitive("status"))),
            JsonObject(search + ("inventory_cursor" to JsonPrimitive(0))),
            JsonObject(search + ("inventory_version" to JsonPrimitive("observed"))),
            JsonObject(search + ("repository_hint" to buildJsonObject { put("symbols", JsonArray(listOf(JsonPrimitive("ObservedSymbol")))) })),
            JsonObject(search + ("unexpected" to JsonPrimitive(true)))
        )
        for (original in invalid) assertEquals(original, catalog.canonicalArguments(original))
    }

    @Test fun scalarNameAndQueryOfferAnExplicitLocalProposalWithoutWeakeningAutomaticSelection() = runTest {
        val advertised = reader("read_document__selected")
        val withheld = reader("read_document__withheld")
        val hidden = reader("private_reader", visible = false)
        val definitions = listOf(advertised, withheld, hidden)
        var coldCalls = 0
        val catalog = ProgressiveToolCatalog(definitions, "Read repository instructions", acquireCold = { _, _ ->
            coldCalls++
            listOf(reader("cold_reader"))
        })
        catalog.recordSelection(definitions + catalog.definition, listOf(advertised, catalog.definition))
        for ((index, name) in listOf(withheld.name, "read_document", "cold_reader", hidden.name, "one,two", "one two", "x".repeat(160)).withIndex()) {
            val budgeted = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2)).bind(catalog)
            val query = if (index == 6) "document" + " ".repeat(4088) else "  document status $index  "
            val original = buildJsonObject {
                put("names", name)
                put("query", query)
            }
            assertEquals(original, catalog.canonicalArguments(original))
            val selectedBefore = catalog.selected(definitions + catalog.definition)
            val rejection = budgeted.execute("scalar-query-$index", original)
            assertTrue(rejection.toString(), rejection.isError)
            assertFalse(rejection.dispatched)
            assertEquals("scalar-query-$index", rejection.callId)
            assertEquals(selectedBefore, catalog.selected(definitions + catalog.definition))
            val content = (rejection.content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive(1), content["corrected_proposal_limit"])
            assertTrue(content.getValue("message").toString().contains("new call ID"))
            val correction = content.getValue("correction_call").jsonObject
            assertEquals(JsonPrimitive(ProgressiveToolCatalog.NAME), correction["tool"])
            val corrected = correction.getValue("arguments").jsonObject
            assertEquals(
                buildJsonObject {
                    put("names", JsonArray(listOf(JsonPrimitive(name))))
                    put("query", query)
                    put("catalog_only", true)
                },
                corrected
            )
            assertTrue(ToolArgumentValidator.errors(corrected, catalog.definition.inputSchema).isEmpty())
            val discovery = budgeted.execute("explicit-discovery-$index", corrected)
            assertFalse(discovery.toString(), discovery.isError)
            assertFalse(discovery.dispatched)
            val selected = catalog.selected(definitions + catalog.definition).map { it.name }.toSet()
            assertFalse(hidden.name in selected)
            assertFalse("cold_reader" in selected)
        }
        assertEquals(0, coldCalls)
    }

    @Test fun localDiscoverySuggestionsPreserveExplicitChoicesAndRejectMalformedOrMixedSelectors() = runTest {
        var coldCalls = 0
        val catalog = ProgressiveToolCatalog(listOf(reader("read_document")), "Read repository instructions", acquireCold = { _, _ ->
            coldCalls++
            emptyList()
        })
        val scalar = buildJsonObject {
            put("names", "unknown_reader")
            put("query", "document")
        }
        val action = buildJsonObject {
            put("action", "search")
            put("query", "document")
        }
        val invalid = listOf(
            JsonObject(action + ("catalog_only" to JsonPrimitive(false))),
            JsonObject(action + ("catalog_only" to JsonPrimitive("true"))),
            JsonObject(action + ("catalog_only" to JsonNull)),
            JsonObject(scalar + ("catalog_only" to JsonPrimitive(false))),
            JsonObject(scalar + ("catalog_only" to JsonPrimitive("true"))),
            JsonObject(scalar + ("catalog_only" to JsonNull)),
            JsonObject(scalar + ("names" to JsonPrimitive(""))),
            JsonObject(scalar + ("names" to JsonPrimitive(" "))),
            JsonObject(scalar + ("names" to JsonPrimitive("x".repeat(161)))),
            JsonObject(scalar + ("names" to JsonPrimitive(false))),
            JsonObject(scalar + ("names" to JsonObject(emptyMap()))),
            JsonObject(scalar + ("query" to JsonPrimitive(""))),
            JsonObject(scalar + ("query" to JsonPrimitive(false))),
            JsonObject(scalar + ("query" to JsonPrimitive("x".repeat(4097)))),
            JsonObject(scalar + ("action" to JsonPrimitive("search"))),
            JsonObject(scalar + ("inventory_cursor" to JsonPrimitive(0))),
            JsonObject(scalar + ("inventory_version" to JsonPrimitive("observed"))),
            JsonObject(scalar + ("repository_hint" to buildJsonObject { put("symbols", JsonArray(listOf(JsonPrimitive("ObservedSymbol")))) })),
            JsonObject(scalar + ("unexpected" to JsonPrimitive(true)))
        )
        for ((index, original) in invalid.withIndex()) {
            val budgeted = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1)).bind(catalog)
            assertEquals(original, catalog.canonicalArguments(original))
            val result = budgeted.execute("rejected-query-$index", original)
            assertTrue(result.toString(), result.isError)
            assertFalse(result.dispatched)
            assertFalse((result.content as ToolResultContent.Json).value.jsonObject.containsKey("correction_call"))
        }
        assertEquals(0, coldCalls)
    }

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
        assertEquals(canonical, result.executionReceipt?.get("canonical_arguments"))
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
        val emptyNames = Json.parseToJsonElement("""{"action":"catalog","names":[]}""").jsonObject
        assertEquals(buildJsonObject { put("catalog_only", true) }, catalog.canonicalArguments(emptyNames))
        for (raw in listOf(
            """{"action":"catalog","names":[],"catalog_only":false}""",
            """{"action":"write","names":[]}""",
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
        assertEquals(buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(definition.name)))) }, result.executionReceipt?.get("canonical_arguments"))
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
        assertEquals(selectedArguments("list"), result.executionReceipt?.get("canonical_arguments"))
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
        assertEquals(JsonObject(emptyMap()), result.executionReceipt?.get("canonical_arguments"))
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
