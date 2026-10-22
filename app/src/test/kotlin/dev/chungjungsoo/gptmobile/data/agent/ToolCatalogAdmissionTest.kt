package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
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
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogAdmissionTest {
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
        val plan = planAuthorizedToolContext(turns, "Use current schemas.", resolved, TokenBudgetSettings(contextTokens = 4096, outputTokens = 1024), true)
        assertEquals(setOf("github_context", "github_search", "github_read", ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
        assertFalse(plan.tools.any { !it.modelVisible })
        assertEquals(resolved.map { it.modelToolName }.toSet(), plan.authorizedTools.map { it.name }.toSet())
        assertTrue(plan.promptTokens < 1500)
        val prompt = liveToolSystemPrompt(null, resolved.map { it.modelToolName }, deferCapabilities = true)
        assertFalse(prompt.contains("Live mobile tool context"))
        assertFalse(prompt.contains("github_stage_patch"))
        assertTrue(prompt.contains("exact request"))
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
