package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultDeliveryProofTest {
    @Test fun cachedPayloadDigestsMatchDurableProofForTextAndStructuredPages() {
        val cache = ToolExchangeCompactor.RenderCache()
        for (content in listOf(
            ToolResultContent.Text("Bounded source 😀\n\t\"quoted\""),
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("content", "Bounded source 😀\n\t\"quoted\"")
                }
            )
        )) {
            assertEquals(ToolResultDeliveryProof.digest(content), ToolResultDeliveryProof.digest(content, cache))
            assertEquals(ToolResultDeliveryProof.digest(content), ToolResultDeliveryProof.digest(content, cache))
        }
        assertEquals(2, cache.retainedMeasurements())
    }

    @Test fun optionalAssistUsesOnlyTheRoomLeftAfterAnExactPageAndItsReadingNotes() = kotlinx.coroutines.test.runTest {
        val cache = ToolExchangeCompactor.RenderCache()
        val call = ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap()))
        val source = AgentToolResult(call.callId, ToolResultContent.Text("exact source"), false, optionalAssistKey = "annotation")
        val exchanges = listOf(AgentToolExchange(listOf(call), listOf(source)))
        val metadata = listOf(buildJsonObject { put("note", "extra context ".repeat(100)) })
        for (noteBytes in listOf(null, 128)) {
            val raw = SavedPageReading.project(exchanges, null, noteBytes)
            val budget = SavedPageReading.projectionTokens(raw, cache)
            val (tight, tokens) = SavedPageReading.providerProjection(exchanges, null, noteBytes, budget, cache) { it.copy(assistMetadata = metadata) }
            assertEquals(budget, tokens)
            assertEquals(raw, tight)
            val (ample, annotatedTokens) = SavedPageReading.providerProjection(exchanges, null, noteBytes, budget + 4096, cache) { it.copy(assistMetadata = metadata) }
            assertTrue(annotatedTokens > budget)
            val delivered = ample.single().results.single()
            assertEquals(metadata, delivered.assistMetadata)
            assertEquals(source.content, delivered.content)
            assertEquals(raw.single().results.single().savedPageReading, delivered.savedPageReading)
            val (resumed, resumedTokens) = SavedPageReading.providerProjection(ample, null, noteBytes, budget, cache) { it }
            assertEquals(raw, resumed)
            assertEquals(budget, resumedTokens)
        }
    }

    @Test fun proofBindsOnlyTheDeliveredBodyAndRejectsFailedOrDifferentProjections() {
        val source = AgentToolResult("page", ToolResultContent.Text("delivered"), false, retainedContent = ToolResultContent.Text("unread original"))
        val proof = ToolResultDeliveryProof.capture(listOf(source))
        assertEquals(1, proof.size)
        assertTrue(ToolResultDeliveryProof.matches(source, proof))
        assertFalse(ToolResultDeliveryProof.matches(source.copy(content = requireNotNull(source.retainedContent)), proof))
        assertFalse(ToolResultDeliveryProof.matches(source.copy(isError = true), proof))
        assertTrue(ToolResultDeliveryProof.capture(listOf(source.copy(isError = true))).isEmpty())
    }

    @Test fun mandatoryTaskStateAndRunnerInstructionsSurviveOptionalAssistReplacementAndTightReplay() = kotlinx.coroutines.test.runTest {
        val call = ProviderEvent.ToolCall("read", "github_read", JsonObject(emptyMap()))
        val memory = buildJsonObject {
            put("schema", "github.continuation-control.v1")
            put("decisions", "Keep the exact accepted choice.")
        }
        val instruction = buildJsonObject {
            put("schema", "github.runner-instruction.v1")
            put("instruction", "Finish from retained evidence.")
        }
        val required = listOf(memory, instruction)
        val source = AgentToolResult(call.callId, ToolResultContent.Text("Exact saved source"), false, optionalAssistKey = "annotation", assistMetadata = required)
        val exchanges = listOf(AgentToolExchange(listOf(call), listOf(source)))
        val cache = ToolExchangeCompactor.RenderCache()
        val budget = SavedPageReading.projectionTokens(exchanges, cache)
        val optional = buildJsonObject { put("note", "Optional model notes ".repeat(100)) }
        val (ample, _) = SavedPageReading.providerProjection(exchanges, null, null, budget + 4096, cache) { it.copy(assistMetadata = listOf(optional)) }
        assertEquals(required + optional, ample.single().results.single().assistMetadata)
        val (tight, used) = SavedPageReading.providerProjection(ample, null, null, budget, cache) { it }
        assertEquals(exchanges, tight)
        assertEquals(budget, used)
        val (overCapacity, actual) = SavedPageReading.providerProjection(ample, null, null, 1, cache) { it }
        assertEquals(exchanges, overCapacity)
        assertTrue(actual > 1)
        assertEquals(ToolResultDeliveryProof.digest(source.content), ToolResultDeliveryProof.digest(tight.single().results.single().content))
    }

    @Test fun captureIsBoundedAndRetainsTheNewestSuccessfulResults() {
        val results = (0..ToolResultDeliveryProof.MAX_RESULTS).map { AgentToolResult("page-$it", ToolResultContent.Text("page $it"), false) }
        val proof = ToolResultDeliveryProof.capture(results)
        assertEquals(ToolResultDeliveryProof.MAX_RESULTS, proof.size)
        assertFalse("page-0" in proof)
        assertTrue(ToolResultDeliveryProof.matches(results.last(), proof))
        assertTrue(proof.values.all(ToolResultDeliveryProof::valid))
    }

    @Test fun aConsumedRecoveryWrapperKeepsItsUnreadOffsetAndCannotClaimFullDelivery() {
        val arguments = buildJsonObject { put("view_cursor", "retained:50:1536") }
        val call = ProviderEvent.ToolCall("page", "github_context", arguments)
        val content = ToolResultContent.Json(
            buildJsonObject {
                put("schema", "github.operation.v1")
                put("replay_compacted", true)
                put("previously_delivered", false)
                put("recovery_required", true)
                put(
                    "continuation_call",
                    buildJsonObject {
                        put("tool", "github_context")
                        put("arguments", arguments)
                    }
                )
                put("unverified_preview", "omitted source".repeat(300))
            }
        )
        val result = AgentToolResult(call.callId, content, false)
        val exchanges = listOf(AgentToolExchange(listOf(call), listOf(result)))
        val consumed = ToolResultDeliveryProof.qualifiedConsumed(exchanges, setOf(call.callId), ToolResultDeliveryProof.capture(listOf(result)))
        assertTrue(consumed.isEmpty())
        val replay = ToolExchangeCompactor.compact(exchanges, 1024, 512, consumed)
        val body = requireNotNull(GitHubReplayRecovery.envelope(replay.single().results.single().content))
        assertEquals(JsonPrimitive(false), body["previously_delivered"])
        assertEquals(arguments, (body["continuation_call"] as JsonObject)["arguments"])
        assertTrue(body["prior_delivery_scope"] != JsonPrimitive("FULL_RESULT"))
    }

    @Test fun anExactlyDeliveredSemanticSummaryQualifiesOnlyItsOwnScope() {
        val call = ProviderEvent.ToolCall("summary", "github_context", JsonObject(emptyMap()))
        val result = AgentToolResult(
            call.callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.evidence-summary.v1")
                    put(
                        "semantic_summary",
                        buildJsonObject {
                            put("schema", "github.semantic-summary.v1")
                            put("commit_sha", "a".repeat(40))
                        }
                    )
                    put("prior_delivery_scope", "UNCONFIRMED")
                    put("previously_delivered", false)
                }
            ),
            false
        )
        val exchanges = listOf(AgentToolExchange(listOf(call), listOf(result)))
        val consumed = ToolResultDeliveryProof.qualifiedConsumed(exchanges, setOf(call.callId), ToolResultDeliveryProof.capture(listOf(result)))
        assertEquals(setOf(call.callId), consumed)
        val replay = ToolExchangeCompactor.compact(exchanges, 1024, 512, consumed)
        val body = requireNotNull(GitHubReplayRecovery.envelope(replay.single().results.single().content))
        assertEquals(JsonPrimitive(true), body["previously_delivered"])
        assertEquals(JsonPrimitive("SEMANTIC_SUMMARY"), body["prior_delivery_scope"])
    }
}
