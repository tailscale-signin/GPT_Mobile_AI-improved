package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubVerifiedSession
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReplayPlanningTest {
    @Test fun verifiedLastAllowedOperationCompletesWithoutRequestingAnotherAllowance() = runBlocking {
        val definition = AgentToolDefinition(
            "github_context",
            "Read repository status",
            buildJsonObject { put("properties", buildJsonObject { put("action", buildJsonObject { put("const", "status") }) }) }
        )
        val book = GitHubEvidenceBook("task", "owner/repo", "main", setOf(definition.name), now = { 100 })
        book.observe(
            ProviderEvent.ToolCall("last-allowed-status", definition.name, buildJsonObject { put("action", "status") }),
            AgentToolResult("last-allowed-status", ToolResultContent.Json(buildJsonObject { put("commit_sha", "a".repeat(40)) }), false)
        )
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The final receipt already satisfies the requested scope")
        }
        val session = GitHubVerifiedSession(
            delegate, book, "test all tools read only", { 8192 }, { delegate }, {}, {}, now = { 100 },
            authorizedTools = listOf(definition), remainingToolCalls = { 0 }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        assertTrue(events.toString(), events.none { it is ProviderEvent.Failed || it is ProviderEvent.ToolCall })
        assertTrue(events.last() is ProviderEvent.Completed)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isNotEmpty())
    }

    @Test fun exhaustedRetryRestoresStructuredEvidenceWithoutRenewingExecutionAuthority() = runBlocking {
        val saved = linkedMapOf<String, JsonObject>()
        val definition = GitHubOperationTool.definition("github_context")
        fun book() = GitHubEvidenceBook(
            "task", "owner/repo", "main", setOf(definition.name),
            now = { 100 }, load = { saved[it] }, save = { id, value -> saved[id] = value },
            retainedIds = { saved.keys.filter { it.startsWith("ev_") } },
            currentToolContracts = mapOf(definition.name to definition.inputSchema)
        )
        val initial = book()
        initial.observe(
            ProviderEvent.ToolCall("observed-status", definition.name, buildJsonObject { put("action", "status") }),
            AgentToolResult("observed-status", ToolResultContent.Json(buildJsonObject { put("commit_sha", "a".repeat(40)) }), false)
        )
        val original = initial.snapshot().first
        val restored = book()
        restored.restore()
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("No allowance remains for new execution")
        }
        val session = GitHubVerifiedSession(
            delegate, restored, "test all tools read only", { 8192 }, { delegate }, {}, {}, now = { 100 },
            authorizedTools = listOf(definition), currentRequest = "Retry failed stage", remainingToolCalls = { 0 }
        )
        val events = session.streamRound(emptyList(), emptyList()).toList()
        val retained = restored.snapshot().first
        assertEquals(original.keys, retained.keys)
        assertEquals(original.values.single()["result_sha256"], retained.values.single()["result_sha256"])
        assertEquals(JsonPrimitive(true), retained.values.single()["verification_eligible"])
        assertTrue(events.filterIsInstance<ProviderEvent.Failed>().any { it.errorCode == "TOOL_BUDGET_EXHAUSTED" })
        assertTrue(events.none { it is ProviderEvent.ToolCall })
    }

    @Test fun operationalRetryWithoutExecutableToolsReportsItsBlockerWithoutAskingTheModelToRewrite() = runBlocking {
        for (remaining in listOf(0, 12)) {
            val book = GitHubEvidenceBook("task", "owner/repo", "main", emptySet())
            val delegate = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("An operational retry must not become prose-only generation")
            }
            val session = GitHubVerifiedSession(
                delegate, book, "test all tools read only", { 8192 }, { error("A model cannot supply missing operation proof") }, {}, {},
                currentRequest = "Retry failed stage", remainingToolCalls = { remaining }
            )
            val events = session.streamRound(emptyList(), emptyList()).toList()
            val expected = if (remaining == 0) "TOOL_BUDGET_EXHAUSTED" else "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE"
            assertTrue(events.filterIsInstance<ProviderEvent.Failed>().any { it.errorCode == expected })
            assertTrue(events.none { it is ProviderEvent.ToolCall })
            assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().isNotEmpty())
            assertTrue(book.snapshot().first.isEmpty())
        }
    }

    @Test fun integrationPlannerUsesRetainedOriginalsBeforeSpendingTheBudgetOnDisplayFragments() = runBlocking {
        val views = GitHubResultViews()
        val calls = mutableListOf<ProviderEvent.ToolCall>()
        val tools = listOf("github_context" to "commits", "github_pr" to "list", "github_ci" to "workflows").map { (name, action) ->
            object : AgentTool {
                override val definition = AgentToolDefinition(
                    name,
                    "Read the selected repository",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put("action", buildJsonObject { put("const", action) })
                                put("view_cursor", buildJsonObject { put("type", "string") })
                                put("per_page", buildJsonObject { put("type", "integer") })
                                put("state", buildJsonObject { put("type", "string") })
                            }
                        )
                        put("additionalProperties", false)
                        put("anyOf", JsonArray(listOf("action", "view_cursor").map { field -> buildJsonObject { put("required", JsonArray(listOf(JsonPrimitive(field)))) } }))
                    }
                )

                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    calls += ProviderEvent.ToolCall(callId, name, arguments)
                    val cursor = arguments["view_cursor"] as? JsonPrimitive
                    if (cursor != null) return views.read("task", name, name, cursor.content, callId)
                    return views.present(
                        "task",
                        name,
                        name,
                        AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("details", "retained source ".repeat(2000)) }), false),
                        arguments
                    )
                }
            }
        }
        val book = GitHubEvidenceBook("task", "owner/repo", "main", tools.mapTo(mutableSetOf()) { it.definition.name })
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The integration checklist plans from retained receipts")
        }
        val session = GitHubVerifiedSession(
            delegate, book, "test all tools read only", { 8192 }, { error("No model review supplies missing acceptance evidence") }, {}, {},
            authorizedTools = tools.map { it.definition }, remainingToolCalls = { 8 - calls.size }
        )
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, maxToolCalls = 8, maxObservedToolCalls = 12, maxReplayTokens = 1800, maxReplayResultTokens = 512, maxRetainedEvidenceTokens = 1800),
            onToolResult = book::observe
        ).run(session, tools).toList()

        assertEquals(listOf("github_context", "github_pr", "github_ci"), calls.map { it.name })
        assertTrue(calls.none { "view_cursor" in it.arguments })
        assertTrue(book.snapshot().first.values.all { it["retained_result_complete"] == JsonPrimitive(true) })
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.TextDelta })
        assertTrue(session.handlesToolResultRecovery)
    }

    @Test fun automaticRecoveryAllowanceSurvivesReplayEvictionAndCheckpointRestoration() {
        val views = GitHubResultViews()
        val name = "github_release"
        val request = buildJsonObject {
            put("action", "detail")
            put("tag_name", "v1")
        }
        val first = views.present(
            "task",
            name,
            name,
            AgentToolResult("original", ToolResultContent.Json(buildJsonObject { put("body", "retained source ".repeat(15000)) }), false),
            request
        )
        var replay = listOf(AgentToolExchange(listOf(ProviderEvent.ToolCall("original", name, request)), listOf(first.copy(content = requireNotNull(GitHubReplayRecovery.compact(first.content, 1600))))))
        val definitions = listOf(GitHubOperationTool.definition(name))
        val journal = mutableListOf<ProviderEvent.ToolCall>()
        val state = GitHubReplayRecovery.State()
        repeat(32) {
            val call = requireNotNull(GitHubReplayRecovery.nextSavedRead(replay, definitions, 512, state))
            state.record(listOf(call))
            journal += call
            val page = views.read("task", name, name, call.arguments.getValue("view_cursor").jsonPrimitive.content, call.callId)
            assertFalse(page.isError)
            // Only the newest exchange survives the provider's replay window.
            replay = listOf(AgentToolExchange(listOf(call), listOf(page)))
        }
        assertNotNull((replay.single().results.single().content as ToolResultContent.Json).value.jsonObject["continuation_call"])
        assertNull(GitHubReplayRecovery.nextSavedRead(replay, definitions, 512, state))
        assertNull(GitHubReplayRecovery.nextSavedRead(replay, definitions, 512, GitHubReplayRecovery.State(journal)))
        assertNotNull(GitHubReplayRecovery.nextSavedRead(replay, definitions, 512))
    }

    @Test fun ordinaryProviderSessionsKeepAutomaticSavedResultRecovery() {
        val delegate = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.Completed)
        }
        val book = GitHubEvidenceBook("task", "owner/repo", "main", emptySet())
        val session = GitHubVerifiedSession(delegate, book, "Explain this source file", { 8192 }, { delegate }, {}, {})
        assertFalse(session.handlesToolResultRecovery)
    }
}
