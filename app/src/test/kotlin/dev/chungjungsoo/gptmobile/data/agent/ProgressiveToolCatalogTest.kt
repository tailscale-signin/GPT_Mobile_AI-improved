package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveToolCatalogTest {

    @Test fun structuredRepositoryHintsSelectReadsWithoutEnglishAndNeverAcquireOtherConnections() = runTest {
        var acquisitions = 0
        val names = listOf("github_context", "github_read", "github_search", "github_write", "github_commit", "repository_graph", "personal_health")
        val definitions = names.map { AgentToolDefinition(it, "authorized fixture", buildJsonObject { put("type", "object") }) }
        val catalog = ProgressiveToolCatalog(definitions, "continue", githubMode = true, acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        })
        val request = buildJsonObject {
            put(
                "repository_hint",
                buildJsonObject {
                    put("feature_ids", JsonArray(listOf(JsonPrimitive("语音字幕"))))
                    // Even a name matching a mutation stays discovery data.
                    put("symbols", JsonArray(listOf(JsonPrimitive("github_write"))))
                    put("paths", JsonArray(listOf(JsonPrimitive("src/Voice.kt"))))
                }
            )
        }
        val result = catalog.execute("hint", request)
        assertFalse(result.isError)
        val data = (result.content as ToolResultContent.Json).value.jsonObject
        val selected = data["available_next_round"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("repository_graph" in selected)
        val nextRead = data["repository_context_call"]!!.jsonObject
        assertEquals("task_context_bundle", nextRead["arguments"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        assertTrue(nextRead["arguments"]!!.jsonObject["query"]!!.jsonPrimitive.content.contains("语音字幕"))
        assertTrue("github_read" in selected)
        assertFalse("github_write" in selected)
        assertFalse("github_commit" in selected)
        assertFalse("personal_health" in selected)
        assertEquals(0, acquisitions)
    }

    @Test fun repositoryContextCallsRequireTheObservedGraphContract() = runTest {
        for ((action, supportsQuery) in listOf("status" to true, "task_context_bundle" to false, "task_context_bundle" to true)) {
            val graph = AgentToolDefinition(
                "repository_graph__observed",
                "Authorized graph fixture",
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("required", JsonArray(listOf(JsonPrimitive("action"))))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "action",
                                buildJsonObject {
                                    put("type", "string")
                                    put("enum", JsonArray(listOf(JsonPrimitive(action))))
                                }
                            )
                            if (supportsQuery) put("query", buildJsonObject { put("type", "string") })
                        }
                    )
                },
                capabilities = setOf(AgentToolCapability.GITHUB)
            )
            var acquisitions = 0
            val catalog = ProgressiveToolCatalog(listOf(graph), "continue", githubMode = true, acquireCold = { _, _ ->
                acquisitions++
                emptyList()
            })
            val result = catalog.execute(
                "hint",
                buildJsonObject { put("repository_hint", buildJsonObject { put("feature_ids", JsonArray(listOf(JsonPrimitive("compiler")))) }) }
            )
            assertFalse(result.isError)
            val data = (result.content as ToolResultContent.Json).value.jsonObject
            assertTrue(data["available_next_round"]!!.jsonArray.any { it.jsonObject["name"] == JsonPrimitive(graph.name) })
            val proposed = data["repository_context_call"] as? JsonObject
            if (action == "task_context_bundle" && supportsQuery) {
                val call = requireNotNull(proposed)
                assertEquals(JsonPrimitive(graph.name), call["name"])
                assertTrue(ToolArgumentValidator.errors(call["arguments"]!!.jsonObject, graph.inputSchema).isEmpty())
            } else {
                assertNull("Unsupported graph contract must not produce a call: $action / $supportsQuery", proposed)
            }
            assertEquals(0, acquisitions)
        }
    }

    @Test fun repositoryHintsRequireGitHubScopeAndCannotContinueInventory() = runTest {
        val hint = buildJsonObject { put("repository_hint", buildJsonObject { put("symbols", JsonArray(listOf(JsonPrimitive("VoiceState")))) }) }
        val generic = ProgressiveToolCatalog(emptyList(), "hello")
        assertFalse(generic.definition.inputSchema["properties"]!!.jsonObject.containsKey("repository_hint"))
        assertEquals("INVALID_ARGUMENTS", generic.execute("outside", hint).errorCode)
        val github = ProgressiveToolCatalog(emptyList(), "continue", githubMode = true)
        assertTrue(github.definition.inputSchema["properties"]!!.jsonObject.containsKey("repository_hint"))
        val mixed = JsonObject(hint + ("inventory_cursor" to JsonPrimitive(10)))
        assertEquals("INVALID_ARGUMENTS", github.execute("mixed", mixed).errorCode)
        val empty = buildJsonObject { put("repository_hint", buildJsonObject {}) }
        assertEquals("INVALID_ARGUMENTS", github.execute("empty", empty).errorCode)
    }

    @Test fun repositoryHintRuntimeBoundsRejectBeforeAnyColdAcquisition() = runTest {
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(emptyList(), "continue", githubMode = true, acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        })
        val invalidLists = listOf(
            JsonArray((1..13).map { JsonPrimitive("identifier$it") }),
            JsonArray(listOf(JsonPrimitive("x".repeat(257)))),
            JsonArray(listOf(JsonPrimitive("   "))),
            JsonArray(listOf(JsonPrimitive("same"), JsonPrimitive("same"))),
            JsonArray(listOf(JsonPrimitive(1)))
        )
        for (field in listOf("feature_ids", "symbols", "paths")) {
            for (values in invalidLists + JsonPrimitive("not an array")) {
                val request = buildJsonObject { put("repository_hint", buildJsonObject { put(field, values) }) }
                val result = catalog.execute("invalid", request)
                assertEquals("$field: $values", "INVALID_ARGUMENTS", result.errorCode)
                assertFalse(result.dispatched)
            }
        }
        val unknown = buildJsonObject {
            put(
                "repository_hint",
                buildJsonObject {
                    put("symbols", JsonArray(listOf(JsonPrimitive("VoiceState"))))
                    put("connection", "other")
                }
            )
        }
        assertEquals("INVALID_ARGUMENTS", catalog.execute("unknown", unknown).errorCode)
        val accepted = buildJsonObject {
            put("repository_hint", buildJsonObject { put("symbols", JsonArray((1..12).map { JsonPrimitive(it.toString().padEnd(256, 'x')) })) })
        }
        assertFalse(catalog.execute("bounded", accepted).isError)
        assertEquals(0, acquisitions)
    }

    @Test fun selectedGitHubHintsFitTheExistingCompactNativeSchemaBudget() {
        val suffix = dev.chungjungsoo.gptmobile.data.github.GitHubPlanningContext(
            connectionUid = "fixture-connection",
            toolSuffix = "",
            repository = null
        ).toolDescription()
        val definitions = listOf("github_context_inspect", "github_read", "github_search").map { name ->
            GitHubOperationTool.definition(name).let { it.copy(description = it.description + suffix) }
        }
        val catalog = ProgressiveToolCatalog(definitions, "show git branches", githubMode = true)
        val costs = (definitions + catalog.definition).map { it.name to ProgressiveToolCatalog.schemaTokens(it) }
        assertTrue("Compact GitHub schema costs: $costs", costs.sumOf { it.second } <= 2000)
    }

    @Test fun discoveryActionRequiresOneToolBeforeColdAcquisition() = runTest {
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(emptyList(), "continue", acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        })
        val query = buildJsonObject { put("query", "workflow logs") }
        val action = JsonPrimitive("logs")
        val mixed = JsonObject(query + ("action" to action))
        val exact = buildJsonObject {
            put("action", action)
            put("names", JsonArray(listOf(JsonPrimitive("github_ci"))))
        }
        val schema = catalog.definition.inputSchema
        assertTrue(ToolArgumentValidator.errors(query, schema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(exact, schema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(JsonObject(emptyMap()), schema).isEmpty())
        val noNames = JsonObject(exact + ("names" to JsonArray(emptyList())))
        val multipleNames = JsonObject(exact + ("names" to JsonArray(listOf(JsonPrimitive("github_ci"), JsonPrimitive("github_pr")))))
        val nullAction = JsonObject(exact + ("action" to kotlinx.serialization.json.JsonNull))
        val unknownField = JsonObject(exact + ("unrecognized" to JsonPrimitive(true)))
        for (invalid in listOf(mixed, noNames, multipleNames, nullAction, unknownField)) {
            assertTrue(ToolArgumentValidator.errors(invalid, schema).isNotEmpty())
            val result = catalog.execute("discovery", invalid)
            assertEquals("INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertEquals(0, acquisitions)
    }

    @Test fun oversizedFullContextStillAllowsItsFittingInspectContract() {
        val full = GitHubOperationTool.definition("github_context", "github_context__native").copy(description = "long schema ".repeat(2000))
        val inspect = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__native")
        val definitions = listOf(full, inspect)
        for (ceiling in listOf(4096, null)) {
            val catalog = ProgressiveToolCatalog(definitions, "continue", githubMode = true, contextCeilingTokens = ceiling)
            val selected = catalog.selected(definitions + catalog.definition)
            assertTrue(selected.contains(inspect))
            assertFalse(selected.contains(full))
            assertTrue(selected.contains(catalog.definition))
        }
    }

    @Test fun smallObservedProviderContextNeverAdmitsOversizedSingleGitHubSchema() {
        val original = GitHubOperationTool.definition("github_pr", "github_pr__native")
        val oversized = original.copy(description = original.description + "oversized contract ".repeat(500))
        val catalog = ProgressiveToolCatalog(
            listOf(oversized),
            "Inspect the latest PR and verify CI",
            githubMode = true,
            contextCeilingTokens = 4096
        )
        val selected = catalog.selected(listOf(oversized, catalog.definition))
        val advertised = selected.filter { it.name != ProgressiveToolCatalog.NAME }
        assertTrue(advertised.sumOf { ProgressiveToolCatalog.schemaTokens(it) } <= 4096 / 3)
        assertFalse(advertised.any { it.name == oversized.name })
        assertTrue(selected.any { it.name == ProgressiveToolCatalog.NAME })
        val inventory = catalog.selectionOmissions(selected)
        assertTrue(inventory[oversized.name] in setOf("SCHEMA_TOO_LARGE", "SCHEMA_WINDOW_BUDGET"))
    }

    @Test fun routeCapacityIsOptInAndLargeVerifiedModelsRetainNormalSchemaWindow() {
        val original = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__native")
        val defaults = ProgressiveToolCatalog(listOf(original), "Check the repository", githubMode = true)
        val full = ProgressiveToolCatalog(
            listOf(original),
            "Check the repository",
            githubMode = true,
            contextCeilingTokens = 1_048_576
        )
        assertEquals(
            defaults.selected(listOf(original, defaults.definition)).map { it.name },
            full.selected(listOf(original, full.definition)).map { it.name }
        )
    }

    @Test fun fullFirstGitHubDefinitionsPreferCompactInitialContracts() {
        val requests = mapOf(
            "check repo status" to setOf("github_context_inspect", "github_pr_inspect"),
            "show git branches" to setOf("github_context_inspect", "github_search", "github_read"),
            "inspect commits" to setOf("github_context_inspect", "github_search", "github_read"),
            dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow.defaults.single { it.id == "status" }.prompt to setOf("github_context_inspect", "github_pr_inspect", "github_ci")
        )
        for (suffix in listOf("", "__native")) {
            val definitions = GitHubOperationTool.names.map { GitHubOperationTool.definition(it, it + suffix) }.sortedBy { it.name }
            for ((task, expected) in requests) {
                val catalog = ProgressiveToolCatalog(definitions, task, githubMode = suffix.isNotEmpty())
                val selected = catalog.selected(definitions + catalog.definition)
                assertEquals(task, expected.map { it + suffix }.toSet() + ProgressiveToolCatalog.NAME, selected.map { it.name }.toSet())
                assertTrue(selected.sumOf { ProgressiveToolCatalog.schemaTokens(it) } <= ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE)
                selected.filterNot { it.name == ProgressiveToolCatalog.NAME }.forEach { definition ->
                    assertSame(definitions.single { it.name == definition.name }, definition)
                }
            }
        }
    }

    @Test fun compactInitialSelectionPreservesConnectionFallbackAndExactFullDiscovery() = runTest {
        val primary = GitHubOperationTool.definition("github_context", "github_context__primary")
        val secondary = GitHubOperationTool.definition("github_context", "github_context__secondary")
        val compactSecondary = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__secondary")
        val hiddenPrimary = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__primary").copy(modelVisible = false)
        for (hidden in listOf(emptyList(), listOf(hiddenPrimary))) {
            val definitions = (listOf(primary, secondary, compactSecondary) + hidden).sortedBy { it.name }
            val catalog = ProgressiveToolCatalog(definitions, "check repo status", githubMode = true)
            val allowed = definitions + catalog.definition
            val initial = catalog.selected(allowed)
            assertEquals(setOf(primary.name, compactSecondary.name, ProgressiveToolCatalog.NAME), initial.map { it.name }.toSet())
            assertSame(primary, initial.single { it.name == primary.name })
            assertSame(compactSecondary, initial.single { it.name == compactSecondary.name })
            val inventory = catalog.execute("inventory", JsonObject(emptyMap()))
            assertFalse(inventory.isError)
            assertFalse(inventory.dispatched)
            val names = (inventory.content as ToolResultContent.Json).value.jsonObject.getValue("authorized_inventory").jsonArray
                .map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet()
            assertEquals(setOf(primary.name, secondary.name, compactSecondary.name), names)
            val discovered = catalog.execute("full-secondary", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(secondary.name)))) })
            assertFalse(discovered.isError)
            assertFalse(discovered.dispatched)
            val selected = catalog.selected(allowed)
            assertSame(primary, selected.single { it.name == primary.name })
            assertSame(secondary, selected.single { it.name == secondary.name })
            assertFalse(selected.any { it.name == compactSecondary.name || it.name == hiddenPrimary.name })
            assertFalse(catalog.selected(allowed - secondary).any { it.name == secondary.name })
        }
    }

    @Test fun omissionsRemainVisibleAfterTheOuterPlannerHasAlreadyFilteredSchemas() {
        val catalog = ProgressiveToolCatalog(tools, "task_1")
        val planned = catalog.selected(tools + catalog.definition).take(2)
        val omitted = catalog.selectionOmissions(planned)
        assertEquals(tools.map { it.name }.toSet() - planned.map { it.name }.toSet(), omitted.keys)
        assertTrue(omitted.values.contains("NOT_ADMITTED_TO_ROUND"))
        assertTrue(omitted.values.any { it != "NOT_ADMITTED_TO_ROUND" })
    }

    @Test fun emptyColdDiscoveryInstallsExecutableSchemasOnTheNextRound() = runTest {
        for ((task, toolName) in listOf("Read document" to "read_document", "Read source" to "github_read__github_api")) {
            var executions = 0
            var acquired = false
            val reader = object : AgentTool {
                override val definition = AgentToolDefinition(toolName, task, JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    executions++
                    return AgentToolResult(callId, ToolResultContent.Text("Observed evidence"), false)
                }
            }
            val catalog = ProgressiveToolCatalog(emptyList(), task, acquireCold = { _, _ ->
                acquired = true
                listOf(reader.definition)
            })
            var round = 0
            val advertised = mutableListOf<List<String>>()
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    advertised += tools.map { it.name }
                    when (round++) {
                        0 -> emit(ProviderEvent.ToolCall("discover", ProgressiveToolCatalog.NAME, JsonObject(emptyMap())))
                        1 -> {
                            emit(ProviderEvent.ToolCall("read", reader.definition.name, JsonObject(emptyMap())))
                        }
                        else -> emit(ProviderEvent.TextDelta("Evidence received"))
                    }
                    emit(ProviderEvent.Completed)
                }
            }
            val events = AgentRunner().run(session, listOf(catalog), dynamicTools = { if (acquired) listOf(reader) else emptyList() }, selectDefinitions = catalog::selected).toList()
            assertTrue("Cold acquisition for $task", acquired)
            assertEquals(listOf(ProgressiveToolCatalog.NAME), advertised.first())
            assertTrue("Advertised rounds: $advertised; events: $events", toolName in advertised[1])
            assertEquals("Events: $events", 1, executions)
            val read = events.filterIsInstance<AgentRunEvent.ToolFinished>().single { it.call.callId == "read" }
            assertTrue(read.result.dispatched)
            assertFalse(read.result.isError)
            val repeated = (catalog.execute("again", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
            assertEquals(JsonPrimitive(true), repeated["catalog_unchanged"])
        }
    }

    @Test fun deferredWritesRemainDiscoverableWithTheirExactSchemasAfterLongRequests() = runTest {
        val definitions = GitHubOperationTool.names.map {
            GitHubOperationTool.definition(it, it + "__github_api")
        }
        val goal = "Inspect the GitHub repository.\n" + "Retained diagnostic observation. ".repeat(200) + "\nRepair the failures, commit the reviewed changes and push."
        val writes = setOf("github_write__github_api", "github_commit__github_api")
        for (task in listOf(goal, goal + "\nContinue the accepted task.")) {
            val catalog = ProgressiveToolCatalog(definitions, task)
            assertTrue(catalog.selected(definitions).none { it.name in writes })
            val result = catalog.execute("discover", buildJsonObject { put("names", JsonArray(writes.map(::JsonPrimitive))) })
            assertFalse(result.isError)
            val selected = catalog.selected(definitions)
            assertTrue(selected.map { it.name }.containsAll(writes))
            writes.forEach { name -> assertEquals(definitions.single { it.name == name }, selected.single { it.name == name }) }
        }
    }

    @Test fun explicitGithubEngineeringGoalAdmitsStageAndCommitWithoutDiscovery() {
        val names = listOf(
            "github_context_inspect__github_api",
            "github_read__github_api",
            "github_stage_patch__github_api",
            "github_commit__github_api",
            "github_write__github_api"
        )
        val authorized = names.map { name ->
            AgentToolDefinition(
                name,
                "Authorized GitHub operation",
                JsonObject(emptyMap()),
                capabilities = setOf(AgentToolCapability.GITHUB)
            )
        }
        val task = "Inspect the GitHub repository, repair the failure, stage a patch and commit the tested fix."
        val catalog = ProgressiveToolCatalog(authorized, task, githubMode = true)
        val initial = catalog.selected(authorized + catalog.definition).map { it.name }.toSet()
        assertTrue(initial.toString(), "github_context_inspect__github_api" in initial)
        assertTrue(initial.toString(), "github_stage_patch__github_api" in initial)
        assertTrue(initial.toString(), "github_commit__github_api" in initial)
        assertFalse("github_write__github_api" in initial)
        assertTrue(ProgressiveToolCatalog.NAME in initial)
        assertTrue(
            catalog.selected(authorized.filterNot { it.name == "github_commit__github_api" } + catalog.definition)
                .none { it.name == "github_commit__github_api" }
        )

        val inspection = ProgressiveToolCatalog(authorized, "Inspect GitHub PR checks", githubMode = true)
        val inspectionNames = inspection.selected(authorized + inspection.definition).map { it.name }
        assertFalse("github_stage_patch__github_api" in inspectionNames)
        assertFalse("github_commit__github_api" in inspectionNames)
    }

    @Test fun githubModeRetainsAuthorizedInventoryWithoutMountingEveryOperation() = runTest {
        val operations = GitHubOperationTool.names.map { GitHubOperationTool.definition(it) }
        val catalog = ProgressiveToolCatalog(operations, "List GitHub issues and inspect PR checks", githubMode = true)
        val initial = catalog.selected(operations + catalog.definition).map { it.name }
        assertTrue("Initial GitHub context missing: $initial", initial.any { GitHubOperationTool.operationName(it) == "github_context" })
        assertFalse("Write schemas should be deferred: $initial", "github_write" in initial)
        assertTrue(initial.size < operations.size + 1)
        val discovery = catalog.execute(
            "need-write",
            buildJsonObject {
                put("names", JsonArray(listOf(JsonPrimitive("github_write"))))
            }
        )
        assertFalse(discovery.isError)
        assertTrue(catalog.selected(operations + catalog.definition).any { it.name == "github_write" })
    }

    @Test fun githubGreetingFallbackKeepsMutationsDeferredAndAuthorizedReadsAvailable() = runTest {
        val deferredOperations = setOf("github_write", "github_commit", "github_stage_patch", "github_release", "github_watch_checks")
            .map { AgentToolDefinition(it, "Authorized operation", JsonObject(emptyMap())) }
        val reader = AgentToolDefinition("mcp__team__get_file_contents", "Read a file", JsonObject(emptyMap()), capabilities = setOf(AgentToolCapability.GITHUB))
        for (task in listOf("hello", "continue")) {
            for (readers in listOf(emptyList(), listOf(reader))) {
                val definitions = deferredOperations + readers
                val catalog = ProgressiveToolCatalog(definitions, task, githubMode = true)
                val allowed = definitions + catalog.definition
                assertEquals(readers.map { it.name }.toSet() + ProgressiveToolCatalog.NAME, catalog.selected(allowed).map { it.name }.toSet())
                val discovered = catalog.execute("discover-write", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("github_write")))) })
                assertFalse(discovered.isError)
                assertFalse(discovered.dispatched)
                assertTrue(catalog.selected(allowed).contains(deferredOperations.first()))
            }
        }
    }

    @Test fun explicitLargeGithubDiscoveryReachesHardAdmissionInStatusAndIntegrationCatalogs() = runTest {
        val context = AgentToolDefinition("github_context", "Read repository context", JsonObject(emptyMap()))
        val recovery = AgentToolDefinition("read_recovery_context", "Read retained task", JsonObject(emptyMap()))
        val large = AgentToolDefinition("mcp__team__get_file_contents", "Read source ranges ".repeat(2000), JsonObject(emptyMap()), capabilities = setOf(AgentToolCapability.GITHUB))
        val otherLarge = large.copy(name = "mcp__team__get_tree")
        assertTrue(ProgressiveToolCatalog.schemaTokens(large) > ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE)
        val definitions = listOf(context, recovery, large, otherLarge)
        val requests = listOf(
            "Inspect the repository" to true,
            "check repo status" to true,
            "[github-workflow-preset:test_integration]\nTest GitHub integration" to false
        )
        for ((task, githubMode) in requests) {
            val catalog = ProgressiveToolCatalog(definitions, task, initialNames = setOf(context.name), githubMode = githubMode)
            val allowed = definitions + catalog.definition
            assertFalse(task, catalog.selected(allowed).contains(large))
            val discovered = catalog.execute("discover-large", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(large.name), JsonPrimitive(otherLarge.name)))) })
            assertFalse(task, discovered.isError)
            assertFalse(task, discovered.dispatched)
            val selected = catalog.selected(allowed)
            assertTrue(task, selected.contains(large))
            assertTrue(task, selected.contains(recovery))
            assertFalse("Only one schema may exceed the soft window: $task", selected.contains(otherLarge))
            assertFalse(task, catalog.selected(allowed - large).contains(large))
            val ample = AgentRunLimits(githubMode = githubMode, githubTask = true, contextTokens = 65536, finalResponseReserveTokens = 1024)
            assertTrue(task, fitProviderToolSchemas(selected, ample, 0, 0).contains(large))
            val tight = ample.copy(contextTokens = 4096)
            val deferred = fitProviderToolSchemas(selected, tight, 0, 0)
            assertFalse(task, deferred.contains(large))
            assertTrue(task, deferred.contains(catalog.definition))
            assertThrows(GitHubToolContextBudgetException::class.java) {
                fitProviderToolSchemas(selected - catalog.definition, tight, 0, 0)
            }
        }
    }

    private val tools = (1..80).map { AgentToolDefinition("task_$it", "Task $it", JsonObject(emptyMap())) }

    @Test fun healthSeparatesUnknownFromFailureAndExpiresObservationsWhenContractChanges() = runTest {
        var definition = tools.first()
        val catalog = ProgressiveToolCatalog(listOf(definition), "tools", acquireCold = { _, _ -> listOf(definition) })
        suspend fun page() = (catalog.execute("list", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        suspend fun row() = page().getValue("authorized_inventory").jsonArray.single().jsonObject
        val result = AgentToolResult("one", ToolResultContent.Text("ok"), false)
        catalog.recordObservation(definition.name, result)
        catalog.recordObservation(definition.name, result.copy(callId = "two", isError = true, errorCode = "TIMEOUT_OUTCOME_UNKNOWN"), 1)
        catalog.recordObservation(definition.name, result.copy(sharedResult = true))
        val observed = row()
        assertEquals(JsonPrimitive("last execution unknown"), observed["operational_status"])
        assertEquals(JsonPrimitive("stale"), observed["observation_freshness"])
        assertEquals(JsonPrimitive(1), observed["observed_success_count"])
        assertEquals(JsonPrimitive(0), observed["observed_failure_count"])
        assertEquals(JsonPrimitive(1), observed["observed_unknown_count"])
        val version = page()["inventory_version"]
        definition = definition.copy(contractVersion = AgentContractVersion(2, 0))
        catalog.execute("refresh", JsonObject(mapOf("query" to JsonPrimitive(definition.name))))
        assertTrue(version != page()["inventory_version"])
        assertEquals(JsonPrimitive("not tested"), row()["operational_status"])
        assertEquals(JsonPrimitive(false), row()["invocation_contract_supported"])
    }

    @Test fun githubActionGraphShowsAvailableAndMissingEvidenceWithoutGrantingTools() = runTest {
        val write = GitHubOperationTool.definition("github_write", "github_write__github_api")
        val pull = GitHubOperationTool.definition("github_pr", "github_pr__github_api")
        val definitions = listOf(write, pull)
        val catalog = ProgressiveToolCatalog(definitions, "Review and write")
        val page = (catalog.execute("inventory", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        val rows = page.getValue("authorized_inventory").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
        val writeLink = rows.getValue(write.name).getValue("workflow_links").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("github_read__github_api"), writeLink["source_tool"])
        assertEquals(JsonPrimitive(false), writeLink["source_available"])
        assertEquals(JsonPrimitive("optional_evidence"), writeLink["relation"])
        val prLinks = rows.getValue(pull.name).getValue("workflow_links").jsonArray.map { it.jsonObject }
        assertTrue(prLinks.any { it["action"] == JsonPrimitive("review") && it["source_tool"] == JsonPrimitive(pull.name) && it["source_available"] == JsonPrimitive(true) })
        assertFalse(catalog.selected(definitions).any { it.name == "github_read__github_api" })

        val withoutLinks = ProgressiveToolCatalog(listOf(write.copy(workflowLinks = emptyList()), pull), "Review and write")
        val otherPage = (withoutLinks.execute("inventory", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        assertTrue(page["inventory_version"] != otherPage["inventory_version"])
        assertTrue(runCatching { AgentToolWorkflowLink("", "source", "read", "purpose") }.isFailure)
        assertTrue(runCatching { write.copy(workflowLinks = write.workflowLinks + write.workflowLinks) }.isFailure)
    }

    @Test fun inventoryExposesOnlyDeclaredDependenciesWithoutGrantingAccess() = runTest {
        val definition = AgentToolDefinition("writer", "Write", JsonObject(emptyMap()), dependsOn = setOf("reader", "missing"))
        val reader = AgentToolDefinition("reader", "Read", JsonObject(emptyMap()))
        val catalog = ProgressiveToolCatalog(listOf(definition, reader), "writer")
        val page = (catalog.execute("list", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        val rows = page.getValue("authorized_inventory").jsonArray.map { it.jsonObject }.associateBy { it.getValue("name").jsonPrimitive.content }
        assertEquals("declared", rows.getValue("writer").getValue("dependency_metadata").jsonPrimitive.content)
        assertEquals(setOf("reader", "missing"), rows.getValue("writer").getValue("declared_dependencies").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(listOf("missing"), rows.getValue("writer").getValue("unavailable_dependencies").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("not declared", rows.getValue("reader").getValue("dependency_metadata").jsonPrimitive.content)
        assertFalse(catalog.selected(listOf(definition, reader)).any { it.name == "missing" })
        assertTrue(runCatching { AgentToolDefinition("self", "Invalid", JsonObject(emptyMap()), dependsOn = setOf("self")) }.isFailure)
        assertTrue(runCatching { AgentToolDefinition("many", "Invalid", JsonObject(emptyMap()), dependsOn = (1..33).map { "tool$it" }.toSet()) }.isFailure)
    }

    @Test fun runnerReportsOnlyObservedDispatchOutcomesInTheInventory() = runTest {
        val definition = AgentToolDefinition("example", "Example tool", JsonObject(emptyMap()))
        val tool = object : AgentTool {
            override val definition = definition
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("ok"), false)
        }
        val catalog = ProgressiveToolCatalog(listOf(definition), "example")
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) emit(ProviderEvent.ToolCall("one", "example", JsonObject(emptyMap()))) else emit(ProviderEvent.TextDelta("done"))
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner(healthCatalog = catalog).run(session, listOf(tool)).toList()
        val result = (catalog.execute("list", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        val row = result.getValue("authorized_inventory").jsonArray.single().jsonObject
        assertEquals("last execution succeeded", row.getValue("operational_status").jsonPrimitive.content)
        catalog.recordObservation("example", AgentToolResult("two", ToolResultContent.Text("blocked"), true, dispatched = false))
        val after = (catalog.execute("list", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        assertEquals("last execution succeeded", after.getValue("authorized_inventory").jsonArray.single().jsonObject.getValue("operational_status").jsonPrimitive.content)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentIdenticalColdDiscoveryUsesOneSuccessfulAcquisition() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var count = 0
        val tool = AgentToolDefinition("cold_tool", "Cold tool", JsonObject(emptyMap()))
        val catalog = ProgressiveToolCatalog(emptyList(), "cold", acquireCold = { _, _ ->
            count++
            release.await()
            listOf(tool)
        })
        val args = JsonObject(mapOf("query" to JsonPrimitive("cold_tool")))
        val first = async { catalog.execute("first", args) }
        val second = async { catalog.execute("second", args) }
        runCurrent()
        assertEquals(1, count)
        release.complete(Unit)
        assertFalse(first.await().isError)
        assertFalse(second.await().isError)
        assertEquals(1, count)
    }

    @Test fun inventoryContinuationRejectsAChangedCatalogAndCarriesContractFingerprints() = runTest {
        val added = AgentToolDefinition("aaa_new", "New tool", JsonObject(emptyMap()))
        val catalog = ProgressiveToolCatalog(tools.take(20), "tools", acquireCold = { query, _ -> if (query == "aaa_new") listOf(added) else emptyList() })
        val first = (catalog.execute("inventory", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        assertEquals("1", first.getValue("contract_version").jsonPrimitive.content)
        val continuation = first.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
        assertTrue(continuation.containsKey("inventory_version"))
        assertFalse(catalog.execute("next", continuation).isError)
        catalog.execute("add", JsonObject(mapOf("query" to JsonPrimitive("aaa_new"))))
        val stale = catalog.execute("stale", continuation)
        assertTrue(stale.isError)
        assertEquals("STALE_INVENTORY", stale.errorCode)
        assertTrue(catalog.execute("unversioned", JsonObject(mapOf("inventory_cursor" to JsonPrimitive(12)))).isError)
    }

    @Test fun explicitPrAliasMemoryAndStagingDiscoveryLoadAuthorizedSchemas() = runTest {
        val github = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.names.map {
            dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition(it, it + "__github_api")
        }
        val memory = AgentToolDefinition("memory", "Search authorized memory", JsonObject(emptyMap()))
        val definitions = github + memory
        val catalog = ProgressiveToolCatalog(definitions, "Review GitHub repository workflows and releases")
        val result = catalog.execute(
            "load",
            JsonObject(mapOf("names" to kotlinx.serialization.json.JsonArray(listOf("github_pull_request", "memory", "github_stage_patch").map(::JsonPrimitive))))
        )
        val names = catalog.selected(definitions).map { it.name }
        assertTrue("github_pr__github_api" in names)
        assertTrue("github_stage_patch__github_api" in names)
        assertTrue("memory" in names)
        assertFalse(result.isError)
        assertTrue(catalog.selected(definitions).sumOf { ProgressiveToolCatalog.schemaTokens(it) } <= ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE)
        val restricted = ProgressiveToolCatalog(listOf(memory), "GitHub")
        restricted.execute("no-grant", JsonObject(mapOf("names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("github_stage_patch"))))))
        assertFalse(restricted.selected(definitions).any { it.name.startsWith("github_stage_patch") })
        val alias = ProviderEvent.ToolCall("p", "github_pull_request", JsonObject(mapOf("action" to JsonPrimitive("list"))))
        assertEquals("github_pr__github_api", resolveGitHubReadAlias(alias, names.toSet()).name)
        assertEquals(alias, resolveGitHubReadAlias(alias, emptySet()))
    }

    @Test fun emptyDiscoveryReturnsReadOnlyInventory() = runTest {
        val catalog = ProgressiveToolCatalog(tools, "list all tools")
        val before = catalog.selected(tools)
        val result = catalog.execute("inventory", JsonObject(emptyMap()))
        assertFalse(result.isError)
        assertTrue(result.content.toString().contains("authorized_inventory"))
        assertEquals(before, catalog.selected(tools))
    }

    @Test fun repositorySynonymsPrioritizeCompactToolsAndRetainAuthorizedGatewayTools() {
        val native = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.names.map { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.definition(it) }
        val gateway = AgentToolDefinition("mcp__github__github_api", "Remote GitHub repository status", JsonObject(emptyMap()))
        for (task in listOf("check repo status", "inspect git", "show branches", "inspect commits", "review repositories")) {
            val catalog = ProgressiveToolCatalog(tools + native + gateway, task)
            val selected = catalog.selected(tools + native + gateway)
            assertEquals("github_context", GitHubOperationTool.operationName(selected.first().name))
            assertTrue(selected.any { it.name == gateway.name })
        }
    }

    @Test fun githubDiscoveryCanExpandTheInitialWindowButNeverExceedTheMaximumOrGrantAccess() = runTest {
        val github = AgentToolDefinition("github__github_api", "GitHub action details ".repeat(600), JsonObject(emptyMap()))
        assertTrue(ProgressiveToolCatalog.schemaTokens(github) > ProgressiveToolCatalog.SCHEMA_TOKEN_ALLOWANCE)
        val catalog = ProgressiveToolCatalog(tools + github, "what tools do you have")
        assertFalse(catalog.selected(tools + github).contains(github))
        catalog.execute("discover", JsonObject(mapOf("query" to JsonPrimitive("github"))))
        assertTrue(catalog.selected(tools + github).contains(github))
        assertFalse(catalog.selected(tools).contains(github))
        assertTrue(catalog.selected(tools + github).sumOf { ProgressiveToolCatalog.schemaTokens(it) } < ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE)
        val huge = github.copy(description = "x".repeat(100000))
        val oversized = ProgressiveToolCatalog(listOf(huge), "check repo status")
        oversized.execute("again", JsonObject(mapOf("query" to JsonPrimitive("github"))))
        assertEquals("SCHEMA_TOO_LARGE", oversized.selectionOmissions(listOf(huge))[huge.name])
        assertTrue(oversized.selected(listOf(huge)).isEmpty())
    }

    @Test fun plannedInitialSchemasDoNotRemoveDiscoverableAuthorization() = runTest {
        val planned = requireNotNull(ProgressiveToolCatalog.create(tools, "task_1"))
        val initial = planned.selected(tools).map { it.name }.take(3).toSet()
        val runner = requireNotNull(ProgressiveToolCatalog.create(tools, "task_1", initial))
        assertEquals(initial, runner.selected(tools).map { it.name }.toSet())
        runner.execute("d", JsonObject(mapOf("query" to JsonPrimitive("task_80"))))
        assertTrue(runner.selected(tools).any { it.name == "task_80" })
        assertFalse(runner.selected(tools.dropLast(1)).any { it.name == "task_80" })
    }

    @Test fun catalogIsBoundedAndDiscoveryCannotGrantPermission() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task_1"))
        assertEquals(8, catalog.selected(tools).size)
        val result = catalog.execute("d", JsonObject(mapOf("query" to JsonPrimitive("task_80"))))
        assertFalse(result.isError)
        assertTrue(catalog.selected(tools).any { it.name == "task_80" })
        assertFalse(catalog.selected(tools.dropLast(1)).any { it.name == "task_80" })
        assertTrue(catalog.selected(tools).size <= ProgressiveToolCatalog.INITIAL_TOOLS)
    }

    @Test fun coldToolDiscoveryReusesPreviouslyFetchedCatalogPerRun() = runTest {
        var fetches = 0
        val catalog = ProgressiveToolCatalog(tools, "task", acquireCold = { _, _ ->
            fetches++
            listOf(AgentToolDefinition("external_tool", "External", JsonObject(emptyMap())))
        })
        val query = JsonObject(mapOf("query" to JsonPrimitive("external_tool")))
        catalog.execute("first", query)
        catalog.execute("repeat", query)
        assertEquals(1, fetches)
        assertTrue(catalog.selected(tools + AgentToolDefinition("external_tool", "External", JsonObject(emptyMap()))).any { it.name == "external_tool" })
    }

    @Test fun unchangedDiscoveryIsExplicitlyReported() = runTest {
        val catalog = ProgressiveToolCatalog(tools.take(3), "task_1")
        val query = JsonObject(mapOf("names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("task_1")))))
        catalog.execute("first", query)
        val result = catalog.execute("again", query)
        assertTrue(result.content.toString().contains("catalog_unchanged"))
        assertTrue(result.content.toString().contains("true"))
    }

    @Test fun catalogDoesNotReplaceSmallOrCollidingToolSets() {
        assertNull(ProgressiveToolCatalog.create(tools.take(8), "task"))
        assertNull(ProgressiveToolCatalog.create(tools + AgentToolDefinition(ProgressiveToolCatalog.NAME, "Existing tool", JsonObject(emptyMap())), "task"))
    }

    @Test fun emptyDiscoveryDoesNotActivateTools() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task"))
        val inventory = catalog.execute("d", JsonObject(emptyMap()))
        assertFalse(inventory.isError)
        assertTrue(inventory.content.toString().contains("authorized_inventory"))
        assertEquals(8, catalog.selected(tools).size)
    }

    @Test fun repeatedBroadDiscoveryIsStableAndExplicitNamesCanStillReachEveryAuthorizedTool() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task"))
        val query = JsonObject(mapOf("query" to JsonPrimitive("task")))
        val seen = catalog.selected(tools).map { it.name }.toMutableSet()
        val initial = seen.toSet()
        repeat(10) { round ->
            val result = catalog.execute("discovery-$round", query)
            val advertised = catalog.selected(tools).map { it.name }.toSet()
            assertTrue(advertised.size <= ProgressiveToolCatalog.INITIAL_TOOLS)
            assertEquals(initial, advertised)
            assertTrue(result.content.toString().contains("catalog_unchanged"))
        }
        for (tool in tools) {
            catalog.execute("explicit-${tool.name}", JsonObject(mapOf("names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(tool.name))))))
            val advertised = catalog.selected(tools).map { it.name }.toSet()
            assertTrue(tool.name in advertised)
            assertTrue(advertised.size <= ProgressiveToolCatalog.INITIAL_TOOLS)
            seen += advertised
        }
        assertEquals(tools.map { it.name }.toSet(), seen)
        val result = catalog.execute("reactivate", JsonObject(mapOf("names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("task_1"))))))
        assertFalse(result.isError)
        assertTrue(catalog.selected(tools).any { it.name == "task_1" })
        assertEquals(ProgressiveToolCatalog.INITIAL_TOOLS, catalog.selected(tools).size)
    }

    @Test fun repeatedDiscoveryRetainsRequiredSearchAndReaderCapabilitiesWithinTheSchemaBudget() = runTest {
        val search = AgentToolDefinition("search_web", "Search published sources", JsonObject(emptyMap()), setOf(AgentToolCapability.WEB_SEARCH))
        val reader = AgentToolDefinition("read_source", "Read exact source content", JsonObject(emptyMap()), setOf(AgentToolCapability.PAGE_READER))
        val authorized = tools + search + reader
        val catalog = requireNotNull(ProgressiveToolCatalog.create(authorized, "Search latest sources and verify their contents"))
        val required = setOf(search.name, reader.name)
        assertTrue(catalog.selected(authorized).map { it.name }.containsAll(required))
        for (tool in tools) {
            catalog.execute("discover-${tool.name}", JsonObject(mapOf("query" to JsonPrimitive(tool.name))))
            val selected = catalog.selected(authorized)
            assertTrue(selected.map { it.name }.containsAll(required + tool.name))
            assertTrue(selected.size <= ProgressiveToolCatalog.INITIAL_TOOLS)
            assertTrue(selected.sumOf { ProgressiveToolCatalog.schemaTokens(it) } <= ProgressiveToolCatalog.SCHEMA_TOKEN_ALLOWANCE)
        }
        assertFalse(catalog.selected(authorized - reader).contains(reader))
    }

    @Test fun coldDiscoveredReaderStaysAvailableAcrossFurtherToolDiscoveries() = runTest {
        val search = AgentToolDefinition("search_web", "Search sources", JsonObject(emptyMap()), setOf(AgentToolCapability.WEB_SEARCH))
        val reader = AgentToolDefinition("remote_reader", "Read source content", JsonObject(emptyMap()), setOf(AgentToolCapability.PAGE_READER))
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(tools + search, "Verify latest sources", acquireCold = { _, _ ->
            acquisitions++
            listOf(reader)
        })
        val readerQuery = JsonObject(mapOf("query" to JsonPrimitive(reader.name)))
        catalog.execute("acquire", readerQuery)
        catalog.execute("repeat-acquire", readerQuery)
        assertEquals(1, acquisitions)
        val authorized = tools + search + reader
        for (name in listOf("task_80", "task_40", "task_70", "task_80")) {
            catalog.execute(name, JsonObject(mapOf("names" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(name))))))
            assertTrue(catalog.selected(authorized).map { it.name }.containsAll(listOf(search.name, reader.name, name)))
        }
        assertEquals(emptyList<AgentToolDefinition>(), catalog.selected(emptyList()))
    }

    @Test fun explicitKnownToolRemainsDiscoverableAfterActivation() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task_1"))
        val result = catalog.execute("known", JsonObject(mapOf("query" to JsonPrimitive("task_1"))))
        assertFalse(result.isError)
        assertTrue(result.content.toString().contains("task_1"))
        assertTrue(catalog.selected(tools).any { it.name == "task_1" })
    }

    @Test fun fewHugeSchemasStillActivateProgressiveDiscovery() = runTest {
        val huge = AgentToolDefinition("huge", "x".repeat(100000), JsonObject(emptyMap()))
        val source = listOf(huge) + tools.take(4)
        val catalog = requireNotNull(ProgressiveToolCatalog.create(source, "huge"))
        assertFalse(catalog.selected(source).any { it.name == "huge" })
        val result = catalog.execute("discover", JsonObject(mapOf("query" to JsonPrimitive("huge"))))
        assertTrue(result.content.toString().contains("schema_budget_excluded"))
        assertFalse(catalog.selected(source).any { it.name == "huge" })
    }

    @Test fun repeatedDiscoveryDoesNotAccumulateAllSchemas() = runTest {
        val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, "task"))
        for (index in 1..80) {
            catalog.execute("call-$index", JsonObject(mapOf("query" to JsonPrimitive("task_$index"))))
            assertTrue(catalog.selected(tools).size <= ProgressiveToolCatalog.INITIAL_TOOLS)
            assertTrue(catalog.selected(tools).sumOf { ProgressiveToolCatalog.schemaTokens(it).toLong() } <= ProgressiveToolCatalog.SCHEMA_TOKEN_ALLOWANCE)
            assertTrue(catalog.selected(tools).any { it.name == "task_$index" })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun coldDiscoverySuspendsWithoutHoldingTheSelectionMonitor() = runTest {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val acquired = AgentToolDefinition("cold_tool", "Authorized cold tool", JsonObject(emptyMap()))
        val catalog = ProgressiveToolCatalog(emptyList(), "cold", acquireCold = { _, _ ->
            release.await()
            listOf(acquired)
        })
        val discovery = kotlinx.coroutines.CoroutineScope(coroutineContext).async { catalog.execute("discover", JsonObject(mapOf("query" to JsonPrimitive("cold_tool")))) }
        runCurrent()
        assertEquals(listOf(catalog.definition), catalog.selected(listOf(catalog.definition)))
        release.complete(Unit)
        assertFalse(discovery.await().isError)
        assertEquals(listOf(acquired), catalog.selected(listOf(acquired)))
        assertTrue(catalog.selected(emptyList()).isEmpty())
    }

    @Test fun recoveryReaderSurvivesDiscoveryAndUnknownNamesAreCachedPerVersion() = runTest {
        val reader = AgentToolDefinition("read_recovery_context", "Read saved checkpoint", JsonObject(emptyMap()))
        var cold = 0
        val definitions = tools + reader
        val catalog = ProgressiveToolCatalog(definitions, "Resume GitHub task", acquireCold = { _, _ ->
            cold++
            emptyList()
        })
        val args = buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("github__github_api")))) }
        catalog.execute("first", args)
        catalog.execute("again", args)
        assertEquals(1, cold)
        assertTrue(catalog.selected(definitions).any { it.name == reader.name })
    }
}
