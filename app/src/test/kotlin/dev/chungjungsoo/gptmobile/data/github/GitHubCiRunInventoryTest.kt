package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCiRunInventoryTest {
    private val head = "a".repeat(40)
    private val tools = listOf(GitHubOperationTool.definition("github_ci"))
    private fun page(number: Int, ids: List<Int>, more: Boolean, at: Long, workflow: String? = null) = buildJsonObject {
        put("tool", "github_ci")
        put("trusted", true)
        put("success", true)
        put("verification_eligible", true)
        put("repository", "o/r")
        put("binding", "task")
        put("observed_at_ms", at)
        put(
            "arguments",
            buildJsonObject {
                put("action", "runs")
                put("commit_sha", head)
                put("per_page", 2)
                put("page", number)
                workflow?.let { put("workflow_id", it) }
            }
        )
        put(
            "result",
            buildJsonObject {
                put("page", number)
                put("returned", ids.size)
                put("total_count", 3)
                put("has_more", more)
                put(
                    "workflow_runs",
                    JsonArray(
                        ids.map { id ->
                            buildJsonObject {
                                put("id", id)
                                put("head_sha", head)
                                put("run_attempt", 1)
                                put("status", "completed")
                                put("conclusion", "success")
                            }
                        }
                    )
                )
            }
        )
    }

    @Test fun pageTwoIsRequiredAndACompleteSameQueryStopsCollection() {
        val receipts = linkedMapOf("one" to page(1, listOf(1, 2), true, 1000))
        val partial = requireNotNull(GitHubCiRunInventory.snapshot(receipts, head, 3000))
        assertFalse(partial.complete)
        assertEquals(3, partial.total)
        val next = requireNotNull(GitHubCiRunInventory.nextCall(tools, receipts, head, 3000))
        assertEquals(JsonPrimitive(2), next.arguments["page"])
        assertEquals(JsonPrimitive(head), next.arguments["commit_sha"])
        receipts["two"] = page(2, listOf(3), false, 2000)
        assertTrue(requireNotNull(GitHubCiRunInventory.snapshot(receipts, head, 3000)).complete)
        assertNull(GitHubCiRunInventory.nextCall(tools, receipts, head, 3000))
    }

    @Test fun narrowWorkflowAndPreviousTraversalPagesCannotCompleteTheCurrentScope() {
        val receipts = linkedMapOf(
            "one" to page(1, listOf(1, 2), true, 1000),
            "two" to page(2, listOf(3), false, 2000),
            "refresh" to page(1, listOf(1, 2), true, 4000),
            "narrow" to page(2, listOf(3), false, 4500, "release.yml")
        )
        val result = requireNotNull(GitHubCiRunInventory.snapshot(receipts, head, 5000))
        assertFalse(result.complete)
        assertEquals(setOf("refresh"), result.receipts.keys)
        assertEquals(2, result.nextPage)
    }

    @Test fun aSameMillisecondRefreshCannotBorrowThePreviousTrailingPage() {
        val receipts = linkedMapOf(
            "one" to page(1, listOf(1, 2), true, 1000),
            "two" to page(2, listOf(3), false, 1000),
            "refresh" to page(1, listOf(1, 2), true, 1000),
            "narrow" to page(1, listOf(2), false, 1000, "release.yml")
        )
        val current = requireNotNull(GitHubCiRunInventory.snapshot(receipts, head, 2000))
        assertFalse(current.complete)
        assertEquals(setOf("refresh"), current.receipts.keys)
        assertEquals(setOf("refresh", "narrow"), GitHubCiRunInventory.currentObservations(receipts, current).keys)
    }

    @Test fun staleCompleteInventoryRefreshesItsOriginalScopeAfterANewerNarrowResult() {
        val at = GitHubEvidencePolicy.MAX_AGE_MS + 3000
        val receipts = linkedMapOf(
            "one" to page(1, listOf(1, 2), true, 1000),
            "two" to page(2, listOf(3), false, 2000),
            "narrow" to page(1, listOf(2), false, at - 1, "release.yml")
        )
        val current = requireNotNull(GitHubCiRunInventory.snapshot(receipts, head, at))
        assertTrue(current.complete)
        assertFalse(current.fresh)
        assertEquals(setOf("one", "two"), current.receipts.keys)
        val refresh = requireNotNull(GitHubCiRunInventory.nextCall(tools, receipts, head, at))
        assertEquals(JsonPrimitive(1), refresh.arguments["page"])
        assertEquals(JsonPrimitive(2), refresh.arguments["per_page"])
        assertEquals(JsonPrimitive(head), refresh.arguments["commit_sha"])
        assertNull(refresh.arguments["workflow_id"])
    }

    @Test fun anUnchangedFailedRefreshIsNotProposedAgain() {
        val failed = JsonObject(page(1, listOf(1, 2), true, 1000) + ("success" to JsonPrimitive(false)))
        val first = requireNotNull(GitHubCiRunInventory.nextCall(tools, emptyMap(), head, 3000))
        assertNull(GitHubCiRunInventory.nextCall(tools, mapOf("failed" to JsonObject(failed + ("call_id" to JsonPrimitive(first.callId)))), head, 3000))
    }

    @Test fun canonicalInventoryMetadataSurvivesMissingReviewRowsButRejectsContradictions() {
        val source = page(1, listOf(1, 2, 3), false, 1000)
        val original = JsonObject(source + ("arguments" to JsonObject((source.getValue("arguments") as JsonObject) + ("per_page" to JsonPrimitive(3)))))
        val inventory = requireNotNull(GitHubInventoryObservation.capture(original.getValue("result")))
        val projected = JsonObject(
            original + mapOf(
                "result" to buildJsonObject { put("has_more", false) },
                "inventory_observation" to inventory,
                "review_projection" to JsonPrimitive("EXACT_VALUES_WITH_DURABLE_ORIGINAL")
            )
        )
        val recovered = requireNotNull(GitHubCiRunInventory.snapshot(mapOf("projected" to projected), head, 3000))
        assertTrue(recovered.complete)
        assertEquals(3, recovered.total)
        val contradictory = JsonObject(projected + ("result" to buildJsonObject { put("returned", 2) }))
        assertNull(GitHubCiRunInventory.pageData(contradictory))
    }
}
