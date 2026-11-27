package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
    @Test fun unrelatedToolResultsCannotEvictReceiptsForRetainedSavedPageNotes() {
        val savedDigest = "b".repeat(64)
        val reading = buildJsonObject {
            put("schema", "saved_page_reading.v1")
            put(
                "reads",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put(
                                "sources",
                                buildJsonObject {
                                    put(
                                        "saved-call",
                                        buildJsonObject {
                                            put("invocation_sha256", "a".repeat(64))
                                            put("body_sha256", savedDigest)
                                        }
                                    )
                                }
                            )
                            put("notes", "The saved page contains the relevant source.")
                        }
                    )
                )
            )
        }
        val delivered = linkedMapOf(
            "saved-call" to savedDigest,
            "unrelated-old" to "c".repeat(64),
            "unrelated-new" to "d".repeat(64)
        )

        val retained = SavedPageReading.retainDeliveryDigests(delivered, reading, maxResults = 2)

        assertEquals(setOf("saved-call", "unrelated-new"), retained.keys)
        assertEquals(savedDigest, retained["saved-call"])
        assertEquals("d".repeat(64), retained["unrelated-new"])
    }

    @Test fun oversizedPrivateReadRetainsWholeUnicodeObservationsOnlyAfterFreshCompletion() = runBlocking {
        val note = "Retained commit fixé.\n"
        val verbose = note + "📁".repeat(120)
        suspend fun read(reply: List<ProviderEvent>, fresh: Boolean, retained: MutableList<String>): List<ProviderEvent> {
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    reply.forEach { emit(it) }
                }.let { if (fresh) SavedPageDeliveryRound.providerRound(exchanges, it) else it }
            }
            return SavedPageDeliveryRound.read(session, emptyList(), 80) { retained += it }.toList()
        }
        val retained = mutableListOf<String>()
        val complete = listOf(ProviderEvent.TextDelta(verbose), ProviderEvent.Completed)
        assertEquals(listOf(ProviderEvent.Completed), read(complete, true, retained))
        assertEquals(note.trimEnd() + "…", retained.single())
        assertTrue(retained.single().encodeToByteArray().size <= 80)
        for (events in listOf(complete.dropLast(1), listOf(ProviderEvent.TextDelta("A".repeat(120)), ProviderEvent.Completed), listOf(ProviderEvent.TextDelta(verbose), ProviderEvent.ToolCall("x", "tool", JsonObject(emptyMap())), ProviderEvent.Completed))) {
            assertTrue(read(events, true, retained).last() is ProviderEvent.Failed)
        }
        assertTrue(read(complete, false, retained).last() is ProviderEvent.Failed)
        assertEquals(1, retained.size)
    }

    @Test fun replayDiagnosticsUseCanonicalOperationsWithoutRetainingPrivateToolNames() {
        for ((name, operation) in listOf(
            "github_context" to "github_context",
            "github_context__private-route" to "github_context",
            "read_url" to "read_url",
            "private-unknown-tool" to null,
            "github_context_private-route" to null
        )) {
            val call = ProviderEvent.ToolCall("private-call-id", name, buildJsonObject { put("query", "private source text") })
            val details = buildJsonObject { replaySource(listOf(AgentToolExchange(listOf(call), emptyList())), call.callId) }
            assertEquals("name=$name", operation?.let(::JsonPrimitive), details["operation"])
            assertNotNull(details["replay_call_hash"])
            assertFalse(details.toString().contains("private"))
        }
    }

    @Test fun aSavedPageFailureIdentifiesItsBoundaryWithoutInventingADispatchOutcome() {
        for (stage in listOf("planning", "provider_admission", "response_stream")) {
            val failure = SavedPageDeliveryRound.blocked("Retained page", stage)
            assertEquals("REPLAY_EVIDENCE_REQUIRED", failure.errorCode)
            assertEquals("BLOCKED", failure.taskOutcome)
            val details = requireNotNull(failure.failureDetails)
            assertEquals(JsonPrimitive(stage), details["failed_stage"])
            assertEquals(JsonPrimitive("APPLICATION"), details["failure_origin"])
            assertEquals(JsonPrimitive(false), details["logical_continuation_allowed"])
            assertEquals(if (stage == "response_stream") null else JsonPrimitive(false), details["dispatched"])
        }
    }

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
