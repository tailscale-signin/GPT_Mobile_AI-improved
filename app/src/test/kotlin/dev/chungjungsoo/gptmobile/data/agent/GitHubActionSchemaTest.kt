package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

class GitHubActionSchemaTest {
    @Test fun theActualGitHubRunnerKeepsTheCompactContractOnTheProviderWire() = runBlocking {
        val original = GitHubOperationTool.definition("github_ci")
        var executed = 0
        val tool = object : AgentTool {
            override val definition = original
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertEquals(JsonPrimitive("logs"), arguments["action"])
                executed++
                return AgentToolResult(callId, ToolResultContent.Text("Observed log fixture"), false)
            }
        }
        val catalog = ProgressiveToolCatalog(listOf(original), "Read GitHub CI logs", githubMode = true)
        var round = 0
        var advertised: AgentToolDefinition? = null
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (round++) {
                    0 -> emit(
                        ProviderEvent.ToolCall(
                            "choose",
                            ProgressiveToolCatalog.NAME,
                            buildJsonObject {
                                put("names", JsonArray(listOf(JsonPrimitive("github_ci"))))
                                put("action", "logs")
                            }
                        )
                    )
                    1 -> {
                        advertised = tools.single { it.name == "github_ci" }
                        emit(
                            ProviderEvent.ToolCall(
                                "logs",
                                "github_ci",
                                buildJsonObject {
                                    put("action", "logs")
                                    put("job_id", 3)
                                }
                            )
                        )
                    }
                    else -> emit(ProviderEvent.TextDelta("The fixture log was read."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner(AgentRunLimits(githubMode = true)).run(session, listOf(tool, catalog), selectDefinitions = catalog::selected).toList()
        assertEquals(requireNotNull(GitHubActionSchema.project(original, "logs")), advertised)
        assertEquals(1, executed)
    }

    @Test fun ciLogContractRetainsBoundsAndPagingButExcludesDispatch() {
        val original = GitHubOperationTool.definition("github_ci")
        val compact = requireNotNull(GitHubActionSchema.project(original, "logs"))
        val args = buildJsonObject {
            put("action", "logs")
            put("job_id", 3)
            put("start_line", 10)
            put("end_line", 30)
        }
        assertTrue(ToolArgumentValidator.errors(args, compact.inputSchema).isEmpty())
        assertTrue(ToolArgumentValidator.errors(buildJsonObject { put("view_cursor", "opaque") }, compact.inputSchema).isEmpty())
        assertTrue(
            ToolArgumentValidator.errors(
                buildJsonObject {
                    put("action", "logs")
                    put("job_id", 0)
                },
                compact.inputSchema
            ).isNotEmpty()
        )
        assertTrue(
            ToolArgumentValidator.errors(
                buildJsonObject {
                    put("action", "dispatch")
                    put("workflow_id", "build.yml")
                },
                compact.inputSchema
            ).isNotEmpty()
        )
        val fields = compact.inputSchema["properties"] as JsonObject
        assertFalse("inputs" in fields)
        assertFalse("workflow_id" in fields)
        assertTrue(ProgressiveToolCatalog.schemaTokens(compact) < ProgressiveToolCatalog.schemaTokens(original) / 2)
        assertEquals(original.capabilities, compact.capabilities)
        assertEquals(original.workflowLinks, compact.workflowLinks)
        assertEquals(compact, GitHubActionSchema.authorized(original, compact))
        assertEquals(original, GitHubActionSchema.authorized(original, compact.copy(inputSchema = JsonObject(emptyMap()))))
    }

    @Test fun everyAdvertisedActionHasASelectableContractWithoutExpandingAuthority() {
        for (name in GitHubOperationTool.names) {
            val original = GitHubOperationTool.definition(name)
            val properties = original.inputSchema["properties"] as JsonObject
            val actions = ((properties["action"] as? JsonObject)?.get("enum") as? JsonArray).orEmpty()
            for (action in actions) {
                val compact = requireNotNull(GitHubActionSchema.project(original, (action as JsonPrimitive).content))
                assertEquals(original.name, compact.name)
                assertTrue(properties.keys.containsAll((compact.inputSchema["properties"] as JsonObject).keys))
                assertEquals(JsonArray(listOf(action)), ((compact.inputSchema["properties"] as JsonObject)["action"] as JsonObject)["enum"])
            }
            assertNull(GitHubActionSchema.project(original, "invented_action"))
        }
    }

    @Test fun selectingAndRestoringAnActionChangesTheNextProviderRoundOnly() = runBlocking {
        val original = GitHubOperationTool.definition("github_ci")
        val catalog = ProgressiveToolCatalog(listOf(original), "Inspect GitHub CI", githubMode = true)
        fun select(action: String?) = buildJsonObject {
            put("names", JsonArray(listOf(JsonPrimitive("github_ci"))))
            action?.let { put("action", it) }
        }
        assertFalse(catalog.execute("select", select("logs")).isError)
        val selected = catalog.selected(listOf(original, catalog.definition)).first { it.name == "github_ci" }
        assertEquals(requireNotNull(GitHubActionSchema.project(original, "logs")), selected)
        assertTrue(catalog.execute("invalid", select("not_real")).isError)
        assertEquals(selected, catalog.selected(listOf(original)).single())
        assertTrue(catalog.selected(listOf(catalog.definition)).none { it.name == "github_ci" })
        assertFalse(catalog.execute("restore", select(null)).isError)
        assertEquals(original, catalog.selected(listOf(original)).single())
    }
}
