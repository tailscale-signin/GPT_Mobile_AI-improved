package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedCursorContinuationTest {
    private fun tool(visible: Boolean = true) = AgentToolDefinition("github_ci", "CI reads", buildJsonObject { put("type", "object") }, modelVisible = visible)
    private fun recipe(name: String = "github_ci", cursor: String = "retained:0:1536") = buildJsonObject {
        put("tool", name)
        put("arguments", buildJsonObject { put("view_cursor", cursor) })
    }
    private fun exchange(
        id: String = "repeat",
        content: JsonObject = buildJsonObject {
            put("schema", "github.repeat.v1")
            put("recovery_call", recipe())
        }
    ): AgentToolExchange {
        val call = ProviderEvent.ToolCall(id, "github_ci", buildJsonObject { put("action", "runs") })
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(id, ToolResultContent.Json(content), false, dispatched = false, sharedResult = true)))
    }

    @Test fun suppressedReadConsumesTheSavedFullResultWithoutRepeatingRemoteArguments() {
        val next = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange()), listOf(tool())))
        assertEquals("github_ci", next.name)
        assertEquals(setOf("view_cursor"), next.arguments.keys)
        assertEquals(recipe().getValue("arguments"), next.arguments)
        val page = buildJsonObject {
            put("schema", "github.operation.v1")
            put("operation", "github_ci")
            put("fragment_offset", 0)
            put("recovery_call", recipe())
        }
        val restored = AgentToolExchange(listOf(next), listOf(AgentToolResult(next.callId, ToolResultContent.Json(page), false, dispatched = false, executionReceipt = retainedReceipt(next))))
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange(), restored), listOf(tool())))
    }

    @Test fun rejectedMixedCursorCannotConsumeTheIssuedPageInLiveOrRestoredHistory() {
        val original = exchange()
        val issued = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(original), listOf(tool())))
        val invalid = issued.copy(
            callId = "mixed",
            arguments = buildJsonObject {
                put("view_cursor", issued.arguments.getValue("view_cursor"))
                put("action", "status")
            }
        )
        val rejected = AgentToolExchange(listOf(invalid), listOf(AgentToolResult("mixed", ToolResultContent.Text("Invalid arguments"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)))
        val history = listOf(original, rejected)
        assertEquals(issued, GitHubReplayRecovery.nextSavedRead(history, listOf(tool())))
        val restored = GitHubReplayRecovery.State(history.flatMap { it.calls })
        assertEquals(issued, GitHubReplayRecovery.nextSavedRead(history, listOf(tool()), state = restored))
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(rejected), listOf(tool()), state = restored))
        restored.record(listOf(issued))
        assertNull(GitHubReplayRecovery.nextSavedRead(history, listOf(tool()), state = restored))
    }

    @Test fun admittedSavedReadWithCompatibleSelectorsIsNotReadAgain() {
        for (context in listOf(
            buildJsonObject { put("action", "runs") },
            buildJsonObject { put("owner", "OWNER") },
            buildJsonObject { put("repo", "REPO") },
            buildJsonObject {
                put("action", "runs")
                put("owner", "owner")
                put("repo", "repo")
            }
        )) {
            val (original, call, result, cursor) = nativeSavedRead(context)
            assertFalse(result.isError)
            assertFalse(result.dispatched)
            assertTrue(result.sharedResult)
            val history = listOf(original, AgentToolExchange(listOf(call), listOf(result)))
            assertNull("An admitted page must stay consumed: $context", GitHubReplayRecovery.nextSavedRead(history, listOf(tool())))

            val live = GitHubReplayRecovery.State()
            live.record(listOf(call))
            assertFalse("Pending selectors do not prove admission", live.hasRead(call.name, cursor))
            live.record(listOf(call), listOf(result))
            assertTrue(live.hasRead(call.name, cursor))
            assertNull(GitHubReplayRecovery.nextSavedRead(listOf(original), listOf(tool()), state = live))

            // Compaction may discard the page body; its successful local outcome remains.
            val compacted = result.copy(content = ToolResultContent.Text("Retained completed saved-page outcome."))
            val restored = GitHubReplayRecovery.State(listOf(call), listOf(compacted))
            assertTrue(restored.hasRead(call.name, cursor))
            assertNull(GitHubReplayRecovery.nextSavedRead(listOf(original), listOf(tool()), state = restored))
            assertNull(GitHubReplayRecovery.nextSavedRead(listOf(original, AgentToolExchange(listOf(call), listOf(compacted))), listOf(tool())))
        }
    }

    @Test fun nativeSavedPageRefusalsDoNotConsumeTheIssuedPage() {
        val contexts = listOf(
            buildJsonObject { put("action", "status") },
            buildJsonObject { put("owner", "different") },
            buildJsonObject { put("repo", "different") },
            buildJsonObject { put("unsupported_selector", "value") }
        )
        val refused = contexts.map { nativeSavedRead(it) } +
            nativeSavedRead(buildJsonObject { put("action", "runs") }, scope = "other-private-scope")
        for ((original, call, result, cursor) in refused) {
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            val issued = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(original), listOf(tool())))
            val history = listOf(original, AgentToolExchange(listOf(call), listOf(result)))
            assertEquals(issued, GitHubReplayRecovery.nextSavedRead(history, listOf(tool())))
            val live = GitHubReplayRecovery.State()
            live.record(listOf(call))
            live.record(listOf(call), listOf(result))
            assertFalse(live.hasRead(call.name, cursor))
            assertEquals(issued, GitHubReplayRecovery.nextSavedRead(listOf(original), listOf(tool()), state = live))
            val restored = GitHubReplayRecovery.State(listOf(call), listOf(result))
            assertFalse(restored.hasRead(call.name, cursor))
            assertEquals(issued, GitHubReplayRecovery.nextSavedRead(listOf(original), listOf(tool()), state = restored))
        }
    }

    @Test fun mixedPageConsumptionRequiresTheSameSuccessfulLocalOutcome() {
        val (original, call, result, cursor) = nativeSavedRead(buildJsonObject { put("action", "runs") })
        val issued = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(original), listOf(tool())))
        for (results in listOf(
            emptyList(),
            listOf(result.copy(callId = "other")),
            listOf(result.copy(dispatched = true)),
            listOf(result.copy(errorCode = "TOOL_OUTCOME_UNKNOWN")),
            listOf(result.copy(executionReceipt = buildJsonObject { put("requires_reconciliation", true) })),
            listOf(result.copy(executionReceipt = buildJsonObject { put("requiresReconciliation", true) }))
        )) {
            val history = listOf(original, AgentToolExchange(listOf(call), results))
            val state = GitHubReplayRecovery.State()
            state.record(listOf(call), results)
            assertFalse(state.hasRead(call.name, cursor))
            assertEquals(issued, GitHubReplayRecovery.nextSavedRead(history, listOf(tool()), state = state))
        }
        // Success alone cannot authorize unsupported selectors or a non-native tool.
        for (unsupported in listOf(
            call.copy(arguments = JsonObject(call.arguments + ("unsupported_selector" to JsonPrimitive("value")))),
            call.copy(name = "unregistered_tool")
        )) {
            val state = GitHubReplayRecovery.State(listOf(unsupported), listOf(result))
            assertFalse(state.hasRead(unsupported.name, cursor))
            assertEquals(issued, GitHubReplayRecovery.nextSavedRead(listOf(original, AgentToolExchange(listOf(unsupported), listOf(result))), listOf(tool()), state = state))
        }
    }

    @Test fun nextLocalPageIsConsumedOnceAndOnlyFromTheIssuingTool() {
        val first = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange()), listOf(tool())))
        val page = buildJsonObject {
            put("schema", "github.operation.v1")
            put("operation", "github_ci")
            put("fragment_offset", 0)
            put("recovery_call", recipe())
            put("continuation_call", recipe(cursor = "retained:1536:1536"))
        }
        val completed = AgentToolExchange(listOf(first), listOf(AgentToolResult(first.callId, ToolResultContent.Json(page), false, dispatched = false, executionReceipt = retainedReceipt(first))))
        val second = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange(), completed), listOf(tool())))
        assertEquals(recipe(cursor = "retained:1536:1536").getValue("arguments"), second.arguments)
        val foreign = exchange(
            content = buildJsonObject {
                put("schema", "github.repeat.v1")
                put("recovery_call", recipe("github_write"))
            }
        )
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(foreign), listOf(tool())))
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange()), listOf(tool(visible = false))))
    }

    @Test fun aConsumedPageCannotBeReopenedWithADifferentRecoveryPageSize() {
        val first = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange()), listOf(tool())))
        val page = buildJsonObject {
            put("schema", "github.operation.v1")
            put("operation", "github_ci")
            put("fragment_offset", 0)
            put("recovery_call", recipe(cursor = "retained:0:8192"))
            put("continuation_call", recipe(cursor = "retained:1536:1536"))
        }
        val completed = AgentToolExchange(listOf(first), listOf(AgentToolResult(first.callId, ToolResultContent.Json(page), false, dispatched = false, executionReceipt = retainedReceipt(first))))
        val next = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange(), completed), listOf(tool())))
        assertEquals(recipe(cursor = "retained:1536:1536").getValue("arguments"), next.arguments)
    }

    @Test fun remotePaginationAndMutationRecipesAreNeverAutoDispatched() {
        val remote = exchange(
            content = buildJsonObject {
                put("schema", "github.repeat.v1")
                put(
                    "continuation_call",
                    buildJsonObject {
                        put("tool", "github_ci")
                        put(
                            "arguments",
                            buildJsonObject {
                                put("action", "runs")
                                put("page", 2)
                            }
                        )
                    }
                )
            }
        )
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(remote), listOf(tool())))
        val altered = exchange(
            content = buildJsonObject {
                put("schema", "github.repeat.v1")
                put(
                    "recovery_call",
                    buildJsonObject {
                        put("tool", "github_ci")
                        put(
                            "arguments",
                            buildJsonObject {
                                put("view_cursor", "saved:0:1536")
                                put("action", "rerun")
                            }
                        )
                    }
                )
            }
        )
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(altered), listOf(tool())))
    }

    // Older successful wrappers retain exact issued handles even when their
    // display body predates the native successful-read diagnostics.
    private fun retainedReceipt(call: ProviderEvent.ToolCall) = buildJsonObject {
        val cursor = call.arguments.getValue("view_cursor").jsonPrimitive.content
        put("saved_view_id", cursor.substringBefore(':'))
        put("saved_view_cursor_ids", JsonArray(listOf(JsonPrimitive(cursor))))
        put("saved_view_source_sha256", "a".repeat(64))
    }

    private fun nativeSavedRead(context: JsonObject, scope: String = "scope"): NativeSavedRead {
        val views = GitHubResultViews(pageBytes = 2048)
        val request = buildJsonObject {
            put("action", "runs")
            put("owner", "owner")
            put("repo", "repo")
        }
        val source = AgentToolResult("source", ToolResultContent.Json(JsonPrimitive("retained source ".repeat(1000))), false)
        val shown = views.present("scope", "github_ci", "github_ci", source, request)
        val page = (shown.content as ToolResultContent.Json).value.jsonObject
        val recovery = page.getValue("recovery_call").jsonObject
        val cursor = recovery.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        val original = exchange(
            content = buildJsonObject {
                put("schema", "github.repeat.v1")
                put("recovery_call", recovery)
            }
        )
        val call = ProviderEvent.ToolCall("compatible", "github_ci", JsonObject(context + ("view_cursor" to JsonPrimitive(cursor))))
        val result = views.read(scope, "github_ci", call.name, cursor, call.callId, context)
        return NativeSavedRead(original, call, result, cursor)
    }

    private data class NativeSavedRead(val original: AgentToolExchange, val call: ProviderEvent.ToolCall, val result: AgentToolResult, val cursor: String)
}
