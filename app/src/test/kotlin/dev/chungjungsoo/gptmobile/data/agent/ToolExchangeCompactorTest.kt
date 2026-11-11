package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExchangeCompactorTest {
    @Test fun replayCompactionDiagnosticsUseHighWaterMarksInsteadOfEveryRound() {
        val gate = ReplayCompactionLogGate(slowProjectionMicros = 50_000)

        assertTrue(gate.shouldLog(rawTokens = 16_000, exchangeCount = 1, compactionMicros = 1_000))
        assertTrue(!gate.shouldLog(rawTokens = 17_000, exchangeCount = 1, compactionMicros = 1_000))
        assertTrue(gate.shouldLog(rawTokens = 18_000, exchangeCount = 2, compactionMicros = 1_000))
        assertTrue(!gate.shouldLog(rawTokens = 19_000, exchangeCount = 3, compactionMicros = 1_000))
        assertTrue(gate.shouldLog(rawTokens = 36_000, exchangeCount = 3, compactionMicros = 1_000))
        assertTrue(gate.shouldLog(rawTokens = 37_000, exchangeCount = 3, compactionMicros = 50_000))
    }

    @Test fun measurementCacheDoesNotPinDiscardedOversizedBodies() {
        val cache = ToolExchangeCompactor.RenderCache(maxCharacters = 1024, maxEntries = 256)
        fun measureDiscarded(index: Int): java.lang.ref.WeakReference<ToolResultContent> {
            val content = ToolResultContent.Text("discarded-$index " + "x".repeat(1024 * 1024))
            cache.measure(content)
            return java.lang.ref.WeakReference(content)
        }
        val discarded = (0 until 96).map(::measureDiscarded)
        repeat(10) {
            if (discarded.any { it.get() != null }) {
                System.gc()
                Thread.yield()
            }
        }
        assertEquals("Measurements must not retain source payloads after compaction", 0, discarded.count { it.get() != null })
        assertEquals(0, cache.retainedCharacters())
    }

    @Test fun oversizedBodiesKeepBoundedMeasurementsWithoutRetainingSerializedCopies() {
        val cache = ToolExchangeCompactor.RenderCache(maxCharacters = 1024, maxEntries = 4)
        val content = ToolResultContent.Json(buildJsonObject { put("evidence", "😀é evidence ".repeat(2000)) })
        val first = cache.measure(content)
        assertEquals(0, cache.retainedCharacters())
        org.junit.Assert.assertSame(first, cache.measure(content))
        val call = ProviderEvent.ToolCall("large", "read", buildJsonObject { })
        val exchange = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, content, false)))
        assertEquals(ToolExchangeCompactor.estimateTokens(listOf(exchange)), ToolExchangeCompactor.estimateTokens(listOf(exchange), cache))
        repeat(30) { cache.measure(ToolResultContent.Text("body $it")) }
        assertTrue(cache.retainedMeasurements() <= 4)
        cache.clear()
        assertEquals(0, cache.retainedMeasurements())
    }

    @Test fun cachedReplayKeepsIndependentCallsAndLatestEvidenceAcrossChangingBudgets() {
        val cache = ToolExchangeCompactor.RenderCache()
        val exchanges = (0 until 100).map { index ->
            val call = ProviderEvent.ToolCall("call-$index", "read", buildJsonObject { put("path", "source/$index") })
            AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("evidence-$index 😀 " + "x".repeat(18_000)), false)))
        }
        for (budget in listOf(12_000, 4096, 1024, 4096)) {
            val cached = ToolExchangeCompactor.compact(exchanges, budget, 400, renderCache = cache)
            val uncached = ToolExchangeCompactor.compact(exchanges, budget, 400)
            assertEquals(uncached, cached)
            assertTrue(ToolExchangeCompactor.estimateTokens(cached, cache) <= budget)
            assertEquals("call-99", cached.last().results.last().callId)
            cached.forEach { assertEquals(it.calls.map { call -> call.callId }, it.results.map { result -> result.callId }) }
        }
        assertTrue(cache.retainedCharacters() <= 2 * 1024 * 1024)
        assertTrue(cache.retainedMeasurements() <= 256)
    }

    @Test fun replayRenderCacheReusesImmutableBodiesWithinABoundedRunWindow() {
        val cache = ToolExchangeCompactor.RenderCache(maxCharacters = 1024, maxEntries = 4)
        val content = ToolResultContent.Json(kotlinx.serialization.json.buildJsonObject { put("value", kotlinx.serialization.json.JsonPrimitive("x".repeat(200))) })
        val first = cache.render(content)
        org.junit.Assert.assertSame(first, cache.render(content))
        repeat(20) { cache.render(ToolResultContent.Text("y".repeat(200))) }
        org.junit.Assert.assertTrue(cache.retainedCharacters() <= 1024)
        cache.clear()
        org.junit.Assert.assertEquals(0, cache.retainedCharacters())
    }

    @Test fun compactSavedPageRetainsRevisionAndResumesAtFirstOmittedCharacter() {
        val text = "Retained evidence 😀 ".repeat(2000)
        val call = ProviderEvent.ToolCall("read", "read_recovery_context", buildJsonObject { put("offset", 100) })
        val result = AgentToolResult(
            "read",
            ToolResultContent.Json(
                buildJsonObject {
                    put("corpus_revision", "revision")
                    put("evidence_id", "record")
                    put("offset", 100)
                    put("next_offset", 100 + text.length)
                    put("total_characters", 100 + text.length)
                    put("complete", true)
                    put("saved_work", text)
                }
            ),
            false
        )
        val replay = ToolExchangeCompactor.compact(listOf(AgentToolExchange(listOf(call), listOf(result))), 1000, 400)
        val page = (replay.single().results.single().content as ToolResultContent.Json).value as JsonObject
        val retained = (page.getValue("saved_work") as JsonPrimitive).content
        assertTrue(retained.isNotEmpty())
        assertEquals(text.take(retained.length), retained)
        assertEquals(100 + retained.length, (page["next_offset"] as JsonPrimitive).intOrNull)
        assertEquals(JsonPrimitive("revision"), page["corpus_revision"])
        assertEquals(JsonPrimitive("record"), page["evidence_id"])
        assertEquals(JsonPrimitive(false), page["complete"])
        assertTrue(!retained.last().isHighSurrogate())
        assertTrue(ToolExchangeCompactor.estimateTokens(replay) <= 1000)
    }

    @Test fun equalMeasurementsFromDifferentToolsKeepTheirOwnEvidence() {
        val exchanges = listOf("heart_rate", "sleep_minutes").mapIndexed { index, name ->
            val call = ProviderEvent.ToolCall("call-$index", name, buildJsonObject { put("date", "2026-10-11") })
            AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("60"), false)))
        }
        val replay = ToolExchangeCompactor.compact(exchanges, 1000, 512)
        assertEquals(listOf("60", "60"), replay.flatMap { it.results }.map { (it.content as ToolResultContent.Text).text })
    }

    @Test fun manySmallResultsDoNotExhaustTheBudgetWithUnusedReservations() {
        val exchanges = (0 until 12).map { index ->
            val call = ProviderEvent.ToolCall("call-$index", "read", buildJsonObject { put("index", index) })
            AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("value=$index"), false)))
        }
        val replay = ToolExchangeCompactor.compact(exchanges, 1000, 512)
        assertEquals(exchanges, replay)
        assertTrue(ToolExchangeCompactor.estimateTokens(replay) <= 1000)
    }

    @Test
    fun compact_preserves_latest_failure_instructions_and_bounds_old_errors() {
        val instruction = "Give a final response and ask the user to continue."
        fun exchange(id: String) = AgentToolExchange(
            calls = listOf(ProviderEvent.ToolCall(id, "web_search", buildJsonObject { })),
            results = listOf(AgentToolResult(id, ToolResultContent.Text(id + "x".repeat(600) + instruction), true))
        )
        val compacted = ToolExchangeCompactor.compact(listOf(exchange("old"), exchange("latest")), 4_000, 800)
        val oldText = (compacted.first().results.single().content as ToolResultContent.Text).text
        val latestText = (compacted.last().results.single().content as ToolResultContent.Text).text
        assertTrue(oldText.length <= 480)
        assertTrue(oldText.endsWith(instruction))
        assertEquals("latest" + "x".repeat(600) + instruction, latestText)
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) <= 4_000)
    }

    @Test
    fun compact_bounds_old_tool_payloads_and_keeps_pairing() {
        val exchanges = (0 until 6).map { index ->
            val call = ProviderEvent.ToolCall(
                callId = "call-$index",
                name = "github",
                arguments = buildJsonObject { put("query", "q$index") }
            )
            AgentToolExchange(
                calls = listOf(call),
                results = listOf(
                    AgentToolResult(
                        callId = call.callId,
                        content = ToolResultContent.Text("result-$index\n" + "x".repeat(12_000)),
                        isError = false
                    )
                )
            )
        }

        val compacted = ToolExchangeCompactor.compact(
            exchanges = exchanges,
            maxReplayTokens = 4_000,
            maxResultTokens = 400
        )

        assertEquals(exchanges.size, compacted.size)
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) < ToolExchangeCompactor.estimateTokens(exchanges))
        compacted.zip(exchanges).forEach { (after, before) ->
            assertEquals(before.calls.map { it.callId }, after.calls.map { it.callId })
            assertEquals(before.results.map { it.callId }, after.results.map { it.callId })
        }
    }

    @Test
    fun compact_omits_older_consumed_payloads_when_replay_budget_is_exhausted() {
        fun exchange(index: Int) = AgentToolExchange(
            calls = listOf(
                ProviderEvent.ToolCall(
                    callId = "call-$index",
                    name = "read_url",
                    arguments = buildJsonObject { put("url", "https://example.com/$index") }
                )
            ),
            results = listOf(
                AgentToolResult(
                    callId = "call-$index",
                    content = ToolResultContent.Text("result-$index\n" + "x".repeat(9_000)),
                    isError = false
                )
            )
        )

        val compacted = ToolExchangeCompactor.compact(
            exchanges = (0 until 8).map(::exchange),
            maxReplayTokens = 1_200,
            maxResultTokens = 400
        )

        val texts = compacted.map { (it.results.single().content as ToolResultContent.Text).text }
        assertTrue(texts.first().contains("omitted from primary replay", ignoreCase = true))
        assertTrue(texts.last().contains("result-7"))
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) < 2_000)
    }

    @Test
    fun compact_projects_large_results_after_a_completed_provider_round() {
        val consumedCall = ProviderEvent.ToolCall(
            callId = "consumed",
            name = "github_read",
            arguments = buildJsonObject { put("path", "src/old.kt") }
        )
        val currentCall = ProviderEvent.ToolCall(
            callId = "current",
            name = "github_read",
            arguments = buildJsonObject { put("path", "src/current.kt") }
        )
        val consumedBody = "verified-old-evidence\n" + "x".repeat(12_000)
        val currentBody = "verified-current-evidence\n" + "y".repeat(1_200)
        val exchanges = listOf(
            AgentToolExchange(
                listOf(consumedCall),
                listOf(AgentToolResult(consumedCall.callId, ToolResultContent.Text(consumedBody), false))
            ),
            AgentToolExchange(
                listOf(currentCall),
                listOf(AgentToolResult(currentCall.callId, ToolResultContent.Text(currentBody), false))
            )
        )

        val compacted = ToolExchangeCompactor.compact(
            exchanges = exchanges,
            maxReplayTokens = 8_000,
            maxResultTokens = 4_000,
            consumedCallIds = setOf(consumedCall.callId)
        )

        val consumedText = (compacted.first().results.single().content as ToolResultContent.Text).text
        val currentText = (compacted.last().results.single().content as ToolResultContent.Text).text
        assertTrue(consumedText.contains("already consumed by the model"))
        assertEquals(currentBody, currentText)
        assertEquals(listOf("consumed", "current"), compacted.flatMap { it.calls }.map { it.callId })
        assertEquals(listOf("consumed", "current"), compacted.flatMap { it.results }.map { it.callId })
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) < ToolExchangeCompactor.estimateTokens(exchanges))
    }

    @Test
    fun compact_keeps_small_consumed_results_when_they_cost_less_than_a_projection() {
        val call = ProviderEvent.ToolCall("small", "github_status", buildJsonObject { })
        val result = AgentToolResult(call.callId, ToolResultContent.Text("state=green"), false)
        val exchange = AgentToolExchange(listOf(call), listOf(result))

        val compacted = ToolExchangeCompactor.compact(
            exchanges = listOf(exchange),
            maxReplayTokens = 1_000,
            maxResultTokens = 512,
            consumedCallIds = setOf(call.callId)
        )

        assertEquals(listOf(exchange), compacted)
    }

    @Test
    fun compact_respects_replay_budgets_below_previous_512_token_floor() {
        val call = ProviderEvent.ToolCall(
            callId = "tiny-budget",
            name = "github__github_api",
            arguments = buildJsonObject { put("path", "x".repeat(2_000)) }
        )
        val compacted = ToolExchangeCompactor.compact(
            exchanges = listOf(AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("y".repeat(2_000)), false)))),
            maxReplayTokens = 128,
            maxResultTokens = 128
        )

        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) <= 128)
    }

    @Test
    fun compact_hard_bounds_call_metadata_and_keeps_newest_pairs() {
        val exchanges = (0 until 100).map { index ->
            val call = ProviderEvent.ToolCall(
                callId = "call-$index",
                name = "github__github_api",
                arguments = buildJsonObject {
                    put("action", "read_code")
                    put("path", "src/very/long/path/$index/" + "segment".repeat(12))
                }
            )
            AgentToolExchange(
                calls = listOf(call),
                results = listOf(AgentToolResult(call.callId, ToolResultContent.Text("ok-$index"), false))
            )
        }

        val compacted = ToolExchangeCompactor.compact(exchanges, maxReplayTokens = 4_000, maxResultTokens = 256)

        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) <= 4_000)
        assertTrue(compacted.size < exchanges.size)
        assertEquals("call-99", compacted.last().calls.last().callId)
        compacted.forEach { exchange ->
            assertEquals(exchange.calls.map { it.callId }, exchange.results.map { it.callId })
        }
    }

    @Test
    fun compact_preserves_unmodified_json_result_type() {
        val call = ProviderEvent.ToolCall(
            "location",
            "device_location",
            buildJsonObject { }
        )
        val json = buildJsonObject { put("accuracy_meters", JsonPrimitive(8.0)) }
        val exchange = AgentToolExchange(
            calls = listOf(call),
            results = listOf(AgentToolResult(call.callId, ToolResultContent.Json(json), false))
        )

        val compacted = ToolExchangeCompactor.compact(
            exchanges = listOf(exchange),
            maxReplayTokens = 4_000,
            maxResultTokens = 800
        )

        val content = compacted.single().results.single().content
        assertTrue(content is ToolResultContent.Json)
        assertEquals(json, (content as ToolResultContent.Json).value)
    }

    @Test
    fun compact_deduplicates_older_identical_results() {
        val payload = "same payload " + "z".repeat(5_000)
        fun exchange(id: String) = AgentToolExchange(
            calls = listOf(
                ProviderEvent.ToolCall(id, "read_url", buildJsonObject { put("url", "https://example.com") })
            ),
            results = listOf(AgentToolResult(id, ToolResultContent.Text(payload), false))
        )

        val compacted = ToolExchangeCompactor.compact(
            exchanges = listOf(exchange("old"), exchange("new")),
            maxReplayTokens = 3_000,
            maxResultTokens = 800
        )

        val oldText = (compacted.first().results.first().content as ToolResultContent.Text).text
        val newText = (compacted.last().results.first().content as ToolResultContent.Text).text
        assertTrue(oldText.contains("duplicate tool result omitted", ignoreCase = true))
        assertTrue(newText.contains("same payload"))
    }
}
