package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSavedPageCapacityTest {
    @Test fun nativeCapacityRefusalStopsIdenticalAutomaticReadsWithoutConsumingThePage() {
        val fixture = Fixture()
        assertNull(fixture.next())
        assertNull(fixture.next(state = null))
        assertFalse(fixture.state.hasRead(fixture.route, fixture.cursor))
        assertFalse(fixture.refused.dispatched)
        assertEquals(JsonPrimitive(true), fixture.body["page_capacity_exceeded"])
    }

    @Test fun refusalSurvivesReplayEvictionAndRestorationWithoutInventingSuccessfulConsumption() {
        val fixture = Fixture()
        val earlier = fixture.history.dropLast(1)
        assertNull(fixture.next(exchanges = earlier))
        val restored = GitHubReplayRecovery.State(fixture.history.flatMap { it.calls }, fixture.history.flatMap { it.results })
        assertNull(fixture.next(state = restored, exchanges = earlier))
        assertFalse(restored.hasRead(fixture.route, fixture.cursor))
        assertFalse(restored.exhausted())
        val larger = requireNotNull(fixture.next(tokens = 3000, state = restored, exchanges = earlier))
        assertFalse(fixture.read(larger).isError)
    }

    @Test fun onlyAnIssuedLargerPageWithinTheChangedAllowanceCanResumeAndKeepRemoteContinuations() {
        val fixture = Fixture()
        assertNull(fixture.next())
        assertNull(fixture.next(tokens = 1400))
        val larger = requireNotNull(fixture.next(tokens = 3000))
        val selected = requireNotNull(GitHubSavedReadProgress.cursor(larger.arguments))
        val refused = requireNotNull(GitHubSavedReadProgress.cursor(fixture.cursor))
        assertEquals(refused.view, selected.view)
        assertEquals(refused.offset, selected.offset)
        assertTrue(selected.bytes > refused.bytes && selected.bytes <= 3000 * 3)
        val result = fixture.read(larger)
        assertFalse(result.isError)
        assertFalse(result.dispatched)
        val page = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive(true), page["local_page_complete"])
        assertEquals(2, page.getValue("remote_continuations").jsonArray.size)
        assertTrue(page.toString().encodeToByteArray().size <= selected.bytes)
        fixture.state.record(listOf(larger), listOf(result))
        assertTrue(fixture.state.hasRead(fixture.route, fixture.cursor))
        assertNull(fixture.next(tokens = 3000))
    }

    @Test fun aLaterRefusalCannotEraseAnotherInvocationsSuccessfulLargerRead() {
        val fixture = Fixture()
        val larger = requireNotNull(fixture.next(tokens = 3000))
        val success = fixture.read(larger)
        assertFalse(success.isError)
        fixture.state.record(listOf(larger), listOf(success))
        fixture.state.record(listOf(fixture.blocked), listOf(fixture.refused))
        assertTrue(fixture.state.hasRead(fixture.route, fixture.cursor))
        assertNull(fixture.next(tokens = 3000))
    }

    @Test fun aRefusedViewDoesNotBlockAnotherIssuedView() {
        val first = Fixture()
        val second = Fixture()
        val combined = first.history + second.history.first()
        val state = GitHubReplayRecovery.State(combined.flatMap { it.calls }, combined.flatMap { it.results })
        val next = requireNotNull(GitHubReplayRecovery.nextSavedRead(combined, first.tools, 512, state))
        assertFalse(next.arguments.getValue("view_cursor").jsonPrimitive.content.startsWith(first.cursor.substringBefore(':') + ":"))
        assertFalse(second.read(next).isError)
    }

    @Test fun malformedOrForeignRefusalProofCannotSuppressAnOtherwiseEligiblePage() {
        val fixture = Fixture()
        val receipt = requireNotNull(fixture.refused.executionReceipt)
        fun receiptChange(key: String, value: JsonPrimitive) = fixture.refused.copy(executionReceipt = JsonObject(receipt + (key to value)))
        fun bodyChange(key: String, value: JsonPrimitive) = fixture.refused.copy(content = ToolResultContent.Json(JsonObject(fixture.body + (key to value))))
        val variants = listOf(
            fixture.refused.copy(callId = "unrelated-call"),
            fixture.refused.copy(errorCode = "SAVED_VIEW_UNAVAILABLE"),
            fixture.refused.copy(dispatched = true),
            fixture.refused.copy(sharedResult = false),
            fixture.refused.copy(executionReceipt = null),
            receiptChange("saved_view_cursor_issued", JsonPrimitive(false)),
            receiptChange("saved_view_expired", JsonPrimitive(true)),
            receiptChange("saved_view_owner_operation", JsonPrimitive("github_ci")),
            receiptChange("saved_view_cursor_hash", JsonPrimitive("0".repeat(64))),
            receiptChange("saved_view_source_hash", JsonPrimitive("invalid")),
            receiptChange("saved_view_scope_hash", JsonPrimitive("invalid")),
            receiptChange("requires_reconciliation", JsonPrimitive(true)),
            bodyChange("page_capacity_exceeded", JsonPrimitive(false)),
            bodyChange("requested_page_bytes", JsonPrimitive(8192)),
            bodyChange("fragment_offset", JsonPrimitive(999)),
            bodyChange("operation", JsonPrimitive("github_ci")),
            bodyChange("schema", JsonPrimitive("untrusted.result"))
        )
        for (result in variants) {
            val history = fixture.history.dropLast(1) + AgentToolExchange(listOf(fixture.blocked), listOf(result))
            val state = GitHubReplayRecovery.State(history.flatMap { it.calls }, history.flatMap { it.results })
            val next = GitHubReplayRecovery.nextSavedRead(history, fixture.tools, 512, state)
            assertNotNull("An invalid refusal cannot suppress this page: $result", next)
            assertEquals(fixture.blocked.arguments, next!!.arguments)
        }
    }

    @Test fun capacityRefusalsPreserveTheExistingThirtyTwoAttemptLimit() {
        val fixture = Fixture()
        repeat(32) { index ->
            val call = fixture.blocked.copy(callId = "saved-result-consume-capacity-$index")
            fixture.state.record(listOf(call))
            val result = fixture.read(call)
            assertEquals("SAVED_VIEW_PAGE_CAPACITY", result.errorCode)
            fixture.state.record(listOf(call), listOf(result))
        }
        assertTrue(fixture.state.exhausted())
        assertFalse(fixture.state.hasRead(fixture.route, fixture.cursor))
        assertNull(fixture.next(tokens = 3000))
    }

    @Test fun compactedRefusalsKeepTheirNativeCorrectionAndOmittedBodiesCannotInventOne() {
        val fixture = Fixture()
        val compacted = fixture.refused.copy(content = requireNotNull(GitHubReplayRecovery.compact(fixture.refused.content, 1000)))
        assertEquals(JsonPrimitive(true), (compacted.content as ToolResultContent.Json).value.jsonObject["replay_compacted"])
        val compactedHistory = fixture.history.dropLast(1) + AgentToolExchange(listOf(fixture.blocked), listOf(compacted))
        val restored = GitHubReplayRecovery.State(compactedHistory.flatMap { it.calls }, compactedHistory.flatMap { it.results })
        assertNull(fixture.next(state = restored, exchanges = compactedHistory))
        val next = requireNotNull(fixture.next(tokens = 3000, state = restored, exchanges = compactedHistory))
        assertFalse(fixture.read(next).isError)

        val omitted = fixture.refused.copy(content = requireNotNull(GitHubReplayRecovery.compact(fixture.refused.content, 64)))
        assertTrue(omitted.content is ToolResultContent.Text)
        fixture.state.record(listOf(fixture.blocked), listOf(omitted))
        val earlier = fixture.history.dropLast(1)
        assertNull(fixture.next(exchanges = earlier))
        assertFalse(fixture.read(requireNotNull(fixture.next(tokens = 3000, exchanges = earlier))).isError)
        val missingRecipeHistory = earlier + AgentToolExchange(listOf(fixture.blocked), listOf(omitted))
        val missingRecipe = GitHubReplayRecovery.State(missingRecipeHistory.flatMap { it.calls }, missingRecipeHistory.flatMap { it.results })
        assertNull(fixture.next(tokens = 3000, state = missingRecipe, exchanges = missingRecipeHistory))
        assertFalse(missingRecipe.hasRead(fixture.route, fixture.cursor))
    }

    @Test fun aCapacityReceiptCannotAuthorizeAnotherViewOffsetRouteOrInventedIntermediateSize() {
        val fixture = Fixture()
        val original = fixture.body.getValue("recovery_call").jsonObject
        val cursor = original.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        val alternatives = listOf(
            JsonObject(original + ("tool" to JsonPrimitive("github_ci"))),
            JsonObject(original + ("arguments" to buildJsonObject { put("view_cursor", "foreign:" + cursor.substringAfter(':')) })),
            JsonObject(original + ("arguments" to buildJsonObject { put("view_cursor", cursor.substringBefore(':') + ":999:8192") })),
            JsonObject(original + ("arguments" to fixture.blocked.arguments))
        )
        for (correction in alternatives) {
            val refused = fixture.refused.copy(content = ToolResultContent.Json(JsonObject(fixture.body + ("recovery_call" to correction))))
            val history = fixture.history.dropLast(1) + AgentToolExchange(listOf(fixture.blocked), listOf(refused))
            val state = GitHubReplayRecovery.State(history.flatMap { it.calls }, history.flatMap { it.results })
            assertNull(fixture.next(tokens = 3000, state = state, exchanges = history))
            assertFalse(state.hasRead(fixture.route, fixture.cursor))
        }
        // A larger allowance does not create an unissued 4096-byte handle at this offset.
        assertNull(fixture.next(tokens = 1400))
    }

    private class Fixture {
        val route = "github_context__" + "a".repeat(140)
        private val views = GitHubResultViews(pageBytes = 8192)
        val tools = listOf(GitHubOperationTool.definition("github_context", route))
        val state = GitHubReplayRecovery.State()
        val history = mutableListOf<AgentToolExchange>()
        lateinit var blocked: ProviderEvent.ToolCall
        lateinit var refused: AgentToolResult
        val cursor: String get() = blocked.arguments.getValue("view_cursor").jsonPrimitive.content
        val body: JsonObject get() = (refused.content as ToolResultContent.Json).value.jsonObject

        init {
            val request = buildJsonObject {
                put("action", "commits")
                put("owner", "o".repeat(39))
                put("repo", "r".repeat(100))
            }
            val source = views.present(
                "scope",
                "github_context",
                route,
                AgentToolResult(
                    "initial",
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("body", "value ".repeat(200))
                            for ((field, page) in listOf("next_continuation" to 2, "other_continuation" to 3)) {
                                put(
                                    field,
                                    buildJsonObject {
                                        put("action", "list_commits")
                                        put("page", page)
                                        put("ref", "b".repeat(40))
                                    }
                                )
                            }
                        }
                    ),
                    false
                ),
                request
            )
            history += AgentToolExchange(
                listOf(ProviderEvent.ToolCall("initial", route, request)),
                listOf(source.copy(content = requireNotNull(GitHubReplayRecovery.compact(source.content, 1600))))
            )
            repeat(31) {
                if (::refused.isInitialized) return@repeat
                val call = requireNotNull(next()) { "Expected the actual native reader's capacity boundary" }
                state.record(listOf(call))
                val result = read(call)
                history += AgentToolExchange(listOf(call), listOf(result))
                state.record(listOf(call), listOf(result))
                if (result.errorCode == "SAVED_VIEW_PAGE_CAPACITY") {
                    blocked = call
                    refused = result
                } else {
                    assertFalse(result.isError)
                }
            }
            check(::refused.isInitialized)
        }

        fun read(call: ProviderEvent.ToolCall) = views.read("scope", "github_context", route, call.arguments.getValue("view_cursor").jsonPrimitive.content, call.callId)
        fun next(tokens: Int = 512, state: GitHubReplayRecovery.State? = this.state, exchanges: List<AgentToolExchange> = history) = GitHubReplayRecovery.nextSavedRead(exchanges, tools, tokens, state)
    }
}
