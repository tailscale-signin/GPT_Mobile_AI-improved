package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReviewPayloadsTest {
    @Test fun identicalResultsSharePayloadButKeepEveryLiveCachedAndSuppressedReceipt() {
        val evidence = GitHubReviewEvidence()
        val source = buildJsonObject { put("workflows", "large workflow payload".repeat(700)) }
        listOf("live", "cached", "suppressed").forEach { id ->
            evidence.observe(
                ProviderEvent.ToolCall(id, "github_ci", buildJsonObject { put("action", "workflows") }),
                AgentToolResult(
                    id,
                    ToolResultContent.Json(source),
                    false,
                    sharedResult = id != "live",
                    dispatched = id == "live",
                    executionReceipt = buildJsonObject {
                        put("source_call_id", "live")
                        put("phase", if (id == "live") "EXECUTED" else "LOCAL_OR_REPLAYED")
                    }
                )
            )
        }
        val original = evidence.snapshot()
        val projected = GitHubReviewPayloads.canonicalize(original)
        assertEquals(3, original.size)
        assertEquals(1, projected.size)
        assertTrue(projected.single().length < original.sumOf { it.length } / 2)
        val shared = Json.parseToJsonElement(projected.single()).jsonObject
        assertEquals(source, shared["result"])
        val receipts = shared.getValue("receipts").jsonArray.map { it.jsonObject }
        assertEquals(listOf("live", "cached", "suppressed"), receipts.map { (it.getValue("call_id") as JsonPrimitive).content })
        assertEquals(listOf("LIVE", "CACHED", "CACHED"), receipts.map { (it.getValue("evidence_origin") as JsonPrimitive).content })
        assertEquals(listOf(true, false, false).map(::JsonPrimitive), receipts.map { it["dispatched"] })
        assertTrue(receipts.all { it["execution"] != null && it["result_sha256"] != null })
        assertEquals(original, evidence.snapshot())
    }

    @Test fun canonicalObjectOrderingDoesNotCollapseDifferentResultsOrChangedOutcomes() {
        fun receipt(id: String, result: JsonObject, failed: Boolean = false) = buildJsonObject {
            put("call_id", id)
            put("result", result)
            put("failed", failed)
            put("original_result_sha256", "a".repeat(64))
        }.toString()
        val first = Json.parseToJsonElement("""{"status":"completed","conclusion":"failure"}""").jsonObject
        val reordered = Json.parseToJsonElement("""{"conclusion":"failure","status":"completed"}""").jsonObject
        val different = JsonObject(first + ("conclusion" to JsonPrimitive("success")))
        val projected = GitHubReviewPayloads.canonicalize(listOf(receipt("a", first), receipt("b", reordered, true), receipt("c", different)))
        assertEquals(2, projected.size)
        val retained = Json.parseToJsonElement(projected.first()).jsonObject.getValue("receipts") as JsonArray
        assertEquals(listOf(JsonPrimitive(false), JsonPrimitive(true)), retained.map { it.jsonObject["failed"] })
        assertTrue(projected.last().contains("success"))
    }
}
