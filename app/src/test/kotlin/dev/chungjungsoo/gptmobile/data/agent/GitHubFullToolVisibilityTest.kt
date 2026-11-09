package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubProviderPolicy
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.mcpToolDefinition
import dev.chungjungsoo.gptmobile.data.context.ConversationTurn
import dev.chungjungsoo.gptmobile.data.context.TokenBudgetSettings
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFullToolVisibilityTest {
    private val githubSchemas get() = GitHubOperationTool.names.map { GitHubOperationTool.definition(it) } + schema("repository_graph")

    @Test fun githubModePinsEveryAuthorizedFamilyAcrossDiscoveryAndNewContinuationCatalogs() = runTest {
        val github = githubSchemas
        val hidden = schema("github__legacy").copy(modelVisible = false)
        val optional = (1..80).map { schema("optional_$it") }
        val definitions = github + hidden + optional
        repeat(2) { continuation ->
            val catalog = requireNotNull(ProgressiveToolCatalog.create(definitions, if (continuation == 0) "hello" else "continue", setOf(github.first().name), githubMode = true))
            val allowed = definitions + catalog.definition
            assertEquals(github.toSet(), catalog.selected(allowed).filter { ProgressiveToolCatalog.isGitHubTool(it) }.toSet())
            assertFalse(catalog.selected(allowed).any { !it.modelVisible })
            repeat(3) {
                catalog.execute("discover-$it", buildJsonObject { put("query", "optional_80") })
                assertTrue(catalog.selected(allowed).containsAll(github))
            }
            val denied = github.last()
            assertFalse(catalog.selected(allowed - denied).any { it.name == denied.name })
            assertTrue("An explicit verification-only pass must stay tool-free", catalog.selected(emptyList()).isEmpty())
        }
    }

    @Test fun ordinaryChatsKeepProgressiveSelection() {
        val definitions = githubSchemas
        val catalog = ProgressiveToolCatalog(definitions, "check repo status")
        val selected = catalog.selected(definitions + catalog.definition)
        assertTrue(selected.size < definitions.size)
        assertFalse(selected.any { GitHubOperationTool.operationName(it.name) == "github_write" })
    }

    @Test fun officialMcpWithArbitrarySanitizedAliasStaysVisibleThroughCatalogContextAndRunnerRounds() = runTest {
        val connection = ToolConnection("official", "Work", "Work alias with spaces and a very long name", ToolConnectionType.MCP, "https://api.githubcopilot.com/mcp", "BEARER", "secret", null)
        val definition = GitHubProviderPolicy.definition(connection, mcpToolDefinition(connection.alias, Tool("get_file_contents", inputSchema = ToolSchema())))
        assertFalse(definition.name.contains("github", ignoreCase = true))
        assertTrue(ProgressiveToolCatalog.isGitHubTool(definition))
        val github = tool(definition)
        val catalog = ProgressiveToolCatalog(listOf(definition) + (1..40).map { schema("optional_$it") }, "hello", githubMode = true)
        val resolved = listOf(github, catalog).map { current ->
            ResolvedAgentTool(current, connection.connectionUid, connection.name, current.definition.name, current.definition.name, progressiveCatalog = catalog.takeIf { current === catalog })
        }
        val plan = planAuthorizedToolContext(listOf(ConversationTurn(MessageV2(content = "continue", platformType = null), null, true)), "Use the authorized catalog.", resolved, TokenBudgetSettings(contextTokens = 4096, outputTokens = 512), true, githubMode = true)
        assertTrue(plan.tools.contains(definition))
        assertTrue(catalog.selected(listOf(definition, catalog.definition)).contains(definition))
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(tools.contains(definition))
                if (++rounds == 1) emit(ProviderEvent.ToolCall("read", definition.name, buildJsonObject {})) else emit(ProviderEvent.TextDelta("The authorized read completed."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubMode = true, maxRounds = 3)).run(session, listOf(github), selectDefinitions = { emptyList() }).toList()
        assertEquals(2, rounds)
        assertTrue(events.toString(), events.providerFailures().isEmpty())
        val tiny = AgentRunLimits(githubMode = true, contextTokens = 512, initialContextTokens = 500, finalResponseReserveTokens = 128)
        assertTrue(runCatching { fitProviderToolSchemas(listOf(definition), tiny, 0, 0) }.exceptionOrNull() is GitHubToolContextBudgetException)
    }

    @Test fun githubModePlansAllFamiliesEvenForGreetingAndFailsExplicitlyWhenTheyCannotFit() {
        val github = githubSchemas
        val catalog = ProgressiveToolCatalog(github, "hello", githubMode = true)
        val resolved = (github.map { tool(it) } + catalog).map { current ->
            ResolvedAgentTool(current, null, "GitHub", current.definition.name, current.definition.name, progressiveCatalog = catalog.takeIf { current === catalog })
        }
        val turns = listOf(ConversationTurn(MessageV2(content = "hello", platformType = null), null, true))
        val plan = planAuthorizedToolContext(turns, "Use current schemas.", resolved, TokenBudgetSettings(contextTokens = 65536, outputTokens = 1024), true, githubMode = false)
        assertTrue(plan.tools.containsAll(github))
        assertTrue(plan.toolOmissions.keys.none { name -> github.any { it.name == name } })
        val failure = runCatching {
            planAuthorizedToolContext(turns, "Use current schemas.", resolved, TokenBudgetSettings(contextTokens = 512, outputTokens = 128), true, githubMode = false)
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is GitHubToolContextBudgetException)
        assertTrue(failure?.message.orEmpty().startsWith("GITHUB_TOOL_CONTEXT_BUDGET:"))
    }

    @Test fun githubSchemasHavePriorityOverOptionalSchemasAndDiscoveryInEveryRound() {
        val github = listOf(schema("github_read"), schema("github_write"))
        val optional = AgentToolDefinition("optional", "Optional details ".repeat(500), buildJsonObject {})
        val discovery = ProgressiveToolCatalog(emptyList(), "tools").definition
        val required = github.sumOf { providerSchemaTokens(it, true) }.toInt()
        val limits = AgentRunLimits(githubMode = true, contextTokens = 4096, initialContextTokens = 4096 - 1024 - 256 - required, finalResponseReserveTokens = 1024)
        assertEquals(github, fitProviderToolSchemas(listOf(optional, discovery) + github, limits, 0, 0))
        val failure = runCatching { fitProviderToolSchemas(github, limits, 0, 1) }.exceptionOrNull()
        assertTrue(failure is GitHubToolContextBudgetException)
    }

    @Test fun discoveryStallStopsOnlyDiscoveryAndTheNextGitHubActionStillExecutes() = runTest {
        var executions = 0
        val github = listOf("github_context", "github_read", "github_write").map { name ->
            tool(schema(name)) { executions++ }
        }
        val catalog = ProgressiveToolCatalog(github.map { it.definition }, "continue", githubMode = true)
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue("All GitHub families must be attached at round ${round + 1}", tools.containsAll(github.map { it.definition }))
                when (++round) {
                    1, 2 -> emit(ProviderEvent.ToolCall("discover-$round", ProgressiveToolCatalog.NAME, buildJsonObject {}))
                    3 -> {
                        assertFalse(tools.any { it.name == ProgressiveToolCatalog.NAME })
                        emit(ProviderEvent.ToolCall("read", "github_read", buildJsonObject {}))
                    }
                    else -> emit(ProviderEvent.TextDelta("The requested repository read completed."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubMode = true, githubTask = true, maxRounds = 5)).run(session.withToolCatalog(catalog), github + catalog, selectDefinitions = catalog::selected).toList()
        assertEquals(4, round)
        assertEquals(1, executions)
        assertFalse(events.toString(), events.providerFailures().isNotEmpty())
    }

    @Test fun dynamicallyAuthorizedGithubSchemaBypassesTheProgressiveSixThousandTokenWindow() = runTest {
        var fetched = false
        var executions = 0
        val github = tool(AgentToolDefinition("github_read__authorized", "Read details ".repeat(1800), buildJsonObject {})) { executions++ }
        assertTrue(ProgressiveToolCatalog.schemaTokens(github.definition) > ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE)
        val catalog = ProgressiveToolCatalog(emptyList(), "hello", acquireCold = { _, _ ->
            fetched = true
            listOf(github.definition)
        }, githubMode = true)
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (++round) {
                    1 -> emit(ProviderEvent.ToolCall("discover", ProgressiveToolCatalog.NAME, buildJsonObject {}))
                    2 -> {
                        assertTrue(tools.contains(github.definition))
                        emit(ProviderEvent.ToolCall("read", github.definition.name, buildJsonObject {}))
                    }
                    else -> emit(ProviderEvent.TextDelta("The authorized read completed."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubMode = true, maxRounds = 4, contextTokens = 65536, initialContextTokens = 1024, finalResponseReserveTokens = 1024)).run(
            session.withToolCatalog(catalog),
            listOf(catalog),
            dynamicTools = { if (fetched) listOf(github) else emptyList() },
            selectDefinitions = catalog::selected
        ).toList()
        assertEquals(1, executions)
        assertTrue(events.toString(), events.providerFailures().isEmpty())
    }

    @Test fun failureCircuitPausesExecutionWithoutHidingAuthorizedGithubSchemas() = runTest {
        var failedExecutions = 0
        var successfulExecutions = 0
        val unreliable = object : AgentTool {
            override val definition = schema("connection__github__lookup")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                failedExecutions++
                return AgentToolResult(callId, ToolResultContent.Text("GitHub is cooling down; wait for its retry deadline."), true, errorCode = "RATE_LIMITED")
            }
        }
        val available = tool(schema("github_read")) { successfulExecutions++ }
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(tools.containsAll(listOf(unreliable.definition, available.definition)))
                when (++round) {
                    1, 2 -> emit(ProviderEvent.ToolCall("failed-$round", unreliable.definition.name, buildJsonObject {}))
                    3 -> {
                        val paused = exchanges.last().results.single()
                        assertEquals("TOOL_EXECUTION_PAUSED", paused.errorCode)
                        assertFalse(paused.dispatched)
                        emit(ProviderEvent.ToolCall("read", available.definition.name, buildJsonObject {}))
                    }
                    else -> emit(ProviderEvent.TextDelta("The available repository read completed; the other connection is cooling down."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubMode = true, maxRounds = 5)).run(session, listOf(unreliable, available)).toList()
        assertEquals(1, failedExecutions)
        assertEquals(1, successfulExecutions)
        assertTrue(events.toString(), events.providerFailures().isEmpty())
    }

    @Test fun revokedGithubAuthorizationStillRemovesTheForbiddenSchema() = runTest {
        val revoked = object : AgentTool {
            override val definition = schema("github_write")
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("Permission revoked"), true, errorCode = "PERMISSION_REVOKED", dispatched = false)
        }
        val available = tool(schema("github_read"))
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (++round == 1) {
                    assertTrue(tools.contains(revoked.definition))
                    emit(ProviderEvent.ToolCall("revoked", revoked.definition.name, buildJsonObject {}))
                } else {
                    assertFalse(tools.contains(revoked.definition))
                    assertTrue(tools.contains(available.definition))
                    emit(ProviderEvent.TextDelta("Write permission must be restored before this operation can proceed."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubMode = true, maxRounds = 4)).run(session, listOf(revoked, available)).toList()
        assertEquals(2, round)
        assertTrue(events.toString(), events.providerFailures().isEmpty())
    }

    @Test fun runnerDoesNotSilentlyDropGithubSchemasForAContextOrProviderContractFailure() = runTest {
        val github = tool(AgentToolDefinition("github_read", "Schema details ".repeat(500), buildJsonObject {}))
        var requests = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                requests++
                assertTrue(tools.contains(github.definition))
                throw ToolDefinitionsRejectedException("This model does not support tool definitions")
            }
        }
        val blocked = AgentRunner(AgentRunLimits(githubMode = true, contextTokens = 512, finalResponseReserveTokens = 128)).run(session, listOf(github)).toList().providerFailures().single()
        assertEquals(0, requests)
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", blocked.errorCode)
        assertEquals(JsonPrimitive(false), blocked.failureDetails?.get("dispatched"))
        val unsupported = AgentRunner(AgentRunLimits(githubMode = true)).run(session, listOf(github), selectDefinitions = { emptyList() }).toList().providerFailures().single()
        assertEquals(1, requests)
        assertEquals("GITHUB_TOOL_CONTRACT_UNSUPPORTED", unsupported.errorCode)
    }

    private fun schema(name: String) = AgentToolDefinition(name, "Authorized operation", buildJsonObject {})

    private fun tool(definition: AgentToolDefinition, execute: () -> Unit = {}) = object : AgentTool {
        override val definition = definition
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            execute()
            return AgentToolResult(callId, ToolResultContent.Text("Operation completed"), false)
        }
    }

    private fun List<AgentRunEvent>.providerFailures() = filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>()
}
