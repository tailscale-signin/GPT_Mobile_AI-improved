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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFullToolVisibilityTest {
    private val githubSchemas get() = GitHubOperationTool.names.map { GitHubOperationTool.definition(it) } + schema("repository_graph")

    @Test fun githubModeRetainsEveryAuthorizedFamilyAcrossBoundedContinuationCatalogs() = runTest {
        val github = githubSchemas
        val hidden = schema("github__legacy").copy(modelVisible = false)
        val optional = (1..80).map { schema("optional_$it") }
        val definitions = github + hidden + optional
        repeat(2) { continuation ->
            val catalog = requireNotNull(ProgressiveToolCatalog.create(definitions, if (continuation == 0) "hello" else "continue", setOf(github.first().name), githubMode = true))
            val allowed = definitions + catalog.definition
            val initial = catalog.selected(allowed)
            assertTrue(initial.any { GitHubOperationTool.operationName(it.name) == "github_context" })
            assertTrue(initial.filter { ProgressiveToolCatalog.isGitHubTool(it) }.size < github.size)
            assertFalse(catalog.selected(allowed).any { !it.modelVisible })
            val deferred = github.last()
            catalog.execute("discover-github", buildJsonObject { put("names", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(deferred.name)))) })
            assertTrue(catalog.selected(allowed).contains(deferred))
            assertFalse(catalog.execute("discover-optional", buildJsonObject { put("query", "optional_80") }).isError)
            val shifted = catalog.selected(allowed)
            assertTrue(shifted.contains(optional.last()))
            assertTrue(shifted.count { it.name != ProgressiveToolCatalog.NAME } <= ProgressiveToolCatalog.INITIAL_TOOLS)
            assertFalse("The bounded window may evict a prior schema without revoking it", shifted.contains(deferred))
            val inventory = mutableSetOf<String>()
            var arguments = JsonObject(emptyMap())
            var pages = 0
            while (true) {
                check(++pages <= 32) { "Authorized inventory did not complete" }
                val result = catalog.execute("inventory-$pages", arguments)
                assertFalse(result.isError)
                assertFalse(result.dispatched)
                val payload = (result.content as ToolResultContent.Json).value.jsonObject
                inventory += (payload.getValue("authorized_inventory") as JsonArray).map { it.jsonObject.getValue("name").jsonPrimitive.content }
                val next = payload["continuation_call"] as? JsonObject ?: break
                arguments = next.getValue("arguments").jsonObject
            }
            assertEquals(definitions.filter { it.modelVisible }.map { it.name }.toSet(), inventory)
            val rediscovered = catalog.execute("rediscover-github", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(deferred.name)))) })
            assertFalse(rediscovered.isError)
            assertFalse(rediscovered.dispatched)
            assertTrue(catalog.selected(allowed).contains(deferred))
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

    @Test fun officialMcpWithArbitrarySanitizedAliasRemainsDiscoverableThroughRunnerRounds() = runTest {
        val connection = ToolConnection("official", "Work", "Work alias with spaces and a very long name", ToolConnectionType.MCP, "https://api.githubcopilot.com/mcp", "BEARER", "secret", null)
        val definition = GitHubProviderPolicy.definition(connection, mcpToolDefinition(connection.alias, Tool("get_file_contents", inputSchema = ToolSchema())))
        assertFalse(definition.name.contains("github", ignoreCase = true))
        assertTrue(ProgressiveToolCatalog.isGitHubTool(definition))
        val github = tool(definition)
        val catalog = ProgressiveToolCatalog(listOf(definition) + (1..40).map { schema("optional_$it") }, "Inspect the repository", githubMode = true)
        assertFalse(catalog.execute("discover", buildJsonObject { put("names", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(definition.name)))) }).isError)
        val resolved = listOf(github, catalog).map { current ->
            ResolvedAgentTool(current, connection.connectionUid, connection.name, current.definition.name, current.definition.name, progressiveCatalog = catalog.takeIf { current === catalog })
        }
        val plan = planAuthorizedToolContext(listOf(ConversationTurn(MessageV2(content = "Inspect the repository", platformType = null), null, true)), "Use the authorized catalog.", resolved, TokenBudgetSettings(contextTokens = 4096, outputTokens = 512), true, githubMode = true)
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
        val events = AgentRunner(AgentRunLimits(githubMode = true, maxRounds = 3)).run(session, listOf(github)).toList()
        assertEquals(2, rounds)
        assertTrue(events.toString(), events.providerFailures().isEmpty())
        val tiny = AgentRunLimits(githubMode = true, contextTokens = 512, initialContextTokens = 500, finalResponseReserveTokens = 128)
        val oversizedRecovery = definition.copy(name = "read_recovery_context", description = "Recovery schema ".repeat(500))
        assertTrue(runCatching { fitProviderToolSchemas(listOf(oversizedRecovery), tiny, 0, 0) }.exceptionOrNull() is GitHubToolContextBudgetException)
    }

    @Test fun githubModePlansOnlyImmediateSchemasAndFailsWhenARequiredSchemaCannotFit() {
        val github = githubSchemas
        val task = "Inspect the repository status"
        val catalog = ProgressiveToolCatalog(github, task, githubMode = true)
        val resolved = (github.map { tool(it) } + catalog).map { current ->
            ResolvedAgentTool(current, null, "GitHub", current.definition.name, current.definition.name, progressiveCatalog = catalog.takeIf { current === catalog })
        }
        val turns = listOf(ConversationTurn(MessageV2(content = task, platformType = null), null, true))
        val plan = planAuthorizedToolContext(turns, "Use current schemas.", resolved, TokenBudgetSettings(contextTokens = 65536, outputTokens = 1024), true, githubMode = true)
        assertTrue(plan.tools.any { GitHubOperationTool.operationName(it.name) == "github_context" })
        assertTrue(plan.tools.size < github.size + 1)
        val oversized = schema("github_context").copy(description = "Required schema ".repeat(500))
        val requiredCatalog = ProgressiveToolCatalog(listOf(oversized), task, githubMode = true)
        val requiredResolved = listOf(tool(oversized), requiredCatalog).map { current ->
            ResolvedAgentTool(current, null, "GitHub", current.definition.name, current.definition.name, progressiveCatalog = requiredCatalog.takeIf { current === requiredCatalog })
        }
        val failure = runCatching {
            planAuthorizedToolContext(turns, "Use current schemas.", requiredResolved, TokenBudgetSettings(contextTokens = 512, outputTokens = 128), true, githubMode = true)
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is GitHubToolContextBudgetException)
        assertTrue(failure?.message.orEmpty().startsWith("GITHUB_TOOL_CONTEXT_BUDGET:"))
    }

    @Test fun fullCatalogAdmissionUsesOnlyTheFirstConnectionsCompactContextSchema() {
        val full = schema("github_context__first_inspect").copy(description = "Oversized required tool ".repeat(3000))
        val other = schema("github_context_inspect__first")
        val compact = schema("github_context_inspect__first_inspect")
        val task = "Continue"
        val turns = listOf(ConversationTurn(MessageV2(content = task, platformType = null), null, true))
        fun plan(definitions: List<AgentToolDefinition>) = planAuthorizedToolContext(
            turns,
            "Use the selected connection.",
            definitions.map { ResolvedAgentTool(tool(it), null, "GitHub", it.name, it.name) },
            TokenBudgetSettings(contextTokens = 5632, outputTokens = 512),
            externalTools = false,
            githubMode = true
        )
        val tools = plan(listOf(full, other, compact)).tools
        assertTrue(compact in tools)
        assertFalse(full in tools)
        val failure = runCatching { plan(listOf(full, other)) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is GitHubToolContextBudgetException)
    }

    @Test fun requiredRecoveryAndDiscoveryHavePriorityOverLargeOptionalSchemas() {
        val recovery = schema("read_recovery_context")
        val github = schema("github_read")
        val optional = AgentToolDefinition("optional", "Optional details ".repeat(500), buildJsonObject {})
        val discovery = ProgressiveToolCatalog(emptyList(), "tools").definition
        val limits = AgentRunLimits(githubMode = true, contextTokens = 4096, initialContextTokens = 2000, finalResponseReserveTokens = 1024)
        val selected = fitProviderToolSchemas(listOf(recovery, github, optional, discovery), limits, 0, 0)
        assertTrue(selected.contains(recovery))
        assertTrue(selected.contains(github))
        assertTrue(selected.contains(discovery))
        assertFalse(selected.contains(optional))
        val hugeRecovery = recovery.copy(description = "Recovery ".repeat(500))
        val failure = runCatching { fitProviderToolSchemas(listOf(hugeRecovery), limits.copy(contextTokens = 512), 0, 0) }.exceptionOrNull()
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
                when (++round) {
                    1 -> emit(ProviderEvent.ToolCall("discover-$round", ProgressiveToolCatalog.NAME, buildJsonObject { put("names", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("github_read")))) }))
                    2 -> {
                        assertTrue(tools.any { it.name == "github_read" })
                        emit(ProviderEvent.ToolCall("discover-$round", ProgressiveToolCatalog.NAME, buildJsonObject { put("names", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("github_read")))) }))
                    }
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

    @Test fun pausedDiscoveryCanExecuteAReadWhenOnlyPartOfTheCatalogFits() = runTest {
        val names = listOf("github_read", "github_search", "github_pr", "github_ci", "github_issue", "github_context")
        val operations = names.mapIndexed { index, name ->
            val cost = if (index == 0) 777 else 778
            val definition = schema(name)
            definition.copy(description = "x".repeat(cost * 3 - name.length - definition.inputSchema.toString().length))
        }
        // Preserve a 3,005-token schema window above the compact-profile boundary.
        // Compact native-operation staging has its own rotation and budget coverage.
        val limits = AgentRunLimits(githubTask = true, contextTokens = 8192, initialContextTokens = 4314, initialGitHubSchemaTokens = 0L, finalResponseReserveTokens = 617, maxRounds = 5)
        assertEquals(4667L, operations.sumOf { providerSchemaTokens(it, false) })
        val admitted = fitProviderToolSchemas(operations, limits, 0, 0, discoveryPaused = true)
        assertEquals(operations.take(3), admitted)
        assertTrue(admitted.sumOf { providerSchemaTokens(it, false) } <= 3005)
        assertTrue(runCatching { fitProviderToolSchemas(operations, limits, 0, 0) }.exceptionOrNull() is GitHubToolContextBudgetException)
        assertTrue(runCatching { fitProviderToolSchemas(operations, limits, 0, 3000, discoveryPaused = true) }.exceptionOrNull() is GitHubToolContextBudgetException)

        val directory = ProgressiveToolCatalog(emptyList(), "tools").definition
        val candidates = listOf(directory) + operations
        val oneOperation = 778
        assertTrue(providerSchemaTokens(directory, false) < oneOperation)
        assertEquals(listOf(directory), fitProviderToolSchemas(candidates, limits, 0, 0, oneOperation))
        assertEquals(operations.take(1), fitProviderToolSchemas(candidates, limits, 0, 0, oneOperation, discoveryPaused = true))
        val directoryOnly = providerSchemaTokens(directory, false).toInt()
        assertEquals(listOf(directory), fitProviderToolSchemas(candidates, limits, 0, 0, directoryOnly, discoveryPaused = true))

        var reads = 0
        val github = operations.map { definition ->
            tool(definition) {
                assertEquals("github_read", definition.name)
                reads++
            }
        }
        val discovery = object : AgentTool {
            override val definition = directory
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Json(buildJsonObject { put("catalog_unchanged", true) }),
                false,
                dispatched = false
            )
        }
        var rounds = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (++rounds) {
                    1, 2 -> emit(ProviderEvent.ToolCall("catalog-$rounds", discovery.definition.name, buildJsonObject { put("query", "github_read") }))
                    3 -> {
                        assertTrue(tools.any { it.name == "github_read" })
                        assertFalse(tools.any { it.name == discovery.definition.name })
                        assertTrue(tools.size < operations.size)
                        emit(ProviderEvent.ToolCall("read", "github_read", buildJsonObject {}))
                    }
                    else -> emit(ProviderEvent.TextDelta("Read complete."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(limits).run(provider, github + discovery).toList()
        assertTrue(events.toString(), events.providerFailures().isEmpty())
        assertEquals(1, reads)
        assertEquals(4, rounds)
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

    @Test fun runnerRejectsOversizedRecoveryAndReportsASelectedGithubContractFailure() = runTest {
        val github = tool(AgentToolDefinition("github_read", "Schema details ".repeat(500), buildJsonObject {}))
        var requests = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow<ProviderEvent> {
                requests++
                assertTrue(tools.contains(github.definition))
                throw ToolDefinitionsRejectedException("This model does not support tool definitions")
            }
        }
        val recovery = tool(AgentToolDefinition("read_recovery_context", "Schema details ".repeat(500), buildJsonObject {}))
        val blocked = AgentRunner(AgentRunLimits(githubMode = true, contextTokens = 1024, finalResponseReserveTokens = 128)).run(session, listOf(recovery)).toList().providerFailures().single()
        assertEquals(0, requests)
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", blocked.errorCode)
        assertEquals(JsonPrimitive(false), blocked.failureDetails?.get("dispatched"))
        val unsupported = AgentRunner(AgentRunLimits(githubMode = true)).run(session, listOf(github), selectDefinitions = { listOf(github.definition) }).toList().providerFailures().single()
        assertEquals(1, requests)
        assertEquals("GITHUB_TOOL_CONTRACT_UNSUPPORTED", unsupported.errorCode)
    }

    @Test fun automaticCatalogMissRecoveryRequiresTheBoundLocalCatalog() = runTest {
        for (local in listOf(false, true)) {
            var coldCalls = 0
            var fakeCalls = 0
            val catalog = ProgressiveToolCatalog(emptyList(), "Inspect the authorized tool catalog", acquireCold = { _, _ ->
                coldCalls++
                emptyList()
            })
            val original = if (local) catalog else tool(catalog.definition) { fakeCalls++ }
            val limits = AgentRunLimits(maxRounds = 4, maxToolCalls = 4, maxReplayTokens = 8192, maxReplayResultTokens = 4096)
            val bound = ToolExecutionBudget(limits).bind(original.withRunContext("catalog-identity-$local"))
            assertFalse(bound is ProgressiveToolCatalog)
            assertEquals(catalog.definition, bound.definition)
            val missing = ProviderEvent.ToolCall("missing-capability", "unregistered_fixture", buildJsonObject { put("url", "https://example.org/article") })
            val expectedArguments = buildJsonObject {
                put("query", missing.name)
                put("catalog_only", true)
            }
            var rounds = 0
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    assertEquals(listOf(bound.definition), tools)
                    if (++rounds == 1) {
                        emit(missing)
                    } else {
                        assertEquals(2, rounds)
                        val miss = exchanges.single { exchange -> exchange.calls.any { it.callId == missing.callId } }
                        assertEquals(missing.name, miss.calls.single().name)
                        assertEquals(missing.arguments, miss.calls.single().arguments)
                        val rejection = miss.results.single()
                        assertTrue(rejection.isError)
                        assertFalse(rejection.dispatched)
                        assertEquals("TOOL_NOT_AVAILABLE", rejection.errorCode)
                        assertEquals(JsonPrimitive(true), rejection.executionReceipt?.get("discovery_available"))
                        // Prove both actual misses are eligible before the runner's local-object gate.
                        val eligible = requireNotNull(CatalogMissRecovery.next(listOf(miss), tools, emptySet()))
                        assertEquals(ProgressiveToolCatalog.NAME, eligible.name)
                        assertEquals(expectedArguments, eligible.arguments)
                        val recovered = exchanges.flatMap { it.calls }.filter { CatalogMissRecovery.isRecovery(it.callId) }
                        assertEquals(if (local) 1 else 0, recovered.size)
                        if (local) {
                            val lookup = recovered.single()
                            assertEquals(eligible.callId, lookup.callId)
                            assertEquals(eligible.name, lookup.name)
                            assertEquals(expectedArguments, lookup.arguments)
                            val result = exchanges.flatMap { it.results }.single { it.callId == lookup.callId }
                            assertFalse(result.isError)
                            assertFalse(result.dispatched)
                        }
                        emit(ProviderEvent.TextDelta("The requested capability is unavailable."))
                    }
                    emit(ProviderEvent.Completed)
                }
            }
            val events = AgentRunner(limits).run(session, listOf(bound)).toList()
            assertEquals(2, rounds)
            assertEquals(0, fakeCalls)
            assertEquals(0, coldCalls)
            val started = events.filterIsInstance<AgentRunEvent.ToolStarted>()
            assertEquals(if (local) 1 else 0, started.size)
            assertTrue(started.all { it.call.name == ProgressiveToolCatalog.NAME && it.call.arguments == expectedArguments })
            assertTrue(events.toString(), events.providerFailures().isEmpty())
            assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        }
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
