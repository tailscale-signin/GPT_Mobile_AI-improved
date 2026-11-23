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

    @Test fun proofBindsOnlyTheDeliveredBodyAndRejectsFailedOrDifferentProjections() {
        val source = AgentToolResult("page", ToolResultContent.Text("delivered"), false, retainedContent = ToolResultContent.Text("unread original"))
        val proof = ToolResultDeliveryProof.capture(listOf(source))
        assertEquals(1, proof.size)
        assertTrue(ToolResultDeliveryProof.matches(source, proof))
        assertFalse(ToolResultDeliveryProof.matches(source.copy(content = requireNotNull(source.retainedContent)), proof))
        assertFalse(ToolResultDeliveryProof.matches(source.copy(isError = true), proof))
        assertTrue(ToolResultDeliveryProof.capture(listOf(source.copy(isError = true))).isEmpty())
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
