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
    private fun retainedSummaryExchange(): AgentToolExchange {
        val call = ProviderEvent.ToolCall("unread-issue", "github_issue", buildJsonObject { put("issue_number", 2257) })
        val summary = buildJsonObject {
            put("schema", "github.semantic-summary.v1")
            put("operation", "github_issue")
            put("finding", "The retained issue has not necessarily reached the model.")
        }
        val result = AgentToolResult(
            call.callId,
            ToolResultContent.Text("Retained issue evidence ".repeat(1000)),
            false,
            executionReceipt = buildJsonObject {
                put("semantic_summary", summary)
                put("retained_original_complete", true)
            }
        )
        return AgentToolExchange(listOf(call), listOf(result))
    }

    @Test fun olderRequestedSourceSurvivesUnrelatedRecentReadsAndThePendingPairStaysLast() {
        fun read(index: Int, path: String): AgentToolExchange {
            val call = ProviderEvent.ToolCall("read-$index", "github_read", buildJsonObject { put("path", path) })
            return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("source evidence for $path"), false)))
        }
        val exchanges = listOf(read(0, "src/InvoiceTax.kt")) + (1..12).map { read(it, "src/Unrelated$it.kt") }
        val replay = ToolExchangeCompactor.compact(exchanges, 220, 80, taskRequest = "Inspect InvoiceTax rounding")
        assertTrue(ToolExchangeCompactor.estimateTokens(replay) <= 220)
        assertEquals(exchanges.first(), replay.first())
        assertEquals(exchanges.last(), replay.last())
        assertTrue(replay.size < exchanges.size)
    }

    @Test fun outstandingCriterionRehydratesItsCitedReadEvenWhenALaterDuplicateExists() {
        fun read(id: String, path: String, text: String): AgentToolExchange {
            val call = ProviderEvent.ToolCall(id, "github_read", buildJsonObject { put("path", path) })
            return AgentToolExchange(listOf(call), listOf(AgentToolResult(id, ToolResultContent.Text(text), false)))
        }
        val proof = "observed validated implementation ".repeat(10)
        val original = read("cited", "src/Rules.kt", proof)
        val duplicate = read("duplicate", "src/Rules.kt", proof)
        val other = (1..10).map { read("unrelated-$it", "src/Other$it.kt", "unrelated observation") }
        val pending = read("pending", "src/Last.kt", "latest handoff")
        val budget = ToolExchangeCompactor.estimateTokens(listOf(original, pending)) + 130
        val result = ToolExchangeCompactor.compact(listOf(original, duplicate) + other + pending, budget, 200, consumedCallIds = setOf("cited"), taskRequest = "Inspect Other", priorityCallIds = setOf("cited"))
        assertEquals(original, result.first())
        assertEquals(pending, result.last())
        assertTrue(result.none { it == duplicate })
        assertTrue(ToolExchangeCompactor.estimateTokens(result) <= budget)
    }

    @Test fun taskMatchesCannotDisplaceAnAdmittedUnreadPageDuringHardCompaction() {
        fun exchange(call: ProviderEvent.ToolCall) = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("selected source"), false)))
        val tool = "github_read__account"
        val cursor = "retained-source:0:2048"
        val pageId = "saved-result-consume-" + dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("$tool:$cursor").take(24)
        val page = exchange(ProviderEvent.ToolCall(pageId, tool, buildJsonObject { put("view_cursor", cursor) }))
        val historical = (0..9).map { i -> exchange(ProviderEvent.ToolCall("source-$i", tool, buildJsonObject { put("path", "src/InvoiceTax$i.kt") })) }
        val pending = exchange(ProviderEvent.ToolCall("latest", tool, buildJsonObject { put("path", "src/latest.kt") }))
        val pairBudget = ToolExchangeCompactor.estimateTokens(listOf(pending, page))
        val replay = ToolExchangeCompactor.compact(historical + pending + page, pairBudget, 70, resultTokenAllowances = mapOf(pageId to 100), taskRequest = "Trace invoice tax")
        assertEquals(listOf(pending, page), replay)
    }

    @Test fun requiredPageKeepsItsBytesAheadOfANewerAdmittedPageAtTheHardLimit() {
        fun exchange(id: String, argument: String, body: String): AgentToolExchange {
            val call = ProviderEvent.ToolCall(id, "github_read", buildJsonObject { put("path", argument) })
            return AgentToolExchange(listOf(call), listOf(AgentToolResult(id, ToolResultContent.Text(body), false)))
        }
        val first = exchange("first", "original", "first-page-data ".repeat(28))
        val newer = exchange("newer", "another", "newer-page-data ".repeat(28))
        val pending = exchange("pending", "x".repeat(850), "pending result ".repeat(12))
        val required = first.results.single()
        val budget = ToolExchangeCompactor.estimateTokens(listOf(first, pending)) + 8
        val replay = ToolExchangeCompactor.compact(
            listOf(first, newer, pending),
            budget,
            64,
            resultTokenAllowances = mapOf("first" to 180, "newer" to 180),
            requiredSavedPageDigests = mapOf("first" to ToolResultDeliveryProof.digest(required.content))
        )
        assertEquals(first, replay.firstOrNull { it.calls == first.calls })
        assertEquals(pending.calls, replay.last().calls)
        assertTrue(ToolExchangeCompactor.estimateTokens(replay) <= budget)
    }

    @Test fun unreadRetainedResultDoesNotClaimDeliveryWhenOnlyItsSummaryFits() {
        val exchange = retainedSummaryExchange()
        val replay = ToolExchangeCompactor.compact(listOf(exchange), 2000, 512)
        val summary = (replay.single().results.single().content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(false), summary["previously_delivered"])
        assertEquals(JsonPrimitive("UNCONFIRMED"), summary["prior_delivery_scope"])
        assertEquals(JsonPrimitive(true), summary["retained_original_complete"])
        assertEquals(exchange.calls, replay.single().calls)
    }

    @Test fun restoredLegacySummaryCannotProveDeliveryWithoutACheckpointedConsumption() {
        val exchange = retainedSummaryExchange()
        val legacy = exchange.results.single().copy(
            content = ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.evidence-summary.v1")
                    put("semantic_summary", buildJsonObject { put("operation", "github_issue") })
                    put("previously_delivered", true)
                }
            ),
            executionReceipt = null
        )
        val replay = ToolExchangeCompactor.compact(listOf(exchange.copy(results = listOf(legacy))), 2000, 512)
        val summary = (replay.single().results.single().content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(false), summary["previously_delivered"])
        assertEquals(JsonPrimitive("UNCONFIRMED"), summary["prior_delivery_scope"])
    }

    @Test fun consumedFullResultKeepsItsDeliveryScopeAcrossSerializedSummaryRestoration() {
        val exchange = retainedSummaryExchange()
        val consumed = setOf(exchange.calls.single().callId)
        val first = ToolExchangeCompactor.compact(listOf(exchange), 2000, 512, consumed)
        val projected = first.single().results.single()
        val restored = projected.copy(
            content = ToolResultContent.Json(kotlinx.serialization.json.Json.parseToJsonElement((projected.content as ToolResultContent.Json).value.toString())),
            executionReceipt = null
        )
        val replay = ToolExchangeCompactor.compact(listOf(exchange.copy(results = listOf(restored))), 2000, 512, consumed)
        val summary = (replay.single().results.single().content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(true), summary["previously_delivered"])
        assertEquals(JsonPrimitive("FULL_RESULT"), summary["prior_delivery_scope"])
        assertEquals(JsonPrimitive(true), summary["retained_original_complete"])
    }

    @Test fun consumingOnlyAProjectedSummaryNeverClaimsFullSourceDelivery() {
        val exchange = retainedSummaryExchange()
        val first = ToolExchangeCompactor.compact(listOf(exchange), 2000, 512)
        val restored = first.map { it.copy(results = it.results.map { row -> row.copy(executionReceipt = null) }) }
        val consumed = setOf(exchange.calls.single().callId)
        val replay = ToolExchangeCompactor.compact(restored, 2000, 512, consumed)
        val summary = (replay.single().results.single().content as ToolResultContent.Json).value as JsonObject
        assertEquals(JsonPrimitive(true), summary["previously_delivered"])
        assertEquals(JsonPrimitive("SEMANTIC_SUMMARY"), summary["prior_delivery_scope"])
    }

    @Test fun unreadSummaryStaysUnreadAfterRestorationAndHardReplayCompaction() {
        val exchange = retainedSummaryExchange()
        val first = ToolExchangeCompactor.compact(listOf(exchange), 2000, 512)
        val restored = first.map { it.copy(results = it.results.map { row -> row.copy(executionReceipt = null) }) }
        for (budget in listOf(2000, 180, 80)) {
            val replay = ToolExchangeCompactor.compact(restored, budget, 512)
            val content = replay.single().results.single().content
            if (content is ToolResultContent.Json) {
                assertEquals(JsonPrimitive(false), (content.value as JsonObject)["previously_delivered"])
            } else {
                assertTrue(!(content as ToolResultContent.Text).text.contains("consumed"))
            }
        }
    }

    @Test fun compactKeepsIssueFindingAcrossRepeatedConsumedSummaryProjection() {
        val summary = buildJsonObject {
            put("schema", "github.semantic-summary.v1")
            put("operation", "github_issue")
            put(
                "resource_observations",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        buildJsonObject {
                            put("number", 2229)
                            put("title", "Verifier loses the final answer")
                            put("state", "open")
                        }
                    )
                )
            )
            put("legacy_metadata", "x".repeat(3000))
        }
        val call = ProviderEvent.ToolCall("issue", "github_issue", buildJsonObject { put("issue_number", 2229) })
        val result = AgentToolResult(call.callId, ToolResultContent.Text("raw issue body ".repeat(1000)), false, executionReceipt = buildJsonObject { put("semantic_summary", summary) })
        val first = ToolExchangeCompactor.compact(listOf(AgentToolExchange(listOf(call), listOf(result))), 2000, 512, consumedCallIds = setOf(call.callId))
        val replayWithoutReceipt = first.map { it.copy(results = it.results.map { row -> row.copy(executionReceipt = null) }) }
        val second = ToolExchangeCompactor.compact(replayWithoutReceipt, 2000, 512, consumedCallIds = setOf(call.callId))
        val visible = (second.single().results.single().content as ToolResultContent.Json).value.toString()
        assertTrue(visible.contains("Verifier loses the final answer"))
        assertTrue(visible.contains("2229"))
        assertEquals(call, second.single().calls.single())
        assertEquals(call.callId, second.single().results.single().callId)
        assertTrue(ToolExchangeCompactor.estimateTokens(second) <= 2000)
    }

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
    fun irreduciblePendingArgumentsRemainExactForAnExplicitReplayAdmissionFailure() {
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

        assertEquals(call, compacted.single().calls.single())
        assertEquals(call.callId, compacted.single().results.single().callId)
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) > 128)
    }

    @Test fun hardBoundNeverReplacesWireArgumentsWithAnEmptyObjectOrAHash() {
        val exchanges = (0 until 12).map { index ->
            val call = ProviderEvent.ToolCall("original-$index", "github_read", buildJsonObject { put("path", "src/" + "segment/".repeat(12) + "$index.kt") })
            AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("source ".repeat(1000)), false)))
        }
        val compacted = ToolExchangeCompactor.compact(exchanges, 400, 80)
        assertTrue(compacted.isNotEmpty())
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) <= 400)
        compacted.flatMap { it.calls }.forEach { call ->
            assertEquals(exchanges.flatMap { it.calls }.single { it.callId == call.callId }, call)
            assertEquals(ToolHandoffReceipt.fingerprint(exchanges.flatMap { it.calls }.single { it.callId == call.callId }), ToolHandoffReceipt.fingerprint(call))
        }
    }

    @Test fun hardBoundPreservesEveryCallInTheLatestPendingBatch() {
        val calls = (1..2).map { index -> ProviderEvent.ToolCall("pending-$index", "github_read", buildJsonObject { put("path", "path-$index/" + "x".repeat(1000)) }) }
        val latest = AgentToolExchange(calls, calls.map { AgentToolResult(it.callId, ToolResultContent.Text("retained"), false) })
        val compacted = ToolExchangeCompactor.compact(listOf(latest), 128, 64)
        assertEquals(calls, compacted.single().calls)
        assertEquals(calls.map { it.callId }, compacted.single().results.map { it.callId })
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) > 128)
    }

    @Test fun savedViewConsumptionCannotEvictTheOriginalPendingHandoff() {
        val name = "github_read__account"
        val cursor = "saved-view:0:2048"
        val localId = "saved-result-consume-" + dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook.digest("$name:$cursor").take(24)
        val pendingCall = ProviderEvent.ToolCall("pending", name, buildJsonObject { put("ranges", "x".repeat(1500)) })
        val localCall = ProviderEvent.ToolCall(localId, name, buildJsonObject { put("view_cursor", cursor) })
        fun exchange(call: ProviderEvent.ToolCall) = AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("captured source ".repeat(100)), false)))
        val compacted = ToolExchangeCompactor.compact(listOf(exchange(pendingCall), exchange(localCall)), 150, 64)
        assertEquals(listOf(pendingCall), compacted.flatMap { it.calls })
        assertEquals(listOf("pending"), compacted.flatMap { it.results }.map { it.callId })
        assertTrue(ToolExchangeCompactor.estimateTokens(compacted) > 150)
        val fits = ToolExchangeCompactor.compact(listOf(exchange(pendingCall), exchange(localCall)), 1000, 80)
        assertTrue(fits.any { pendingCall in it.calls })
        assertTrue(fits.flatMap { it.calls }.all { it == pendingCall || it == localCall })
        assertTrue(ToolExchangeCompactor.estimateTokens(fits) <= 1000)
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
