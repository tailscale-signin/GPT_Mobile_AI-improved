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
    @Test fun verified5632TokenRouteKeepsRequiredSchemasAndTheSelectedOutput() {
        val budget = GitHubSchemaBudget(capacity = 5632, input = 3241, output = 512, safety = 256, toolResults = 704)
        val fitted = budget.fitInitialSchemas(required = 1096, allowOutputReduction = false)
        assertEquals(919L, budget.available)
        assertEquals(1096L, fitted.available)
        assertEquals(527, fitted.toolResults)
        assertEquals(512, fitted.output)
        assertEquals(budget, budget.fitInitialSchemas(900, false))
        assertThrows(GitHubToolContextBudgetException::class.java) { budget.fitInitialSchemas(1112, true) }
        val definition = AgentToolDefinition("github_context", "Read repository context", buildJsonObject { put("type", "object") })
        val plan = ContextBudgetService.plan(emptyList(), "", listOf(definition), TokenBudgetSettings(contextTokens = 5632, outputTokens = 512), fitted.toolResults)
        assertEquals(527 * 3, plan.toolResultBytes)
        assertEquals(listOf(definition), plan.tools)
    }

    @Test fun initialAdmissionBreakdownExcludesOldHistoryAndRetainsNoPromptText() {
        val recovery = object : AgentTool {
            override val definition = AgentToolDefinition("read_recovery_context", "Recovery contract ".repeat(1000), buildJsonObject { put("type", "object") })
            override suspend fun execute(callId: String, arguments: JsonObject) = error("Planning must not dispatch")
        }
        val tools = listOf(ResolvedAgentTool(recovery, null, "Recovery", recovery.definition.name, recovery.definition.name))
        val current = ConversationTurn(MessageV2(content = "private-current-request", platformType = null), null, true)
        val history = ConversationTurn(MessageV2(content = "private-old-history ".repeat(2000), platformType = null), null, false)
        val prompt = "private-system-instructions"
        fun reject(turns: List<ConversationTurn>) = assertThrows(GitHubToolContextBudgetException::class.java) {
            planAuthorizedToolContext(turns, prompt, tools, TokenBudgetSettings(contextTokens = 2048, outputTokens = 512), true, githubMode = true, requestedTask = "Inspect the repository")
        }.providerFailure()
        val withHistory = reject(listOf(history, current))
        val withoutHistory = reject(listOf(current))
        val fields = requireNotNull(withHistory.failureDetails)
        assertTrue(requireNotNull((fields["required_schema_count"] as? JsonPrimitive)?.content?.toInt()) > 0)
        assertEquals(JsonPrimitive(providerSchemaTokens(recovery.definition, true)), fields["required_recovery_schema_tokens"])
        assertEquals(withoutHistory.failureDetails, fields)
        assertEquals(withoutHistory.message, withHistory.message)
        assertFalse(withHistory.message.contains("history"))
        assertFalse(withHistory.message.contains("attachments"))
        val costs = ContextBudgetService.breakdown(listOf(current), prompt, emptyList())
        assertEquals(JsonPrimitive(costs.total), fields["admission_prompt_tokens"])
        assertEquals(JsonPrimitive(costs.system), fields["admission_system_tokens"])
        assertEquals(JsonPrimitive(costs.currentMessage), fields["admission_current_request_tokens"])
        assertEquals(JsonPrimitive(0), fields["admission_history_tokens"])
        assertEquals(JsonPrimitive(0), fields["admission_attachment_reserve_tokens"])
        assertEquals(JsonPrimitive(false), fields["dispatched"])
        assertEquals(JsonPrimitive(false), fields["retryable"])
        for (privateText in listOf(current.userMessage.content, "private-old-history", prompt)) {
            assertFalse(fields.toString().contains(privateText))
            assertFalse(withHistory.message.contains(privateText))
        }
    }

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
        val rejectedCosts = ContextBudgetService.breakdown(turns, "Use current schemas.", emptyList())
        assertEquals(JsonPrimitive(rejectedCosts.system), failure.failureDetails?.get("admission_system_tokens"))
        assertEquals(JsonPrimitive(rejectedCosts.currentMessage), failure.failureDetails?.get("admission_current_request_tokens"))
        assertEquals(JsonPrimitive(0), failure.failureDetails?.get("admission_history_tokens"))
        assertEquals(JsonPrimitive(0), failure.failureDetails?.get("admission_attachment_reserve_tokens"))
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", ProfileFailureRecord.classify("GITHUB_REQUIRED_TOOL_CONTEXT_BUDGET: historical error").category)
        val plan = planAuthorizedToolContext(turns, "Use current schemas.", resolved, TokenBudgetSettings(contextTokens = 8192, outputTokens = 1024), true)
        assertEquals(setOf("github_context_inspect", "github_pr_inspect", ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
        assertFalse(plan.tools.any { !it.modelVisible })
        assertEquals(resolved.map { it.modelToolName }.toSet(), plan.authorizedTools.map { it.name }.toSet())
        val expected = resolved.map { it.tool.definition }.filter { it.name in setOf("github_context_inspect", "github_pr_inspect", ProgressiveToolCatalog.NAME) }
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
            ResolvedAgentTool(
                tool,
                null,
                "GitHub",
                tool.definition.name,
                tool.definition.name,
                progressiveCatalog = catalog.takeIf { tool === catalog }
            )
        }
        val turn = ConversationTurn(MessageV2(content = task, platformType = null), null, true)
        val plan = planAuthorizedToolContext(
            listOf(turn),
            "Use GitHub tools.",
            resolved,
            TokenBudgetSettings(contextTokens = 5632, outputTokens = 1024),
            true,
            githubMode = true,
            requestedTask = task
        )
        assertTrue(plan.tools.any { it.name == "github_context_inspect" })
        assertTrue(plan.tools.any { it.name == ProgressiveToolCatalog.NAME })
        assertTrue(plan.tools.count { ProgressiveToolCatalog.isGitHubTool(it) } < operations.size)
        assertEquals(
            operations.map { it.definition.name }.toSet(),
            plan.authorizedTools.map { it.name }.toSet() - ProgressiveToolCatalog.NAME
        )
    }

    @Test fun ordinaryChatStagesGitHubIntegrationAndKeepsTheAuthorizedCatalog() {
        val task = "Test all GitHub integration tools"
        val tools = GitHubTool().operations().map { tool -> ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name) }
        val turn = ConversationTurn(MessageV2(content = task, platformType = null), null, true)
        val plan = planAuthorizedToolContext(listOf(turn), "Verify with receipts.", tools, TokenBudgetSettings(contextTokens = 5632, outputTokens = 768), externalTools = true)
        assertTrue(plan.tools.any { it.name == ProgressiveToolCatalog.NAME })
        assertTrue(plan.authorizedTools.map { it.name }.containsAll(tools.map { it.modelToolName }))
    }

    @Test fun providerRoundDefersLargeOptionalGithubSchema() {
        val recovery = AgentToolDefinition("read_recovery_context", "Recover task receipts", buildJsonObject {})
        val context = AgentToolDefinition("github_context", "Inspect repository", buildJsonObject {})
        val large = AgentToolDefinition("github_write", "Write staged changes ".repeat(500), buildJsonObject {})
        val discovery = ProgressiveToolCatalog(emptyList(), "discover").definition
        val limits = AgentRunLimits(
            githubMode = true,
            contextTokens = 4096,
            initialContextTokens = 2000,
            finalResponseReserveTokens = 1024
        )
        val selected = fitProviderToolSchemas(listOf(context, large, recovery, discovery), limits, 0, 0)
        assertTrue(selected.contains(context))
        assertTrue(selected.contains(recovery))
        assertTrue(selected.contains(discovery))
        assertFalse(selected.contains(large))
    }

    @Test fun aPausedDirectoryKeepsAllSixOperationsReachableOnA4096TokenRoute() {
        val discovery = ProgressiveToolCatalog(emptyList(), "discover").definition
        val names = listOf("github_context", "github_pr", "github_ci", "github_read", "github_search", "github_issue")
        val operations = names.map { AgentToolDefinition(it, "bounded operation ".repeat(134), buildJsonObject {}) }
        val limits = AgentRunLimits(githubMode = true, contextTokens = 4096, initialContextTokens = 218, finalResponseReserveTokens = 617)
        assertTrue(operations.sumOf { providerSchemaTokens(it, true) } > 3005)
        assertEquals(listOf(operations.first()), fitProviderToolSchemas(operations, limits, 0, 0))
        val focused = operations[2]
        assertEquals(listOf(focused), fitProviderToolSchemas(operations, limits, 0, 0, completedOperations = listOf(focused.name), focusedOperation = focused.name))
        for (chosen in operations) {
            val available = fitProviderToolSchemas((listOf(chosen) + operations + discovery).distinctBy { it.name }, limits, 0, 0)
            val paused = afterRepeatedGitHubDiscovery(available, true)
            assertTrue(chosen in paused)
            assertFalse(discovery in paused)
            assertTrue(available.sumOf { providerSchemaTokens(it, true) } <= 3005)
            assertTrue(discovery in afterRepeatedGitHubDiscovery(available, false))
        }
        assertEquals(listOf(discovery), afterRepeatedGitHubDiscovery(listOf(discovery), true))
    }

    @Test fun compactRunnerExecutesSixRegisteredOperationsInOrderWithoutDiscovery() = runTest {
        val names = listOf("github_context", "github_pr", "github_ci", "github_read", "github_search", "github_issue")
        val executed = mutableListOf<String>()
        val operations = names.mapIndexed { index, name ->
            val targetCost = if (index == 0) 777 else 778
            val description = "x".repeat(3 * (targetCost - 16) - name.length - 2)
            object : AgentTool {
                override val definition = AgentToolDefinition(name, description, buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    executed += name
                    if (name == names.first() && arguments["page"] == JsonPrimitive(1)) {
                        val page = buildJsonObject {
                            put("schema", "github.result.v1")
                            put(
                                "continuation_call",
                                buildJsonObject {
                                    put("tool", name)
                                    put("arguments", buildJsonObject { put("page", 2) })
                                }
                            )
                        }
                        return AgentToolResult(callId, ToolResultContent.Json(page), false, retainedContent = ToolResultContent.Text("Unpaged upstream bytes"))
                    }
                    return AgentToolResult(callId, ToolResultContent.Text("Observed $name"), false)
                }
            }
        }
        val schemaTokens = operations.sumOf { providerSchemaTokens(it.definition, true) }.toInt()
        assertEquals(4667, schemaTokens)
        val plannedSchemaTokens = providerSchemaTokens(operations.first().definition, true)
        val limits = AgentRunLimits(githubMode = true, contextTokens = 4096, initialContextTokens = 218 + plannedSchemaTokens.toInt(), initialGitHubSchemaTokens = plannedSchemaTokens, finalResponseReserveTokens = 617, maxRounds = 9)
        val stages = listOf(names.first()) + names
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(tools.sumOf { providerSchemaTokens(it, true) } <= 3005)
                if (executed.size == stages.size) {
                    emit(ProviderEvent.TextDelta("Six observations complete."))
                } else {
                    val next = stages[executed.size]
                    assertEquals(next, tools.single().name)
                    emit(ProviderEvent.ToolCall("phase-${executed.size}", next, buildJsonObject { if (next == names.first()) put("page", executed.size + 1) }))
                    if (executed.isEmpty()) emit(ProviderEvent.ToolCall("unadvertised", names.last(), buildJsonObject {}))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(limits).run(session, operations).toList()
        assertEquals(stages, executed)
        assertFalse(events.toString(), events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun compactInitialAdmissionValidatesEveryExplicitPhaseAndAdvertisesTheNextOne() {
        val names = listOf("github_context", "github_pr", "github_ci", "github_read", "github_search", "github_issue")
        val task = names.joinToString(" then ") { "use tool $it" }
        val definitions = names.map { AgentToolDefinition(it, "bounded operation ".repeat(168), buildJsonObject {}) }
        val catalog = ProgressiveToolCatalog(definitions, task, githubMode = true, contextCeilingTokens = 4096)
        val operations = definitions.map { definition ->
            object : AgentTool {
                override val definition = definition
                override suspend fun execute(callId: String, arguments: JsonObject) = error("Planning must not dispatch")
            }
        }
        val resolved = (operations + catalog).map { ResolvedAgentTool(it, null, "GitHub", it.definition.name, it.definition.name, progressiveCatalog = catalog.takeIf { item -> item === it }) }
        val turns = listOf(ConversationTurn(MessageV2(content = task, platformType = null), null, true))
        val settings = TokenBudgetSettings(contextTokens = 4096, outputTokens = 617)
        val plan = planAuthorizedToolContext(turns, "", resolved, settings, true, githubMode = true, requestedTask = task)
        assertEquals(617, plan.outputTokens)
        assertEquals(setOf(names.first(), ProgressiveToolCatalog.NAME), plan.tools.map { it.name }.toSet())
        assertTrue(names.all { name -> plan.authorizedTools.any { it.name == name } })
        for (name in names) {
            assertEquals(name, catalog.selected(plan.authorizedTools).first().name)
            catalog.recordObservation(name, AgentToolResult("$name-failed", ToolResultContent.Text("Temporary error"), true))
            assertEquals(name, catalog.selected(plan.authorizedTools).first().name)
            catalog.recordObservation(name, AgentToolResult(name, ToolResultContent.Text("Read"), false))
        }
        assertThrows(RequiredToolReadinessFailure::class.java) {
            planAuthorizedToolContext(turns, "", resolved.filterNot { it.modelToolName == names.last() }, settings, true, githubMode = true, requestedTask = task)
        }
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

    @Test fun officialOnlyLookupSurvivesInitialPlanningAndARestrictedProviderWindow() {
        val task = "Can you inspect PR #42?"
        fun resolved(name: String): ResolvedAgentTool {
            val modelName = "mcp__work__$name"
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition(modelName, name, buildJsonObject {}, capabilities = setOf(AgentToolCapability.GITHUB))
                override suspend fun execute(callId: String, arguments: JsonObject) = error("Planning must not dispatch a tool")
            }
            return ResolvedAgentTool(tool, "work", "GitHub", name, modelName)
        }
        val read = resolved("get_pull_request")
        val write = resolved("merge_pull_request")
        val turn = ConversationTurn(MessageV2(content = task, platformType = null), null, true)
        val plan = planAuthorizedToolContext(listOf(turn), "", listOf(write, read), TokenBudgetSettings(contextTokens = 8192, outputTokens = 1024), externalTools = true, githubMode = true)
        assertEquals(setOf(read.modelToolName, ProgressiveToolCatalog.NAME), plan.tools.mapTo(hashSetOf()) { it.name })
        val allowed = providerSchemaTokens(read.tool.definition, true).toInt()
        val limits = AgentRunLimits(githubMode = true, githubTask = true, githubTaskRequest = task, contextTokens = 8192, finalResponseReserveTokens = 512)
        val round = fitProviderToolSchemas(listOf(write.tool.definition, read.tool.definition), limits, 0, 0, maxSchemaTokens = allowed)
        assertEquals(listOf(read.modelToolName), round.map { it.name })
    }

    @Test fun initialGithubDiscoverySurvivesByBorrowingOnlyADefaultReplyAllowance() = runTest {
        val task = "Repair repository issues"
        val context = AgentToolDefinition("github_context", "Inspect the repository", buildJsonObject {})
        val deferred = AgentToolDefinition("github_write", "Stage an authorized change", buildJsonObject {})
        val catalog = ProgressiveToolCatalog(listOf(context, deferred), task, githubMode = true)
        val resolved = (
            listOf(context, deferred).map { definition ->
                object : AgentTool {
                    override val definition = definition
                    override suspend fun execute(callId: String, arguments: JsonObject) = error("Planning must not dispatch a tool")
                }
            } + catalog
            ).map { tool ->
            ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name, progressiveCatalog = catalog.takeIf { tool === catalog })
        }
        val turns = listOf(ConversationTurn(MessageV2(content = task, platformType = null), null, true))
        val input = ContextBudgetService.breakdown(turns, "", emptyList()).total
        val contextCost = providerSchemaTokens(context, true).toInt()
        val output = 4096 - input - contextCost - 256 - 512
        val limits = TokenBudgetSettings(contextTokens = 4096, outputTokens = output)
        // The boundary genuinely admits the immediate read while dropping discovery.
        assertEquals(listOf(context), ContextBudgetService.plan(turns, "", listOf(context, catalog.definition), limits).tools)
        val blocked = assertThrows(GitHubToolContextBudgetException::class.java) {
            planAuthorizedToolContext(turns, "", resolved, limits, true, githubMode = true)
        }
        assertEquals(JsonPrimitive(false), blocked.providerFailure().failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(output), blocked.providerFailure().failureDetails?.get("admission_output_tokens"))
        val recovered = planAuthorizedToolContext(turns, "", resolved, limits, true, githubMode = true, allowOutputReduction = true)
        assertEquals(setOf(context.name, catalog.definition.name), recovered.tools.map { it.name }.toSet())
        assertTrue(requireNotNull(recovered.outputTokens) in 512 until output)
        assertEquals(resolved.map { it.modelToolName }.toSet(), recovered.authorizedTools.map { it.name }.toSet())
        assertTrue(recovered.promptTokens + requireNotNull(recovered.outputTokens) + 256 + 512 <= limits.contextTokens)
        catalog.execute("discover-later", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(deferred.name)))) })
        assertTrue(catalog.selected(recovered.authorizedTools).contains(deferred))
        assertTrue(catalog.selected(emptyList()).isEmpty())
    }

    @Test fun initialGithubDiscoveryRejectsWhenEvenTheMinimumReplyCannotPreserveIt() {
        val task = "Repair repository issues"
        val context = AgentToolDefinition("github_context", "Inspect the repository", buildJsonObject {})
        val deferred = AgentToolDefinition("github_write", "Stage an authorized change", buildJsonObject {})
        val catalog = ProgressiveToolCatalog(listOf(context, deferred), task, githubMode = true)
        val resolved = (
            listOf(context, deferred).map { definition ->
                object : AgentTool {
                    override val definition = definition
                    override suspend fun execute(callId: String, arguments: JsonObject) = error("Planning must not dispatch a tool")
                }
            } + catalog
            ).map { tool ->
            ResolvedAgentTool(tool, null, "GitHub", tool.definition.name, tool.definition.name, progressiveCatalog = catalog.takeIf { tool === catalog })
        }
        val turns = listOf(ConversationTurn(MessageV2(content = task, platformType = null), null, true))
        val limits = TokenBudgetSettings(contextTokens = 768, outputTokens = 512)
        assertEquals(listOf(context), ContextBudgetService.plan(turns, "", listOf(context, catalog.definition), limits).tools)
        val blocked = assertThrows(GitHubToolContextBudgetException::class.java) {
            planAuthorizedToolContext(turns, "", resolved, limits, true, githubMode = true, allowOutputReduction = true)
        }
        assertTrue(blocked.requiredSchemaTokens > blocked.availableSchemaTokens)
        assertEquals(JsonPrimitive(512), blocked.providerFailure().failureDetails?.get("admission_output_tokens"))
        assertEquals(JsonPrimitive(false), blocked.providerFailure().failureDetails?.get("dispatched"))
        assertTrue(planAuthorizedToolContext(turns, "", emptyList(), limits, true, githubMode = true).tools.isEmpty())
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
