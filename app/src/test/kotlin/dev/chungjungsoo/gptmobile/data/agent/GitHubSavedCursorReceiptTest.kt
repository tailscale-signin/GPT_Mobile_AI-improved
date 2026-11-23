package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
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

class GitHubSavedCursorReceiptTest {
    @Test fun alteredNativeHandlesLeaveTheExactIssuedPageAvailableLiveAndRestored() {
        val fixture = Fixture()
        for (cursor in listOf(
            fixture.cursor.substringBeforeLast(':'),
            fixture.cursor.substringBeforeLast(':') + ":1537",
            fixture.cursor.substringBefore(':') + ":999:1536",
            "unissued:0:1536"
        )) {
            val call = fixture.call(cursor = cursor)
            val result = fixture.read(call)
            assertTrue(result.isError)
            if (cursor.substringBeforeLast(':') == fixture.cursor.substringBeforeLast(':') || cursor == fixture.cursor.substringBeforeLast(':')) {
                assertEquals("SAVED_VIEW_CURSOR_ALTERED", result.errorCode)
                val correction = (result.content as ToolResultContent.Json).value.jsonObject.getValue("correction_call").jsonObject
                assertEquals(JsonPrimitive(fixture.cursor), correction.getValue("arguments").jsonObject["view_cursor"])
            }
            fixture.assertUnconsumed(call, listOf(result))
        }
    }

    @Test fun nativeScopeOperationAndModelAliasRefusalsDoNotConsumeAnExactHandle() {
        for ((scope, operation, name) in listOf(
            Triple("another-private-scope", "github_ci", "github_ci"),
            Triple("scope", "github_context", "github_context"),
            Triple("scope", "github_ci", "github_ci__another_connection")
        )) {
            val fixture = Fixture()
            val call = fixture.call(name = name)
            val result = fixture.read(call, scope, operation)
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            fixture.assertUnconsumed(call, listOf(result))
        }
    }

    @Test fun successRequiresTheSameExactHandleSourceAndLocalOutcome() {
        val fixture = Fixture()
        val call = fixture.call()
        val success = fixture.read(call)
        assertFalse(success.isError)
        val receipt = requireNotNull(success.executionReceipt)
        val other = Fixture()
        val foreign = other.read(other.call(id = call.callId))
        val body = (success.content as ToolResultContent.Json).value.jsonObject
        val wrongOffset = success.copy(content = ToolResultContent.Json(JsonObject(body + ("fragment_offset" to JsonPrimitive(999)))))
        for (results in listOf(
            emptyList(),
            listOf(success, success),
            listOf(success.copy(callId = "another-call")),
            listOf(foreign),
            listOf(wrongOffset),
            listOf(success.copy(dispatched = true)),
            listOf(success.copy(errorCode = "TOOL_OUTCOME_UNKNOWN")),
            listOf(success.copy(executionReceipt = JsonObject(receipt + ("saved_view_source_sha256" to JsonPrimitive("0".repeat(64)))))),
            listOf(success.copy(executionReceipt = JsonObject(receipt + mapOf("saved_view_source_sha256" to JsonPrimitive("0".repeat(64)), "saved_view_source_hash" to JsonPrimitive("0".repeat(64)))))),
            listOf(success.copy(executionReceipt = JsonObject(receipt - setOf("saved_view_source_sha256", "saved_view_source_hash")))),
            listOf(success.copy(executionReceipt = JsonObject(receipt - "saved_view_cursor_hash")))
        )) {
            fixture.assertUnconsumed(call, results)
        }
    }

    @Test fun nativeSuccessKeepsItsExactHashedProofWhenTheDisplayBodyIsCompacted() {
        val fixture = Fixture()
        val call = fixture.call()
        val success = fixture.read(call)
        val receipt = requireNotNull(success.executionReceipt)
        assertEquals(JsonPrimitive(GitHubRepositoryStateStore.digest("github.saved-view.cursor.v1\u0000${fixture.cursor}")), receipt["saved_view_cursor_hash"])
        assertEquals(JsonPrimitive(GitHubRepositoryStateStore.digest("github.saved-view.scope.v1\u0000scope")), receipt["saved_view_scope_hash"])
        assertEquals(JsonPrimitive("github_ci"), receipt["saved_view_owner_operation"])
        assertEquals(JsonPrimitive(true), receipt["saved_view_cursor_issued"])
        assertEquals(receipt["saved_view_source_sha256"], receipt["saved_view_source_hash"])
        assertFalse(receipt.values.contains(JsonPrimitive(fixture.cursor)))
        val compacted = success.copy(content = ToolResultContent.Text("Native page body compacted; its receipt is retained."))
        val restored = GitHubReplayRecovery.State(listOf(call), listOf(compacted))
        assertTrue(restored.hasRead(call.name, fixture.cursor))
        assertNull(fixture.next(restored))
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(fixture.original, AgentToolExchange(listOf(call), listOf(compacted))), fixture.tools))
    }

    @Test fun olderIssuanceListsCannotClaimAnUnreadContinuationWasConsumed() {
        val fixture = Fixture()
        val call = fixture.call()
        val success = fixture.read(call)
        val page = (success.content as ToolResultContent.Json).value.jsonObject
        val next = page.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
        val receipt = buildJsonObject {
            put("saved_view_source_sha256", success.executionReceipt!!.getValue("saved_view_source_sha256"))
            put("saved_view_id", fixture.cursor.substringBefore(':'))
            put("saved_view_cursor_ids", JsonArray(listOf(JsonPrimitive(fixture.cursor), JsonPrimitive(next))))
        }
        val legacy = success.copy(executionReceipt = receipt)
        assertTrue(GitHubReplayRecovery.State(listOf(call), listOf(legacy)).hasRead(call.name, fixture.cursor))
        val unread = fixture.call(cursor = next)
        for (result in listOf(legacy, legacy.copy(content = ToolResultContent.Text("Legacy page body compacted.")))) {
            val state = GitHubReplayRecovery.State(listOf(unread), listOf(result))
            assertFalse(state.hasRead(unread.name, next))
        }
    }

    @Test fun availabilityPlanningDoesNotConsumeAndOnlyTheExactSelectedPendingCallReserves() {
        val fixture = Fixture()
        val state = GitHubReplayRecovery.State()
        val planned = requireNotNull(fixture.next(state))
        assertEquals(planned, fixture.next(state))
        assertFalse(state.hasRead(planned.name, fixture.cursor))
        state.record(listOf(planned.copy(arguments = buildJsonObject { put("view_cursor", fixture.cursor.substringBeforeLast(':') + ":1537") })))
        assertFalse(state.hasRead(planned.name, fixture.cursor))
        val captured = planned.captureProviderInvocation()
        state.record(listOf(captured))
        assertTrue(state.hasRead(planned.name, fixture.cursor))
        assertNull(fixture.next(state))
        assertEquals(planned.arguments, captured.originalInvocation?.arguments)
        assertEquals(planned.name, captured.originalInvocation?.name)
        assertFalse(GitHubReplayRecovery.State(listOf(planned)).hasRead(planned.name, fixture.cursor))
    }

    @Test fun nativeExpiryReleasesOnlyTheRejectedCallsPendingReservation() {
        val fixture = Fixture()
        val state = GitHubReplayRecovery.State()
        val planned = requireNotNull(fixture.next(state))
        state.record(listOf(planned))
        assertTrue(state.hasRead(planned.name, fixture.cursor))
        val successful = fixture.call(id = "independent-success")
        val result = fixture.read(successful)
        fixture.clock = 11 * 60_000L
        val rejected = fixture.read(planned)
        assertTrue(rejected.isError)
        state.record(listOf(planned), listOf(rejected))
        assertFalse(state.hasRead(planned.name, fixture.cursor))
        assertFalse(GitHubReplayRecovery.State(listOf(planned), listOf(rejected)).hasRead(planned.name, fixture.cursor))
        state.record(listOf(successful), listOf(result))
        state.record(listOf(planned), listOf(rejected))
        assertTrue("A later rejection cannot erase another successful read", state.hasRead(planned.name, fixture.cursor))
    }

    @Test fun repeatedNativeRefusalsExhaustExactlyThirtyTwoAttemptsAcrossEvictionAndRestore() {
        val fixture = Fixture()
        var state = GitHubReplayRecovery.State()
        val calls = mutableListOf<ProviderEvent.ToolCall>()
        val results = mutableListOf<AgentToolResult>()
        repeat(32) { index ->
            assertFalse(state.exhausted())
            val planned = requireNotNull(fixture.next(state))
            assertEquals(planned, fixture.next(state))
            assertEquals(buildJsonObject { put("view_cursor", fixture.cursor) }, planned.arguments)
            assertTrue(calls.none { it.callId == planned.callId })
            if (index == 0) assertEquals("saved-result-consume-" + GitHubRepositoryStateStore.digest("github_ci:${fixture.cursor}").take(24), planned.callId)
            state.record(listOf(planned))
            val refused = fixture.read(planned, scope = "another-private-scope")
            assertTrue(refused.isError)
            assertFalse(refused.dispatched)
            state.record(listOf(planned), listOf(refused))
            assertFalse(state.hasRead(planned.name, fixture.cursor))
            calls += planned
            results += refused.copy(content = ToolResultContent.Text("Retained native refusal."))
            // The full journal keeps attempt identities while provider replay
            // drops old bodies. Rebuild from that journal on alternating rounds.
            if (index % 2 == 0) state = GitHubReplayRecovery.State(calls, results)
        }
        assertEquals(32, calls.map { it.callId }.toSet().size)
        assertTrue(state.exhausted())
        assertNull(fixture.next(state))
        val restored = GitHubReplayRecovery.State(calls, results)
        assertTrue(restored.exhausted())
        assertNull(fixture.next(restored))
    }

    @Test fun aValidExactReadCanStillCompleteAfterThirtyOneNativeRefusals() {
        val fixture = Fixture()
        val state = GitHubReplayRecovery.State()
        repeat(31) {
            val planned = requireNotNull(fixture.next(state))
            state.record(listOf(planned))
            val refused = fixture.read(planned, scope = "another-private-scope")
            state.record(listOf(planned), listOf(refused))
            assertFalse(state.hasRead(planned.name, fixture.cursor))
        }
        assertFalse(state.exhausted())
        val exact = requireNotNull(fixture.next(state))
        state.record(listOf(exact))
        val success = fixture.read(exact)
        assertFalse(success.isError)
        state.record(listOf(exact), listOf(success))
        assertTrue(state.exhausted())
        assertTrue(state.hasRead(exact.name, fixture.cursor))
        assertNull(fixture.next(state))
    }

    @Test fun verifiedLegacyReadSurvivesNativeCompactionWithoutCreatingFreshProof() {
        val fixture = Fixture()
        val call = fixture.call()
        val legacy = legacyResult(fixture, call)
        val state = GitHubReplayRecovery.State(listOf(call), listOf(legacy))
        assertTrue(state.hasRead(call.name, fixture.cursor))
        val compacted = legacy.copy(content = requireNotNull(GitHubReplayRecovery.compact(legacy.content, 2048, consumed = true)))
        assertFalse(GitHubReplayRecovery.State(listOf(call), listOf(compacted)).hasRead(call.name, fixture.cursor))
        val history = listOf(fixture.original, AgentToolExchange(listOf(call), listOf(compacted)))
        assertNull(GitHubReplayRecovery.nextSavedRead(history, fixture.tools, state = state))
        assertTrue(state.hasRead(call.name, fixture.cursor))
        assertNull(GitHubReplayRecovery.nextSavedRead(history, fixture.tools, state = state))
    }

    @Test fun verifiedLegacyReadSurvivesActualReplayPlaceholders() {
        val fixture = Fixture()
        val call = fixture.call()
        val legacy = legacyResult(fixture, call)
        val exchange = AgentToolExchange(listOf(call), listOf(legacy))
        val consumed = ToolExchangeCompactor.compact(listOf(exchange), 2048, 128, setOf(call.callId)).single().results.single()
        val omitted = ToolExchangeCompactor.compact(listOf(exchange), 64, 64).single().results.single()
        val duplicate = ToolExchangeCompactor.compact(
            listOf(exchange, AgentToolExchange(listOf(call.copy(callId = "duplicate")), listOf(legacy.copy(callId = "duplicate")))),
            2048,
            128
        ).first().results.single()
        for (projected in listOf(consumed, omitted, duplicate)) {
            assertTrue(ToolExchangeCompactor.isReplayPlaceholder(projected.content))
            val state = GitHubReplayRecovery.State(listOf(call), listOf(legacy))
            state.record(listOf(call), listOf(projected))
            assertTrue(state.hasRead(call.name, fixture.cursor))
            assertFalse(GitHubReplayRecovery.State(listOf(call), listOf(projected)).hasRead(call.name, fixture.cursor))
        }
        val narrowed = legacy.copy(content = requireNotNull(GitHubReplayRecovery.compact(legacy.content, 16, consumed = true)))
        assertTrue(narrowed.content is ToolResultContent.Text)
        val state = GitHubReplayRecovery.State(listOf(call), listOf(legacy))
        state.record(listOf(call), listOf(narrowed))
        assertTrue(state.hasRead(call.name, fixture.cursor))
        assertFalse(GitHubReplayRecovery.State(listOf(call), listOf(narrowed)).hasRead(call.name, fixture.cursor))
    }

    @Test fun changedOutcomeOrInvocationInvalidatesPreviouslyVerifiedLegacyConsumption() {
        val fixture = Fixture()
        val call = fixture.call()
        val legacy = legacyResult(fixture, call)
        val compacted = legacy.copy(content = requireNotNull(GitHubReplayRecovery.compact(legacy.content, 2048, consumed = true)))
        val receipt = requireNotNull(compacted.executionReceipt)
        for (rejected in listOf(
            compacted.copy(isError = true, errorCode = "SAVED_VIEW_UNAVAILABLE"),
            compacted.copy(dispatched = true),
            compacted.copy(errorCode = "TOOL_OUTCOME_UNKNOWN"),
            compacted.copy(toolCallBudgetExhausted = true),
            compacted.copy(outputBudgetExhausted = true),
            compacted.copy(executionReceipt = JsonObject(receipt + ("requires_reconciliation" to JsonPrimitive(true)))),
            compacted.copy(executionReceipt = JsonObject(receipt + ("saved_view_source_sha256" to JsonPrimitive("0".repeat(64))))),
            compacted.copy(executionReceipt = JsonObject(receipt + ("saved_view_cursor_ids" to JsonArray(emptyList()))))
        )) {
            val state = GitHubReplayRecovery.State(listOf(call), listOf(legacy))
            state.record(listOf(call), listOf(rejected))
            assertFalse(state.hasRead(call.name, fixture.cursor))
            state.record(listOf(call), listOf(compacted))
            assertFalse("A cleared witness cannot be recreated from compacted display", state.hasRead(call.name, fixture.cursor))
        }
        for (changed in listOf(
            call.copy(name = "github_ci__other"),
            call.copy(arguments = JsonObject(call.arguments + ("action" to JsonPrimitive("runs")))),
            call.copy(originalInvocationFingerprint = "changed")
        )) {
            val state = GitHubReplayRecovery.State(listOf(call), listOf(legacy))
            state.record(listOf(changed), listOf(compacted))
            assertFalse(state.hasRead(call.name, fixture.cursor))
        }
    }

    @Test fun contradictoryBodiesCannotPreserveALegacyWitnessAsDisplayCompaction() {
        val fixture = Fixture()
        val call = fixture.call()
        val legacy = legacyResult(fixture, call)
        val compacted = requireNotNull(GitHubReplayRecovery.compact(legacy.content, 2048, consumed = true))
        val body = (compacted as ToolResultContent.Json).value.jsonObject
        val other = Fixture()
        val otherRecovery = (other.original.results.single().content as ToolResultContent.Json).value.jsonObject.getValue("recovery_call")
        for (content in listOf(
            ToolResultContent.Text("An arbitrary changed result is not a native replay placeholder."),
            ToolResultContent.Json(JsonObject(body + ("operation" to JsonPrimitive("github_context")))),
            ToolResultContent.Json(JsonObject(body + ("source_sha256" to JsonPrimitive("0".repeat(64))))),
            ToolResultContent.Json(JsonObject(body + ("fragment_offset" to JsonPrimitive(999)))),
            ToolResultContent.Json(JsonObject(body + ("recovery_call" to otherRecovery))),
            ToolResultContent.Json(JsonObject(body + ("page_capacity_exceeded" to JsonPrimitive(true))))
        )) {
            val state = GitHubReplayRecovery.State(listOf(call), listOf(legacy))
            state.record(listOf(call), listOf(legacy.copy(content = content)))
            assertFalse(state.hasRead(call.name, fixture.cursor))
        }
    }

    private fun legacyResult(fixture: Fixture, call: ProviderEvent.ToolCall): AgentToolResult {
        val success = fixture.read(call)
        assertFalse(success.isError)
        val page = (success.content as ToolResultContent.Json).value.jsonObject
        val next = page.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject.getValue("view_cursor")
        return success.copy(
            executionReceipt = buildJsonObject {
                put("saved_view_source_sha256", success.executionReceipt!!.getValue("saved_view_source_sha256"))
                put("saved_view_id", fixture.cursor.substringBefore(':'))
                put("saved_view_cursor_ids", JsonArray(listOf(JsonPrimitive(fixture.cursor), next)))
            }
        )
    }

    private class Fixture {
        var clock = 0L
        private val views = GitHubResultViews(pageBytes = 2048, now = { clock })
        val tools = listOf(AgentToolDefinition("github_ci", "CI reads", buildJsonObject { put("type", "object") }))
        val original: AgentToolExchange
        val cursor: String

        init {
            val arguments = buildJsonObject { put("action", "runs") }
            val source = AgentToolResult("source", ToolResultContent.Json(JsonPrimitive("saved source ".repeat(1000))), false)
            val shown = views.present("scope", "github_ci", "github_ci", source, arguments)
            val recovery = (shown.content as ToolResultContent.Json).value.jsonObject.getValue("recovery_call").jsonObject
            cursor = recovery.getValue("arguments").jsonObject.getValue("view_cursor").jsonPrimitive.content
            original = AgentToolExchange(
                listOf(ProviderEvent.ToolCall("repeat", "github_ci", arguments)),
                listOf(
                    AgentToolResult(
                        "repeat",
                        ToolResultContent.Json(
                            buildJsonObject {
                                put("schema", "github.repeat.v1")
                                put("recovery_call", recovery)
                            }
                        ),
                        false,
                        dispatched = false
                    )
                )
            )
        }

        fun call(id: String = "read", name: String = "github_ci", cursor: String = this.cursor) = ProviderEvent.ToolCall(id, name, buildJsonObject { put("view_cursor", cursor) })
        fun next(state: GitHubReplayRecovery.State) = GitHubReplayRecovery.nextSavedRead(listOf(original), tools, state = state)
        fun read(call: ProviderEvent.ToolCall, scope: String = "scope", operation: String = "github_ci") = views.read(scope, operation, call.name, call.arguments.getValue("view_cursor").jsonPrimitive.content, call.callId)

        fun assertUnconsumed(call: ProviderEvent.ToolCall, results: List<AgentToolResult>) {
            val expected = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(original), tools))
            val history = listOf(original, AgentToolExchange(listOf(call), results))
            val live = GitHubReplayRecovery.State()
            live.record(listOf(call))
            live.record(listOf(call), results)
            val restored = GitHubReplayRecovery.State(listOf(call), results)
            for (state in listOf(live, restored)) {
                assertFalse(state.hasRead(call.name, call.arguments.getValue("view_cursor").jsonPrimitive.content))
                assertEquals(expected, GitHubReplayRecovery.nextSavedRead(history, tools, state = state))
            }
            assertEquals(expected, GitHubReplayRecovery.nextSavedRead(history, tools))
        }
    }
}
