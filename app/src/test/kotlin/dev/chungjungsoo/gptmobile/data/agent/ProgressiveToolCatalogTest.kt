package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveToolCatalogTest {
    private val tools = (1..80).map { AgentToolDefinition("task_$it", "Task $it", JsonObject(emptyMap())) }

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
            assertEquals("github_context", selected.first().name)
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
}
