package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveGitHubPresetTest {
    private val task = "Test all authorized GitHub integration tools.\n[github-workflow-preset:test_integration]"

    @Test fun initialWindowHonorsOnlyVisibleExactNamesWithinEachGitHubConnection() {
        val primary = GitHubOperationTool.definition("github_context", "github_context__primary")
        val compactPrimary = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__primary")
        val secondary = GitHubOperationTool.definition("github_context", "github_context__secondary")
        val compactSecondary = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__secondary")
        val definitions = listOf(primary, compactPrimary, secondary, compactSecondary).sortedBy { it.name }
        val compactNames = setOf(compactPrimary.name, compactSecondary.name)
        val selections = listOf<Pair<Set<String>?, Set<String>>>(
            setOf(primary.name) to setOf(primary.name, compactSecondary.name),
            setOf(compactPrimary.name) to compactNames,
            setOf(primary.name, compactPrimary.name) to compactNames,
            setOf(secondary.name) to setOf(compactPrimary.name, secondary.name),
            null to compactNames,
            emptySet<String>() to compactNames,
            setOf("github_context", "github_context__absent") to compactNames
        )
        for ((initialNames, expected) in selections) {
            val catalog = ProgressiveToolCatalog(definitions, "check repo status", initialNames, githubMode = true)
            val allowed = definitions + catalog.definition
            val initial = catalog.selected(allowed)
            assertEquals(initialNames.toString(), expected + ProgressiveToolCatalog.NAME, initial.map { it.name }.toSet())
            assertTrue(initial.sumOf { ProgressiveToolCatalog.schemaTokens(it) } <= ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE)
            initial.filterNot { it.name == ProgressiveToolCatalog.NAME }.forEach { definition ->
                assertSame(definitions.single { it.name == definition.name }, definition)
            }
            if (initialNames == setOf(primary.name)) assertSame(primary, catalog.selected(listOf(primary)).single())
            assertFalse(catalog.selected(allowed - primary).any { it.name == primary.name })
        }
        for ((hidden, visible) in listOf(primary to compactPrimary, compactPrimary to primary)) {
            val restricted = definitions.map { if (it.name == hidden.name) it.copy(modelVisible = false) else it }
            val catalog = ProgressiveToolCatalog(restricted, "check repo status", setOf(hidden.name, "github_context__absent"), githubMode = true)
            val initial = catalog.selected(restricted + catalog.definition)
            assertEquals(setOf(visible.name, compactSecondary.name, ProgressiveToolCatalog.NAME), initial.map { it.name }.toSet())
            assertSame(visible, initial.single { it.name == visible.name })
            assertSame(compactSecondary, initial.single { it.name == compactSecondary.name })
            assertFalse(initial.any { it.name == hidden.name || it.name == "github_context__absent" })
        }
    }

    @Test fun explicitPresetKeepsFullAuthorizationWhileMountingAFittingInitialWindow() = runTest {
        val authorized = GitHubOperationTool.names.sorted().map { GitHubOperationTool.definition(it, it + "__github_api") }
        val hidden = AgentToolDefinition("github_hidden", "hidden", JsonObject(emptyMap()), modelVisible = false)
        val catalog = ProgressiveToolCatalog(authorized + hidden, task, initialNames = setOf("github_context__github_api"))
        val initial = catalog.selected(authorized + hidden)
        assertTrue(initial.any { it.name == "github_context__github_api" })
        assertTrue(initial.size < authorized.size)
        assertFalse(initial.any { it.name == "github_hidden" })
        val inventory = (catalog.execute("catalog", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive(false), inventory["permissions_changed"])
        assertEquals(JsonPrimitive(false), inventory["has_more"])
        assertEquals(JsonPrimitive("AUTHORIZED_GITHUB_ON_DEMAND"), inventory["mount_policy"])
        assertEquals(authorized.size, inventory.getValue("authorized_inventory").jsonArray.size)
        assertTrue(inventory.getValue("authorized_inventory").jsonArray.all { it.jsonObject["operational_status"] == JsonPrimitive("not tested") })
        assertEquals(JsonPrimitive(0), inventory.getValue("coverage").jsonObject["dispatched_calls"])
        assertFalse(inventory.toString().contains("github_hidden"))
    }

    @Test fun explicitNameLookupRetainsTheWindowAndCannotGrantAbsentSchemas() = runTest {
        val authorized = listOf("github_read", "github_commit", "github_issue").map { GitHubOperationTool.definition(it) }
        val catalog = ProgressiveToolCatalog(authorized, task)
        val before = catalog.selected(authorized).map { it.name }.toSet()
        catalog.execute("lookup", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("github_read"), JsonPrimitive("github_not_authorized")))) })
        val after = catalog.selected(authorized).map { it.name }.toSet()
        assertTrue(after.containsAll(before))
        assertTrue("github_read" in after)
        assertTrue(after.size < authorized.size)
        assertFalse(catalog.selected(authorized).any { it.name == "github_not_authorized" })
    }

    @Test fun ordinaryRepositoryRequestsRetainProgressiveWriteDiscovery() {
        val authorized = listOf("github_context", "github_read", "github_commit", "github_write").map { GitHubOperationTool.definition(it) }
        val catalog = ProgressiveToolCatalog(authorized, "Inspect the GitHub repository")
        assertFalse(catalog.selected(authorized).any { it.name in setOf("github_commit", "github_write") })
    }
}
