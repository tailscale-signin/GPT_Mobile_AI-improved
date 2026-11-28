package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDiscoveryHandoffTest {
    private val source = GitHubOperationTool.definition("github_pr", "github_pr__selected")
    private val action get() = requireNotNull(GitHubActionSchema.project(source, "list"))
    private val recovery = AgentToolDefinition("read_recovery_context", "Read retained receipts", buildJsonObject { put("type", "object") })
    private val directory = ProgressiveToolCatalog(emptyList(), "Test all GitHub tools").definition

    private fun arguments(name: String = source.name, action: String = "list") = buildJsonObject {
        put("names", JsonArray(listOf(JsonPrimitive(name))))
        put("action", action)
    }

    private fun limits(allowance: Long, githubMode: Boolean, capacity: Int = 8192) = AgentRunLimits(
        githubTask = true,
        githubMode = githubMode,
        contextTokens = capacity,
        initialContextTokens = (RequestAdmission.maximumEstimatedPrompt(capacity.toLong() - 512) - allowance).toInt(),
        initialGitHubSchemaTokens = 0,
        finalResponseReserveTokens = 512
    )

    @Test fun exactSelectedActionTakesPriorityOverDirectoryAndUnrelatedSchemasAtEveryContextSize() {
        for (githubMode in listOf(false, true)) {
            val required = listOf(recovery, action).sumOf { providerSchemaTokens(it, githubMode) }
            val allowance = required + providerSchemaTokens(directory, githubMode) - 1
            val unrelated = AgentToolDefinition("github_context_inspect__selected", "Earlier context read ".repeat(200), buildJsonObject {})
            for (capacity in listOf(4096, 8192, 65536)) {
                val admitted = fitProviderToolSchemas(listOf(unrelated, recovery, directory, action), limits(allowance, githubMode, capacity), 0, 0, focusedOperation = action.name)
                assertTrue("$capacity / $githubMode: $admitted", action in admitted)
                assertTrue(recovery in admitted)
                assertFalse(directory in admitted)
                assertTrue(admitted.sumOf { providerSchemaTokens(it, githubMode) } <= allowance)
            }
        }
    }

    @Test fun selectedActionThatCannotFitReportsItsActualSchemaShortfall() {
        val required = listOf(recovery, action).sumOf { providerSchemaTokens(it, true) }
        val failure = assertThrows(GitHubToolContextBudgetException::class.java) {
            fitProviderToolSchemas(listOf(recovery, directory, action), limits(required - 1, true), 0, 0, focusedOperation = action.name)
        }
        assertEquals(required, failure.requiredSchemaTokens)
        assertEquals(required - 1, failure.availableSchemaTokens)
        val evidence = failure.providerFailure()
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", evidence.errorCode)
        assertEquals(JsonPrimitive(false), evidence.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(2), evidence.failureDetails?.get("required_schema_count"))
    }

    @Test fun mostRecentDiscoverySurvivesPlannerFocusUntilItsExactActionIsDispatched() = runTest {
        val context = GitHubOperationTool.definition("github_context", "github_context__selected")
        val definitions = listOf(context, source)
        val catalog = ProgressiveToolCatalog(definitions, "Test all GitHub tools", githubMode = true)
        catalog.execute("old-selection", arguments(context.name, "status"))
        catalog.execute("new-selection", arguments())
        val checkpoint = buildJsonObject {
            put(
                "acceptance_ledger",
                buildJsonObject {
                    put(
                        "requirements",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("id", "github_context:repository")
                                    put("requirement", "github_context:repository")
                                    put("required", true)
                                    put("state", "UNTESTED")
                                }
                            )
                        )
                    )
                }
            )
        }
        catalog.focusRequirement(checkpoint)
        val allowed = definitions + catalog.definition
        val selected = catalog.selected(allowed)
        assertEquals(action, selected.first { it.name == source.name })
        assertEquals(source.name, catalog.focusedOperation(selected))
        catalog.recordSelection(allowed, selected)
        assertEquals(source.name, catalog.focusedOperation(selected))
        catalog.recordObservation(source.name, AgentToolResult("other-action", ToolResultContent.Text("ok"), false), arguments = buildJsonObject { put("action", "context") })
        assertEquals(source.name, catalog.focusedOperation(selected))
        catalog.recordObservation(source.name, AgentToolResult("not-dispatched", ToolResultContent.Text("invalid"), true, dispatched = false), arguments = buildJsonObject { put("action", "list") })
        assertEquals(source.name, catalog.focusedOperation(selected))
        catalog.recordObservation(source.name, AgentToolResult("observed", ToolResultContent.Text("ok"), false), arguments = buildJsonObject { put("action", "list") })
        assertFalse(source.name == catalog.focusedOperation(selected))
        assertNull(catalog.focusedOperation(allowed.filterNot { it.name == source.name || it.name == context.name }))
    }

    @Test fun revokedAliasAndChangedContractCannotInheritAnEarlierDiscoveryHandoff() = runTest {
        val other = source.copy(name = "github_pr__other")
        val catalog = ProgressiveToolCatalog(listOf(source, other), "Test all GitHub tools", githubMode = true)
        catalog.execute("select", arguments())
        assertNull(catalog.focusedOperation(listOf(other, catalog.definition)))
        val changed = source.copy(contractVersion = AgentContractVersion(2, 0))
        assertNull(catalog.focusedOperation(listOf(changed, other, catalog.definition)))
        catalog.bindExecutionContracts(listOf(changed))
        assertNull(catalog.focusedOperation(listOf(changed, other, catalog.definition)))
        assertEquals(changed, catalog.selected(listOf(changed, other, catalog.definition)).first { it.name == changed.name })
    }

    @Test fun ordinaryGitHubTaskAdmitsAndExecutesTheSelectedProjectionOfALargeColdContract() = runTest {
        var fetched = false
        var executions = 0
        val full = source.copy(description = "Full authorized operation documentation. ".repeat(2000))
        assertTrue(ProgressiveToolCatalog.schemaTokens(full) > ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE)
        val tool = object : AgentTool {
            override val definition = full
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertEquals(JsonPrimitive("list"), arguments["action"])
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("No open pull requests."), false)
            }
        }
        val catalog = ProgressiveToolCatalog(emptyList(), "Test all GitHub tools", acquireCold = { _, _ ->
            fetched = true
            listOf(full)
        })
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (++rounds) {
                    1 -> emit(ProviderEvent.ToolCall("discover", catalog.definition.name, arguments()))
                    2 -> {
                        assertEquals(GitHubActionSchema.project(full, "list"), tools.single { it.name == source.name })
                        val discovery = (exchanges.last().results.single().content as ToolResultContent.Json).value.jsonObject
                        assertTrue(discovery.getValue("available_next_round").toString().contains(source.name))
                        emit(ProviderEvent.ToolCall("list", source.name, buildJsonObject { put("action", "list") }))
                    }
                    else -> emit(ProviderEvent.TextDelta("The selected repository read completed."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, githubMode = false, maxRounds = 4, contextTokens = 16384, initialContextTokens = 500, finalResponseReserveTokens = 1024),
            healthCatalog = catalog
        ).run(session.withToolCatalog(catalog), listOf(catalog), dynamicTools = { if (fetched) listOf(tool) else emptyList() }, selectDefinitions = catalog::selected).toList()
        assertEquals(events.toString(), 3, rounds)
        assertEquals(1, executions)
        assertFalse(events.toString(), events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }
}
