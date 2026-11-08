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
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveGitHubPresetTest {
    private val task = "Test all authorized GitHub integration tools.\n[github-workflow-preset:test_integration]"

    @Test fun explicitPresetMountsEveryAuthorizedGitHubSchemaBeforeDiscovery() = runTest {
        val authorized = GitHubOperationTool.names.sorted().map { GitHubOperationTool.definition(it, it + "__github_api") }
        val hidden = AgentToolDefinition("github_hidden", "hidden", JsonObject(emptyMap()), modelVisible = false)
        val catalog = ProgressiveToolCatalog(authorized + hidden, task, initialNames = setOf("github_context__github_api"))
        assertEquals(authorized.map { it.name }.toSet(), catalog.selected(authorized + hidden).map { it.name }.toSet())
        val inventory = (catalog.execute("catalog", JsonObject(emptyMap())).content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive(false), inventory["permissions_changed"])
        assertEquals(JsonPrimitive(false), inventory["has_more"])
        assertEquals(JsonPrimitive("AUTHORIZED_GITHUB_UPFRONT"), inventory["mount_policy"])
        assertEquals(authorized.size, inventory.getValue("authorized_inventory").jsonArray.size)
        assertTrue(inventory.getValue("authorized_inventory").jsonArray.all { it.jsonObject["operational_status"] == JsonPrimitive("not tested") })
        assertEquals(JsonPrimitive(0), inventory.getValue("coverage").jsonObject["dispatched_calls"])
        assertFalse(inventory.toString().contains("github_hidden"))
    }

    @Test fun explicitNameLookupDoesNotEvictOtherPresetSchemasOrGrantAbsentOnes() = runTest {
        val authorized = listOf("github_read", "github_commit", "github_issue").map { GitHubOperationTool.definition(it) }
        val catalog = ProgressiveToolCatalog(authorized, task)
        catalog.execute("lookup", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive("github_read"), JsonPrimitive("github_not_authorized")))) })
        assertEquals(authorized.map { it.name }.toSet(), catalog.selected(authorized).map { it.name }.toSet())
        assertFalse(catalog.selected(authorized).any { it.name == "github_not_authorized" })
    }

    @Test fun ordinaryRepositoryRequestsRetainProgressiveWriteDiscovery() {
        val authorized = listOf("github_context", "github_read", "github_commit", "github_write").map { GitHubOperationTool.definition(it) }
        val catalog = ProgressiveToolCatalog(authorized, "Inspect the GitHub repository")
        assertFalse(catalog.selected(authorized).any { it.name in setOf("github_commit", "github_write") })
    }
}
