package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStatusCatalogTest {
    private val definitions = GitHubOperationTool.names.map { GitHubOperationTool.definition(it, it + "__github_api") }
    private val initialNames = setOf("github_context__github_api", "github_pr__github_api", ProgressiveToolCatalog.NAME)

    @Test fun explicitStatusUsesTwoSchemasAndDiscoveryWithoutDiscardingAuthorization() = runTest {
        val task = "Inspect the GitHub repository status and open PRs"
        val catalog = ProgressiveToolCatalog(definitions, task, githubMode = true)
        val allowed = definitions + catalog.definition
        val selected = catalog.selected(allowed)
        assertEquals(initialNames, selected.map { it.name }.toSet())
        assertTrue(selected.sumOf { ProgressiveToolCatalog.schemaTokens(it) } < definitions.sumOf { ProgressiveToolCatalog.schemaTokens(it) } / 2)
        val exactRead = definitions.single { it.name == "github_read__github_api" }
        val discovery = catalog.execute("read-source", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(exactRead.name)))) })
        assertFalse(discovery.isError)
        assertEquals(exactRead, catalog.selected(allowed).single { it.name == exactRead.name })
        assertTrue(catalog.selected(allowed).map { it.name }.containsAll(initialNames))
        assertFalse(catalog.selected(allowed.filterNot { it.name == exactRead.name }).any { it.name == exactRead.name })
    }

    @Test fun statusPlanningDoesNotReattachTheNineteenOperationCatalog() {
        for (adaptive in listOf(true, false)) {
            val task = "status"
            val backend = GitHubTool()
            val operations = backend.operations()
            val catalog = ProgressiveToolCatalog(operations.map { it.definition }, task, githubMode = true)
            val resolved = (operations + catalog).map { tool -> ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name, progressiveCatalog = catalog.takeIf { tool === catalog }) }
            val turn = ConversationTurn(MessageV2(content = task, platformType = null), null, true)
            val plan = planAuthorizedToolContext(listOf(turn), "Inspect the exact repository status.", resolved, TokenBudgetSettings(contextTokens = 8192, outputTokens = 1024, adaptiveToolCatalog = adaptive), externalTools = true, githubMode = true)
            assertEquals(setOf("github_context", "github_pr", ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
            assertEquals(resolved.map { it.tool.definition.name }.toSet(), plan.authorizedTools.map { it.name }.toSet())
            assertTrue(plan.tools.sumOf { ProgressiveToolCatalog.schemaTokens(it) } < operations.sumOf { ProgressiveToolCatalog.schemaTokens(it.definition) } / 2)
        }
    }

    @Test fun repairSourceAuditAndIntegrationRequestsKeepAuthorizedSchemasDiscoverable() = runTest {
        for (task in listOf("Inspect the repository code and fix the failures", "Audit repository security and current status", "status\n[github-workflow-preset:test_integration]")) {
            val catalog = ProgressiveToolCatalog(definitions, task, githubMode = true)
            assertFalse(catalog.scopedGitHubStatus)
            val initial = catalog.selected(definitions + catalog.definition)
            assertTrue(task, initial.any { GitHubOperationTool.operationName(it.name) == "github_context" })
            assertTrue(task, initial.size < definitions.size + 1)
            val deferred = definitions.first { GitHubOperationTool.operationName(it.name) == "github_write" }
            assertFalse(task, initial.any { it.name == deferred.name })
            assertFalse(catalog.execute("discover-write", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(deferred.name)))) }).isError)
            assertTrue(task, catalog.selected(definitions + catalog.definition).any { it.name == deferred.name })
        }
    }

    @Test fun statusKeepsMandatorySavedRecoveryAlongsideItsScopedReadSchemas() {
        val recovery = AgentToolDefinition("read_recovery_context", "Read the retained receipt for this task", buildJsonObject { put("type", "object") })
        val catalog = ProgressiveToolCatalog(definitions + recovery, "repo status", githubMode = true)
        assertEquals(initialNames + recovery.name, catalog.selected(definitions + recovery + catalog.definition).map { it.name }.toSet())
    }
}
