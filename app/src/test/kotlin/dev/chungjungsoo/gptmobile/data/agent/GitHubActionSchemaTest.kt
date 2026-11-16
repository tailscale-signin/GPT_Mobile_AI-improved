package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

    @Test fun rejectedCiAndFileReadsOfferBoundedSchemaValidCorrections() {
        val sha = "a".repeat(40)
        fun checkCorrection(tool: String, input: String, expected: String) {
            val schema = GitHubOperationTool.definition(tool).inputSchema
            val arguments = Json.parseToJsonElement(input) as JsonObject
            val violations = ToolArgumentValidator.errors(arguments, schema) + GitHubOperationArguments.errors(tool, arguments)
            assertTrue(violations.isNotEmpty())
            val result = ToolArgumentValidator.rejection("test-call", tool, arguments, schema, violations)
            assertFalse(result.dispatched)
            val correction = ((result.content as ToolResultContent.Json).value as JsonObject)["correction_call"] as JsonObject
            val corrected = correction["arguments"] as JsonObject
            assertEquals(Json.parseToJsonElement(expected), corrected)
            assertTrue(ToolArgumentValidator.errors(corrected, schema).isEmpty())
            assertTrue(GitHubOperationArguments.errors(tool, corrected).isEmpty())
        }
        checkCorrection(
            "github_ci",
            """{"action":"runs","branch":"main","status":"completed","conclusion":"failure"}""",
            """{"action":"runs","branch":"main","status":"failure"}"""
        )
        checkCorrection(
            "github_ci",
            """{"action":"logs","job_id":3,"start_line":10,"end_line":3010}""",
            """{"action":"logs","job_id":3,"start_line":10,"end_line":2009}"""
        )
        checkCorrection(
            "github_read",
            """{"action":"ranges","commit_sha":"$sha","ranges":[{"path":"build.yml","start_line":1,"max_lines":2000,"end_line":-1}]}""",
            """{"action":"ranges","commit_sha":"$sha","ranges":[{"path":"build.yml","start_line":1,"max_lines":2000}]}"""
        )
    }

    @Test fun searchCorrectsTheReadLimitWithoutRewritingPinnedContinuationArguments() {
        val definition = GitHubOperationTool.definition("github_search")
        val arguments = Json.parseToJsonElement("""{"action":"search","query":"object Task","max_new_blobs":0}""") as JsonObject
        fun reject(proposal: JsonObject): JsonObject {
            val result = ToolArgumentValidator.rejection("search", definition.name, proposal, definition.inputSchema)
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            return (result.content as ToolResultContent.Json).value as JsonObject
        }
        val corrected = (reject(arguments)["correction_call"] as JsonObject)["arguments"] as JsonObject
        assertEquals(JsonObject(arguments + ("max_new_blobs" to JsonPrimitive(1))), corrected)
        assertTrue(ToolArgumentValidator.errors(corrected, definition.inputSchema).isEmpty())
        for ((key, value) in mapOf("view_cursor" to JsonPrimitive("opaque"), "index_cursor" to JsonPrimitive(5), "page" to JsonPrimitive(2))) {
            assertNull(reject(JsonObject(arguments + (key to value)))["correction_call"])
        }
    }

    @Test fun workflowInventoryProjectionKeepsRemotePaginationAndAnExplicitFullView() {
        val source = Json.parseToJsonElement(
            """{"data":{"workflows":[{"id":1,"name":"Build","path":"build.yml","state":"active","updated_at":"then","html_url":"fixture"}],"total_count":2,"has_more":true,"continuation":{"page":2}}}"""
        ) as JsonObject
        val request = buildJsonObject { put("action", "workflows") }
        val compact = dev.chungjungsoo.gptmobile.data.github.GitHubResultProjection.project("github_ci", request, source)["data"] as JsonObject
        val original = source["data"] as JsonObject
        val row = (compact["workflows"] as JsonArray).single() as JsonObject
        assertEquals(setOf("id", "name", "path", "state"), row.keys)
        assertEquals(original["continuation"], compact["continuation"])
        assertEquals(original["total_count"], compact["total_count"])
        assertEquals(original["has_more"], compact["has_more"])
        assertEquals(source, dev.chungjungsoo.gptmobile.data.github.GitHubResultProjection.project("github_ci", JsonObject(request + ("response_detail" to JsonPrimitive("full"))), source))
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

    @Test fun actualRunnerCanSwitchAndRestoreActionContractsWithoutPausingDiscovery() = runBlocking {
        val run = runSelections(listOf("logs", "jobs", null))
        val original = GitHubOperationTool.definition("github_ci", "github_ci__selected")
        assertEquals(
            listOf(original, GitHubActionSchema.project(original, "logs"), GitHubActionSchema.project(original, "jobs"), original),
            run.definitions.take(4).map { tools -> tools.single { it.name == original.name } }
        )
        assertTrue(run.definitions.take(4).all { tools -> tools.any { it.name == ProgressiveToolCatalog.NAME } })
        assertEquals(listOf(false, false, false).map(::JsonPrimitive), run.discoveryChanges)
        assertEquals(1, run.executed)
        assertFalse(run.events.filterIsInstance<AgentRunEvent.Notice>().any { it.message.contains("discovery is paused", true) })
    }

    @Test fun actualRunnerStillPausesRepeatedIdenticalActionSelections() = runBlocking {
        val run = runSelections(listOf("logs", "logs", "logs"))
        assertEquals(listOf(false, true, true).map(::JsonPrimitive), run.discoveryChanges)
        assertTrue(run.definitions.take(3).all { tools -> tools.any { it.name == ProgressiveToolCatalog.NAME } })
        assertTrue(run.definitions.drop(3).all { tools -> tools.none { it.name == ProgressiveToolCatalog.NAME } })
        assertTrue(run.definitions.all { tools -> tools.any { it.name == "github_ci__selected" } })
        assertEquals(1, run.executed)
        assertTrue(run.events.filterIsInstance<AgentRunEvent.Notice>().any { it.message.contains("discovery is paused", true) })
    }

    private data class SelectionRun(
        val definitions: List<List<AgentToolDefinition>>,
        val events: List<AgentRunEvent>,
        val executed: Int,
        val discoveryChanges: List<kotlinx.serialization.json.JsonElement?>
    )

    private suspend fun runSelections(actions: List<String?>): SelectionRun {
        val original = GitHubOperationTool.definition("github_ci", "github_ci__selected")
        var executed = 0
        val tool = object : AgentTool {
            override val definition = original
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertEquals(JsonPrimitive("logs"), arguments["action"])
                executed++
                return AgentToolResult(callId, ToolResultContent.Text("Observed log fixture"), false)
            }
        }
        val catalog = ProgressiveToolCatalog(listOf(original), "Inspect GitHub CI", githubMode = true)
        // Start with the exact authorized tool already active, so only its schema can change.
        assertFalse(
            catalog.execute(
                "activate-full-contract",
                buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(original.name)))) }
            ).isError
        )
        assertEquals(original, catalog.selected(listOf(original, catalog.definition)).single { it.name == original.name })
        val advertised = mutableListOf<List<AgentToolDefinition>>()
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                advertised += tools
                val step = round++
                when {
                    step < actions.size -> emit(
                        ProviderEvent.ToolCall(
                            "choose-$step",
                            ProgressiveToolCatalog.NAME,
                            buildJsonObject {
                                put("names", JsonArray(listOf(JsonPrimitive(original.name))))
                                actions[step]?.let { put("action", it) }
                            }
                        )
                    )
                    step == actions.size -> emit(
                        ProviderEvent.ToolCall(
                            "logs",
                            original.name,
                            buildJsonObject {
                                put("action", "logs")
                                put("job_id", 3)
                            }
                        )
                    )
                    else -> emit(ProviderEvent.TextDelta("The selected log was read once."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(githubMode = true, maxRounds = 8, maxToolCalls = 10))
            .run(session, listOf(tool, catalog), selectDefinitions = catalog::selected).toList()
        val changes = events.filterIsInstance<AgentRunEvent.ToolFinished>()
            .filter { it.call.name == ProgressiveToolCatalog.NAME }
            .map { (ToolResultEnvelope.element(it.result.content) as? JsonObject)?.get("catalog_unchanged") }
        return SelectionRun(advertised, events, executed, changes)
    }
}
