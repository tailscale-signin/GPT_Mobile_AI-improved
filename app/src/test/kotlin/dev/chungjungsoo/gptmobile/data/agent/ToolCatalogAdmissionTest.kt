package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.context.ContextBudgetService
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.network.wireToolNames
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogAdmissionTest {
    @Test fun recoveryReaderIsAdmittedBeforeOptionalSchemasOnEveryGithubRound() {
        val recovery = AgentToolDefinition("read_recovery_context", "Read retained task receipts", buildJsonObject { put("type", "object") })
        val context = AgentToolDefinition("github_context", "Pinned status", buildJsonObject { put("type", "object") })
        val discovery = ProgressiveToolCatalog(emptyList(), "resume task").definition
        val optional = AgentToolDefinition("optional_reader", "Optional source reader ".repeat(500), buildJsonObject { put("type", "object") })
        val available = listOf(recovery, context, discovery).sumOf { providerSchemaTokens(it, true).toInt() }
        val selected = fitProviderToolSchemas(
            listOf(optional, context, discovery, recovery),
            AgentRunLimits(githubMode = true, contextTokens = 4096, initialContextTokens = 4096 - 1024 - 256 - available, finalResponseReserveTokens = 1024),
            0,
            0
        )
        assertEquals(setOf(context.name, discovery.name, recovery.name), selected.map { it.name }.toSet())
    }

    @Test fun savedRecoveryReaderIsRequiredWithoutTreatingSavedInstructionsAsNewUserAuthority() {
        val recovery = object : AgentTool {
            override val definition = AgentToolDefinition("read_recovery_context", "Read this task's retained evidence", buildJsonObject { put("type", "object") })
            override suspend fun execute(callId: String, arguments: JsonObject) = error("Planning does not execute tools")
        }
        val resolved = listOf(ResolvedAgentTool(recovery, null, "Recovery", recovery.definition.name, recovery.definition.name))
        val current = ConversationTurn(MessageV2(content = "Resume the branch review", platformType = null), null, true)
        val plan = planAuthorizedToolContext(listOf(current), "Continue saved work", resolved, TokenBudgetSettings(contextTokens = 4096, outputTokens = 1024), true, githubMode = true, requestedTask = current.userMessage.content)
        assertTrue(plan.tools.any { it.name == recovery.definition.name })
        assertEquals(listOf(recovery.definition.name), plan.tools.map { it.name }.filter { it != ProgressiveToolCatalog.NAME })
    }

    @Test fun discoveryRemainsAvailableWhenAnOptionalSchemaWouldConsumeItsBudget() {
        val discovery = ProgressiveToolCatalog(emptyList(), "find tools").definition
        val large = AgentToolDefinition("optional_reader", "Read source ranges ".repeat(100), buildJsonObject {})
        val available = maxOf(ProgressiveToolCatalog.schemaTokens(discovery), ProgressiveToolCatalog.schemaTokens(large))
        val limits = AgentRunLimits(contextTokens = 4096, initialContextTokens = 4096 - 1024 - 256 - available, finalResponseReserveTokens = 1024)
        val selected = fitProviderToolSchemas(listOf(large, discovery), limits, 0, 0)
        assertEquals(listOf(discovery), selected)
    }

    @Test fun warmDiscoveryAlsoRespectsTheRemainingRoundBudgetAndPreservesPriority() {
        val github = AgentToolDefinition("github_context", "GitHub status", buildJsonObject {})
        val large = AgentToolDefinition("github_read", "Read source ranges ".repeat(700), buildJsonObject {})
        val definitions = listOf(github, large)
        val selected = fitProviderToolSchemas(definitions, AgentRunLimits(contextTokens = 2048, initialContextTokens = 900, finalResponseReserveTokens = 1024), 100, 50)
        assertEquals(listOf(github), selected)
        assertTrue(fitProviderToolSchemas(definitions, AgentRunLimits(contextTokens = 2048, initialContextTokens = 1800, finalResponseReserveTokens = 1024), 100, 50).isEmpty())
    }

    @Test fun existingResolverCatalogControlsPlanningAndNeverChargesTheHiddenLegacySchema() {
        val github = GitHubTool()
        val backends = listOf(github) + github.operations()
        val catalog = ProgressiveToolCatalog(backends.map { it.definition }, "check repo status")
        val resolved = (backends + catalog).map { tool ->
            ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name, progressiveCatalog = catalog.takeIf { tool === catalog })
        }
        val turns = listOf(ConversationTurn(MessageV2(content = "check repo status", platformType = null), null, true))
        val blocked = assertThrows(GitHubToolContextBudgetException::class.java) {
            planAuthorizedToolContext(turns, "Use current schemas.", resolved, TokenBudgetSettings(contextTokens = 2048, outputTokens = 1024), true)
        }
        val failure = blocked.providerFailure()
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", failure.errorCode)
        assertEquals(JsonPrimitive(2048), failure.failureDetails?.get("admission_capacity_tokens"))
        assertEquals(JsonPrimitive(1024), failure.failureDetails?.get("admission_output_tokens"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", ProfileFailureRecord.classify("GITHUB_REQUIRED_TOOL_CONTEXT_BUDGET: historical error").category)
        val plan = planAuthorizedToolContext(turns, "Use current schemas.", resolved, TokenBudgetSettings(contextTokens = 8192, outputTokens = 1024), true)
        assertEquals(setOf("github_context", "github_pr", ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
        assertFalse(plan.tools.any { !it.modelVisible })
        assertEquals(resolved.map { it.modelToolName }.toSet(), plan.authorizedTools.map { it.name }.toSet())
        val expected = resolved.map { it.tool.definition }.filter { it.name in setOf("github_context", "github_pr", ProgressiveToolCatalog.NAME) }
        assertEquals(ContextBudgetService.breakdown(turns, "Use current schemas.", expected).total, plan.promptTokens)
        assertTrue(plan.promptTokens < ContextBudgetService.breakdown(turns, "Use current schemas.", backends.map { it.definition }).total / 2)
        val prompt = liveToolSystemPrompt(null, resolved.map { it.modelToolName }, deferCapabilities = true)
        assertFalse(prompt.contains("Live mobile tool context"))
        assertFalse(prompt.contains("github_stage_patch"))
        assertTrue(prompt.contains("exact request"))
    }

    @Test fun engineeringGoalFitsSmallContextWithoutPreloadingEveryGitHubSchema() {
        val github = GitHubTool()
        val operations = github.operations()
        val task = "Inspect GitHub issues, repair failures and verify PR checks"
        val catalog = ProgressiveToolCatalog(operations.map { it.definition }, task, githubMode = true)
        val resolved = (operations + catalog).map { tool ->
            ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name,
                progressiveCatalog = catalog.takeIf { tool === catalog })
        }
        val turn = ConversationTurn(MessageV2(content = task, platformType = null), null, true)
        val plan = planAuthorizedToolContext(listOf(turn), "Use GitHub tools.", resolved,
            TokenBudgetSettings(contextTokens = 5632, outputTokens = 1024), true,
            githubMode = true, requestedTask = task)
        assertTrue(plan.tools.any { it.name == "github_context" })
        assertTrue(plan.tools.any { it.name == ProgressiveToolCatalog.NAME })
        assertTrue(plan.tools.count { ProgressiveToolCatalog.isGitHubTool(it) } < operations.size)
        assertEquals(operations.map { it.definition.name }.toSet(),
            plan.authorizedTools.map { it.name }.toSet() - ProgressiveToolCatalog.NAME)
    }

    @Test fun providerRoundDefersLargeOptionalGithubSchema() {
        val recovery = AgentToolDefinition("read_recovery_context", "Recover task receipts", buildJsonObject {})
        val context = AgentToolDefinition("github_context", "Inspect repository", buildJsonObject {})
        val large = AgentToolDefinition("github_write", "Write staged changes ".repeat(500), buildJsonObject {})
        val discovery = ProgressiveToolCatalog(emptyList(), "discover").definition
        val limits = AgentRunLimits(githubMode = true, contextTokens = 4096, initialContextTokens = 2000,
            finalResponseReserveTokens = 1024)
        val selected = fitProviderToolSchemas(listOf(context, large, recovery, discovery), limits, 0, 0)
        assertTrue(selected.contains(context))
        assertTrue(selected.contains(recovery))
        assertTrue(selected.contains(discovery))
        assertFalse(selected.contains(large))
    }

    @Test fun defaultReplyReserveYieldsToRequiredSchemasWhileExplicitReserveStaysPinned() {
        val github = GitHubTool().operations().filter { it.definition.name in setOf("github_context", "github_pr") }
        val tools = github.map { ResolvedAgentTool(it, null, "GitHub", it.definition.name, it.definition.name) }
        val turn = ConversationTurn(MessageV2(content = "check repo status", platformType = null), null, true)
        val settings = TokenBudgetSettings(contextTokens = 8192, outputTokens = 4096)
        val input = ContextBudgetService.breakdown(listOf(turn), "Read GitHub status.", emptyList()).total
        val schema = github.sumOf { providerSchemaTokens(it.definition, true) }
        val nearLimit = settings.copy(contextTokens = input + schema.toInt() + 2048)
        val failure = assertThrows(GitHubToolContextBudgetException::class.java) {
            planAuthorizedToolContext(listOf(turn), "Read GitHub status.", tools, nearLimit, true, githubMode = true)
        }
        assertEquals(JsonPrimitive(4096), failure.providerFailure().failureDetails?.get("admission_output_tokens"))
        val recovered = planAuthorizedToolContext(listOf(turn), "Read GitHub status.", tools, nearLimit, true, githubMode = true, allowOutputReduction = true)
        assertTrue(requireNotNull(recovered.outputTokens) in 512 until settings.outputTokens)
        assertTrue(recovered.tools.containsAll(github.map { it.definition }))
    }

    @Test fun coldGithubDiscoveryReachesTheNextProviderRequestAndExecutesOnce() = runTest {
        exerciseColdDiscovery(context = 16384, expectAvailable = true)
    }

    @Test fun coldGithubDiscoveryCannotOverrunRemainingContextOrPromiseAnUnavailableTool() = runTest {
        exerciseColdDiscovery(context = 4096, expectAvailable = false)
    }

    @Test fun discoveryReceiptAccountsForItsOwnReplayBeforeAdvertisingTheNextRequest() = runTest {
        exerciseColdDiscovery(context = 6000, expectAvailable = false)
    }

    private suspend fun exerciseColdDiscovery(context: Int, expectAvailable: Boolean) {
        var executions = 0
        var fetched = false
        val github = object : AgentTool {
            override val definition = AgentToolDefinition("github__github_api", "GitHub action details ".repeat(600), buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("Repository inspected."), false)
            }
        }
        val catalog = ProgressiveToolCatalog(emptyList(), "what tools do you have", acquireCold = { _, _ ->
            fetched = true
            listOf(github.definition)
        })
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (++round) {
                    1 -> {
                        assertEquals(listOf(ProgressiveToolCatalog.NAME), tools.map { it.name })
                        emit(ProviderEvent.ToolCall("discover", ProgressiveToolCatalog.NAME, buildJsonObject { put("query", "github") }))
                    }
                    2 -> {
                        assertEquals(expectAvailable, tools.any { it.name == github.definition.name })
                        val payload = (exchanges.last().results.single().content as ToolResultContent.Json).value.jsonObject
                        val available = payload["available_next_round"] as JsonArray
                        assertEquals(expectAvailable, available.any { it.jsonObject["name"] == JsonPrimitive(github.definition.name) })
                        if (expectAvailable) {
                            emit(ProviderEvent.ToolCall("status", github.definition.name, buildJsonObject { put("action", "repo_status") }))
                        } else {
                            assertTrue(payload["context_budget_excluded"].toString().contains(github.definition.name))
                            emit(ProviderEvent.TextDelta("This request has insufficient schema space."))
                        }
                    }
                    else -> emit(ProviderEvent.TextDelta("Repository status checked."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(maxRounds = 4, contextTokens = context, initialContextTokens = 500, finalResponseReserveTokens = 1024)).run(
            session.withToolCatalog(catalog),
            listOf(catalog),
            dynamicTools = { if (fetched) listOf(github) else emptyList() },
            selectDefinitions = catalog::selected
        ).toList()
        assertEquals(if (expectAvailable) 1 else 0, executions)
        assertFalse(events.toString(), events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun wireInventoryUsesActualProviderNamesIncludingGroupedGeminiDeclarations() {
        for (payload in listOf(
            """{"tools":[{"type":"function","function":{"name":"github_context"}},{"type":"function","function":{"name":"github_read"}}]}""",
            """{"tools":[{"type":"function","name":"github_context"},{"type":"function","name":"github_read"}]}""",
            """{"tools":[{"name":"github_context"},{"name":"github_read"}]}""",
            """{"tools":[{"functionDeclarations":[{"name":"github_context"},{"name":"github_read"}]}]}"""
        )) {
            assertEquals(listOf("github_context", "github_read"), wireToolNames(Json.parseToJsonElement(payload).jsonObject))
        }
        assertTrue(wireToolNames(buildJsonObject {}).isEmpty())
    }
}
