package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayInventoryTool
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayToolInventory
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolInventoryTest {
    private fun definition(name: String, vararg actions: String) = AgentToolDefinition(
        name,
        "Connection-specific help",
        buildJsonObject {
            put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray(actions.map(::JsonPrimitive))) }) })
        }
    )

    @Test fun builtInsDescribeRegistrationWithoutGrantingAvailability() {
        val known = GitHubToolInventory.registered.associateBy { it.name }
        assertEquals(AppFeatureInventory.gitHubCatalog.keys + AppFeatureInventory.toolProducers.keys, known.keys)
        assertTrue("merge" in known.getValue("github_pr").actions)
        assertFalse("merge" in known.getValue("github_pr_inspect").actions)
        assertTrue("task_context_bundle" in known.getValue("repository_graph").actions)
        val shipped = AppFeatureInventory.packagedNativeTools.values.first { (it.inputSchema["properties"] as? JsonObject).orEmpty().isNotEmpty() }
        assertEquals(shipped.description, known.getValue(shipped.name).description)
        assertEquals((shipped.inputSchema["properties"] as JsonObject).keys, known.getValue(shipped.name).inputFields.toSet())
        assertTrue(known.values.all { it.authorized == null && it.executableNow == null })
    }

    @Test fun mountedKeepsExactVisibleContractsAndDoesNotMergeActionsAcrossConnections() {
        val left = definition("github_pr_inspect__left", "list", "context")
        val right = definition("github_pr__right", "review")
        val external = definition("mcp__project__search", "catalog")
        val official = definition("mcp__work__get_pull_request").copy(capabilities = setOf(AgentToolCapability.GITHUB))
        val conflicting = definition("duplicate", "old")
        val rows = GitHubToolInventory.mounted(listOf(left, right, external, official, definition("hidden").copy(modelVisible = false), conflicting, conflicting.copy(inputSchema = right.inputSchema)))
        assertEquals(listOf(left.name, right.name, external.name, official.name), rows.map { it.name })
        assertEquals(listOf("github_pr", "github_pr", external.name, "github_pr"), rows.map { it.family })
        assertEquals(listOf("context", "list"), rows.first().actions)
        assertEquals(listOf("review"), rows[1].actions)
        assertEquals(listOf("catalog"), rows[2].actions)
        assertTrue(rows.all { it.authorized == true && it.executableNow == null })
    }

    @Test fun runObservationMatchesTheExactConversationAndFrozenWorkspace() {
        val scope = GitHubConversationContext("work", "team", "app", "main", "a".repeat(40), "profile")
        val tool = definition("github_pr__left", "list")
        GitHubToolInventory.record("inventory-test", 4, scope, listOf(tool), mapOf(tool.name to "native-left"))
        val observed = GitHubToolInventory.observations.value.single { it.runId == "inventory-test" }
        assertTrue(observed.matches(4, scope.copy(commitSha = "b".repeat(40))))
        assertFalse(observed.matches(9, scope))
        assertFalse(observed.matches(4, scope.copy(connectionUid = "different")))
        assertFalse(observed.matches(4, scope.copy(mainProfileUid = "different")))
        assertFalse(observed.matches(4, scope.copy(ref = "Main")))
        assertFalse(observed.matches(4, scope.copy(repo = "other")))
        assertEquals("native-left", observed.tools.single().sourceId)
        assertEquals("", observed.tools.single().description)
        GitHubToolInventory.record("inventory-test", 4, scope, emptyList(), emptyMap())
        assertTrue(GitHubToolInventory.observations.value.single { it.runId == "inventory-test" }.tools.isEmpty())
        GitHubToolInventory.forgetChat(4)
        assertTrue(GitHubToolInventory.observations.value.none { it.runId == "inventory-test" })
    }

    @Test fun coldAcquisitionUpdatesOnlyItsAdmittedRunAndRetainsOtherMountedTools() = runTest {
        val scope = GitHubConversationContext("work", "team", "app", "main", "a".repeat(40), "profile")
        val initial = definition("github_read__left")
        val cold = definition("mcp__external__inspect", "summary")
        val tool = object : AgentTool {
            override val definition = cold
            override val coordinatesChildWork = false
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Inventory must not execute tools")
        }
        val acquired = listOf(ResolvedAgentTool(tool, "remote", "Remote", "inspect", cold.name))
        val run = "inventory-discovery-test"
        GitHubToolInventory.record(run, 7, scope, listOf(initial), emptyMap())
        fun rows() = GitHubToolInventory.observations.value.single { it.runId == run }.tools
        withContext(ToolRunContext("different")) { GitHubToolInventory.recordDiscovered(7, scope, acquired) }
        withContext(ToolRunContext(run)) {
            GitHubToolInventory.recordDiscovered(7, scope.copy(ref = "elsewhere"), acquired)
            assertEquals(listOf(initial.name), rows().map { it.name })
            GitHubToolInventory.recordDiscovered(7, scope, acquired)
            GitHubToolInventory.recordDiscovered(7, scope, acquired)
        }
        assertEquals(setOf(initial.name, cold.name), rows().map { it.name }.toSet())
        assertEquals(2, rows().size)
        assertEquals("remote", rows().single { it.name == cold.name }.sourceId)
    }

    @Test fun gatewayPreservesSourceSpecificAvailabilityAndUnknownLegacyPermissions() {
        val ready = GatewayInventoryTool("check", "Inspect", listOf("id"), sourceId = "server-one", authorized = true, executableNow = true)
        val blocked = ready.copy(sourceId = "server-two", enabled = false, executableNow = false, blockedReason = "disabled")
        val current = GatewayToolInventory(listOf(ready, blocked), 2, 10, scope = "selected_gateway_authorized_mcp")
        val rows = GitHubToolInventory.gateway(current)
        assertEquals(listOf("server-one", "server-two"), rows.map { it.sourceId })
        assertEquals(listOf(true, false), rows.map { it.executableNow })
        assertEquals("disabled", rows.last().blockedReason)
        assertTrue(rows.all { it.inputFields == listOf("id") && it.actions.isEmpty() })
        val legacy = GitHubToolInventory.gateway(GatewayToolInventory(listOf(GatewayInventoryTool("check", null, emptyList())), 1, 10)).single()
        assertNull(legacy.authorized)
        assertNull(legacy.enabled)
        assertNull(legacy.executableNow)
    }
}
