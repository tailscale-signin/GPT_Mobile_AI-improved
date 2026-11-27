package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GitHubQuickWorkflow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStatusCatalogTest {
    private val definitions = GitHubOperationTool.names.map { GitHubOperationTool.definition(it, it + "__github_api") }
    private val initialNames = setOf("github_context_inspect__github_api", "github_pr_inspect__github_api", ProgressiveToolCatalog.NAME)

    @Test fun gitHubChatRoutesExplicitFeatureRequestsWhileSourceQuestionsAndExclusionsStayScoped() = runTest {
        val clock = AgentToolDefinition("current_date", "Local clock", buildJsonObject {})
        val search = AgentToolDefinition("web_search", "Search the web", buildJsonObject {}, capabilities = setOf(AgentToolCapability.WEB_SEARCH))
        val supplied = definitions + clock + search
        fun selected(task: String): List<String> {
            val catalog = ProgressiveToolCatalog(supplied, task, githubMode = true)
            return catalog.selected(supplied + catalog.definition).map { it.name }
        }
        assertTrue("current_date" in selected("Use the current time and date to explain the GitHub run timestamp"))
        assertFalse("web_search" in selected("Where is the search the web function defined in this repository?"))
        assertFalse("web_search" in selected("Use only the attached file, without web search, to inspect the GitHub repo"))
        val catalog = ProgressiveToolCatalog(supplied, "Inspect the repository", githubMode = true)
        val found = catalog.execute("clock", buildJsonObject { put("query", "current time and date") })
        assertFalse(found.isError)
        assertTrue("current_date" in catalog.selected(supplied + catalog.definition).map { it.name })
        assertFalse("current_date" in catalog.selected(definitions + catalog.definition).map { it.name })
    }

    @Test fun repositorySourceTasksRankAuthorizedOfficialReadsInSmallAndMixedCatalogs() = runTest {
        fun official(operation: String) = AgentToolDefinition("mcp__work__$operation", operation, buildJsonObject {}, capabilities = setOf(AgentToolCapability.GITHUB))
        val search = official("search_code")
        val read = official("get_file_contents")
        val write = official("push_files")
        val lookalike = search.copy(name = "mcp__unknown__search_code", capabilities = emptySet())
        val question = "Where is search implemented in the repository?"
        val native = definitions.single { it.name == "github_search__github_api" }
        for (extra in listOf(emptyList(), listOf(native))) {
            val supplied = listOf(search, read, write, lookalike) + extra
            val catalog = requireNotNull(ProgressiveToolCatalog.create(supplied, question, githubMode = true))
            val allowed = supplied + catalog.definition
            val initial = catalog.selected(allowed).map { it.name }
            assertTrue(search.name in initial && read.name in initial)
            assertFalse(write.name in initial || lookalike.name in initial)
            assertFalse(catalog.execute("find-source", buildJsonObject { put("query", question) }).isError)
            val discovered = catalog.selected(allowed).map { it.name }
            assertTrue(search.name in discovered && read.name in discovered)
            assertFalse(write.name in discovered || lookalike.name in discovered)
        }
    }

    @Test fun directPullRequestLookupsOfferOnlyMatchingOfficialReads() {
        fun official(operation: String) = AgentToolDefinition("mcp__work__$operation", operation, buildJsonObject {}, capabilities = setOf(AgentToolCapability.GITHUB))
        val read = official("get_pull_request")
        val list = official("list_pull_requests")
        val merge = official("merge_pull_request")
        val untrusted = read.copy(name = "mcp__unknown__get_pull_request", capabilities = emptySet())
        val supplied = listOf(read, list, merge, untrusted)
        fun selected(task: String, tools: List<AgentToolDefinition>): Set<String> {
            val catalog = requireNotNull(ProgressiveToolCatalog.create(tools, task, githubMode = true))
            return catalog.selected(tools + catalog.definition).mapTo(hashSetOf()) { it.name }
        }
        assertEquals(setOf(read.name, ProgressiveToolCatalog.NAME), selected("Can you inspect PR #42?", supplied))
        assertEquals(setOf(list.name, ProgressiveToolCatalog.NAME), selected("List all open PRs", supplied))
        assertEquals(setOf(ProgressiveToolCatalog.NAME), selected("Inspect PR #42", supplied - read))
    }

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

    @Test fun singleTopicLookupProjectsOneReadActionAndCanDiscoverTheFullAuthorizedContract() = runTest {
        for ((request, operation, action) in listOf(
            Triple("List all open issues", "github_issue", "list"),
            Triple("Summarize PR #42", "github_pr_inspect", "context"),
            Triple("Show all tags", "github_release", "tags"),
            Triple("List workflow runs", "github_ci", "runs")
        )) {
            val catalog = ProgressiveToolCatalog(definitions, request, githubMode = true)
            val allowed = definitions + catalog.definition
            val source = definitions.single { it.name == operation + "__github_api" }
            val selected = catalog.selected(allowed)
            assertEquals(request, setOf(source.name, ProgressiveToolCatalog.NAME), selected.map { it.name }.toSet())
            assertEquals(request, GitHubActionSchema.project(source, action), selected.single { it.name == source.name })
            assertEquals(request, listOf(catalog.definition), catalog.selected(allowed - source))
            assertFalse(catalog.execute("discover-full", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(source.name)))) }).isError)
            assertEquals(request, source, catalog.selected(allowed).single { it.name == source.name })
        }
    }

    @Test fun lookupSurvivesTheOuterPlannerWithBothAdaptiveSettings() {
        val task = "Show open issues in the repository"
        for (adaptive in listOf(true, false)) {
            val operations = GitHubTool().operations()
            val catalog = ProgressiveToolCatalog(operations.map { it.definition }, task, githubMode = true)
            val resolved = (operations + catalog).map { tool -> ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name, progressiveCatalog = catalog.takeIf { tool === catalog }) }
            val turn = ConversationTurn(MessageV2(content = task, platformType = null), null, true)
            val plan = planAuthorizedToolContext(listOf(turn), "Inspect the selected repository.", resolved, TokenBudgetSettings(contextTokens = 8192, outputTokens = 1024, adaptiveToolCatalog = adaptive), externalTools = true, githubMode = true)
            assertEquals(setOf("github_issue", ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
            assertEquals(resolved.map { it.tool.definition.name }.toSet(), plan.authorizedTools.map { it.name }.toSet())
        }
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
            assertEquals(setOf("github_context_inspect", "github_pr_inspect", ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
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

    @Test fun healthAdmitsRecentFailureReadsThroughBothCatalogAndContextBudgetPlanning() {
        val task = GitHubQuickWorkflow.defaults.single { it.id == "status" }.prompt
        assertEquals(setOf("github_context", "github_pr", "github_ci"), GitHubOperationTool.taskTools(task))
        for (adaptive in listOf(true, false)) {
            val operations = GitHubTool().operations()
            val catalog = ProgressiveToolCatalog(operations.map { it.definition }, task, githubMode = true)
            val resolved = (operations + catalog).map { tool -> ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name, progressiveCatalog = catalog.takeIf { tool === catalog }) }
            val turn = ConversationTurn(MessageV2(content = task, platformType = null), null, true)
            val plan = planAuthorizedToolContext(listOf(turn), "Inspect the selected repository health.", resolved, TokenBudgetSettings(contextTokens = 8192, outputTokens = 1024, adaptiveToolCatalog = adaptive), externalTools = true, githubMode = true)
            assertEquals(setOf("github_context_inspect", "github_pr_inspect", "github_ci", ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
            assertEquals(resolved.map { it.tool.definition.name }.toSet(), plan.authorizedTools.map { it.name }.toSet())
            assertFalse(plan.tools.any { it.name in setOf("github_write", "github_commit", "github_stage_patch") })
        }
    }

    @Test fun optionalRoundSchemasCannotEvictRequiredHealthCiReads() {
        val task = GitHubQuickWorkflow.defaults.single { it.id == "status" }.prompt
        val required = definitions.filter { GitHubOperationTool.operationName(it.name) in setOf("github_context", "github_pr", "github_ci") }
        val discovery = ProgressiveToolCatalog(definitions, task, githubMode = true).definition
        val requiredCost = (required + discovery).sumOf { providerSchemaTokens(it, true) }.toInt()
        val optional = AgentToolDefinition("large_optional", "Optional schema ".repeat(1000), buildJsonObject {})
        val promptCeiling = RequestAdmission.maximumEstimatedPrompt(16_384L - 1024).toInt()
        val limits = AgentRunLimits(githubTask = true, githubMode = true, githubTaskRequest = task, contextTokens = 16_384, initialContextTokens = promptCeiling - requiredCost, finalResponseReserveTokens = 1024)
        val selected = fitProviderToolSchemas(listOf(optional) + required + discovery, limits, 0, 0)
        assertEquals((required + discovery).map { it.name }.toSet(), selected.map { it.name }.toSet())
        assertEquals(required.filterNot { GitHubOperationTool.operationName(it.name) == "github_ci" }.map { it.name }.toSet(), fitProviderToolSchemas(required.filterNot { GitHubOperationTool.operationName(it.name) == "github_ci" }, limits, 0, 0).map { it.name }.toSet())
    }

    @Test fun insufficientHealthSchemaRoomFailsWithTheMeasuredAllowance() {
        val task = GitHubQuickWorkflow.defaults.single { it.id == "status" }.prompt
        val required = definitions.filter { GitHubOperationTool.operationName(it.name) in setOf("github_context", "github_pr", "github_ci") }
        val cost = required.sumOf { providerSchemaTokens(it, true) }
        val promptCeiling = RequestAdmission.maximumEstimatedPrompt(16_384L - 1024).toInt()
        val limits = AgentRunLimits(githubTask = true, githubMode = true, githubTaskRequest = task, contextTokens = 16_384, initialContextTokens = promptCeiling - cost.toInt() + 1, finalResponseReserveTokens = 1024)
        val error = assertThrows(GitHubToolContextBudgetException::class.java) { fitProviderToolSchemas(required, limits, 0, 0) }
        assertEquals(cost, error.requiredSchemaTokens)
        assertEquals(cost - 1, error.availableSchemaTokens)
        assertEquals(JsonPrimitive(false), error.providerFailure().failureDetails?.get("dispatched"))
    }
}
