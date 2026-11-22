package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.github.GitHubComparisonCoverage
import dev.chungjungsoo.gptmobile.data.github.GitHubComparisonPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubComparisonPaginationTest {
    private val base = "a".repeat(40)
    private val head = "b".repeat(40)

    private fun arguments(page: Int, size: Int = 100, refresh: Boolean = false) = buildJsonObject {
        put("action", "changed_since")
        put("owner", "owner")
        put("repo", "repository")
        put("base_sha", base)
        put("commit_sha", head)
        put("page", page)
        put("per_page", size)
        if (refresh) put("verify_live", true)
    }

    private fun result(page: Int, size: Int = 100, total: Int = 327): JsonObject {
        val window = GitHubComparisonPage.window(arguments(page, size), total)
        return buildJsonObject {
            put("base_sha", base)
            put("head_sha", head)
            put("page", page)
            put("effective_per_page", window.size)
            put("total_pages", window.totalPages)
            put("files_total", total)
            put("tree_complete", true)
            put("has_more", window.hasMore)
            put("files", JsonArray((window.from until window.until).map { buildJsonObject { put("filename", "src/File$it.kt") } }))
        }
    }

    private fun permutations(values: List<Int>): List<List<Int>> = if (values.isEmpty()) listOf(emptyList()) else values.flatMap { first -> permutations(values - first).map { listOf(first) + it } }

    @Test fun allResponseOrdersCompleteWithoutTransientFailuresOrTerminalRereads() {
        for (order in permutations(listOf(1, 2, 3, 4))) {
            val boundary = GitHubPaginationBoundary()
            val pending = (1..4).associateWith { page ->
                val dispatch = boundary.preparePage("github_context", arguments(page))
                assertEquals(null, dispatch.completedPage)
                dispatch.observation
            }
            for ((index, page) in order.withIndex()) {
                val observed = boundary.assessPage("github_context", arguments(page), result(page), pending[page])
                assertEquals("arrival order $order", null, observed.errorCode)
                val progress = observed.data.getValue("pagination_progress").jsonObject
                assertEquals("arrival order $order", JsonPrimitive(index == order.lastIndex), progress["complete"])
                if (index == order.lastIndex) {
                    assertEquals(JsonPrimitive(327), progress["unique_records_observed"])
                    assertEquals(JsonPrimitive(4), progress["terminal_page"])
                    assertEquals(JsonPrimitive(true), progress["contiguous_from_first_page"])
                    assertEquals(JsonPrimitive(false), observed.data["has_more"])
                    assertFalse("continuation" in observed.data)
                }
            }
            assertEquals(null, boundary.completionError("github_context", arguments(5)))
            assertEquals(4, boundary.completedPage("github_context", arguments(5)))
        }
    }

    @Test fun extraEmptyPageCannotMoveTheTerminalBoundaryOrLoseCompletedCoverage() {
        for (order in permutations(listOf(1, 2, 3, 4, 5))) {
            val boundary = GitHubPaginationBoundary()
            val pending = (1..5).associateWith { page -> boundary.preparePage("github_context", arguments(page)).observation }
            var last: GitHubPaginationBoundary.Assessment? = null
            for (page in order) {
                last = boundary.assessPage("github_context", arguments(page), result(page), pending[page])
                assertEquals("arrival order $order", null, last.errorCode)
            }
            val progress = requireNotNull(last).data.getValue("pagination_progress").jsonObject
            assertEquals(JsonPrimitive(true), progress["complete"])
            assertEquals(JsonPrimitive(4), progress["terminal_page"])
            assertEquals(JsonPrimitive(327), progress["unique_records_observed"])
            assertEquals(null, boundary.completionError("github_context", arguments(6)))
        }
    }

    @Test fun aLateUnscheduledGapClearsThePriorIncompleteTerminalState() {
        val boundary = GitHubPaginationBoundary()
        boundary.assessPage("github_context", arguments(1), result(1))
        boundary.assessPage("github_context", arguments(2), result(2))
        val missing = boundary.assessPage("github_context", arguments(4), result(4))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", missing.errorCode)
        assertEquals(JsonPrimitive(3), missing.data.getValue("pagination_progress").jsonObject["first_unobserved_page"])
        val repaired = boundary.assessPage("github_context", arguments(3), result(3))
        assertEquals(null, repaired.errorCode)
        assertEquals(JsonPrimitive(true), repaired.data.getValue("pagination_progress").jsonObject["complete"])
        assertEquals(null, boundary.completionError("github_context", arguments(5)))
    }

    @Test fun duplicatePinnedFirstPagePreservesCompletionAndExplicitRefreshRevokesOldRequests() {
        val boundary = GitHubPaginationBoundary()
        for (page in 1..4) boundary.assessPage("github_context", arguments(page), result(page))
        assertEquals(null, boundary.completedPage("github_context", arguments(1)))
        val repeated = boundary.assessPage("github_context", arguments(1), result(1), boundary.beginPage("github_context", arguments(1)))
        assertEquals(JsonPrimitive(true), repeated.data.getValue("pagination_progress").jsonObject["complete"])
        val old = boundary.beginPage("github_context", arguments(2))
        val fresh = arguments(1, refresh = true)
        boundary.completedPage("github_context", fresh)
        val refreshed = boundary.assessPage("github_context", fresh, result(1), boundary.beginPage("github_context", fresh))
        assertEquals(JsonPrimitive(false), refreshed.data.getValue("pagination_progress").jsonObject["complete"])
        assertEquals(JsonPrimitive(100), refreshed.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        val stale = boundary.assessPage("github_context", arguments(2), result(2), old)
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", stale.errorCode)
        assertEquals(JsonPrimitive("SUPERSEDED_PAGINATION_GENERATION"), stale.data["reason"])
        assertFalse("files" in stale.data)
        for (page in 2..4) {
            val observed = boundary.assessPage("github_context", arguments(page), result(page), boundary.beginPage("github_context", arguments(page)))
            assertEquals(JsonPrimitive(page == 4), observed.data.getValue("pagination_progress").jsonObject["complete"])
        }
    }

    @Test fun cancelledGapDoesNotPretendThatItsResponseIsStillInFlight() {
        val boundary = GitHubPaginationBoundary()
        val pending = (1..4).associateWith { boundary.beginPage("github_context", arguments(it)) }
        boundary.assessPage("github_context", arguments(1), result(1), pending[1])
        boundary.assessPage("github_context", arguments(2), result(2), pending[2])
        boundary.cancelPage(pending[3])
        val observed = boundary.assessPage("github_context", arguments(4), result(4), pending[4])
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", observed.errorCode)
        assertEquals(JsonPrimitive(3), observed.data.getValue("pagination_progress").jsonObject["first_unobserved_page"])
    }

    @Test fun pageSizesAndImmutablePairsCannotBorrowAnotherTraversal() {
        val boundary = GitHubPaginationBoundary()
        for (page in 1..4) boundary.assessPage("github_context", arguments(page), result(page))
        val small = boundary.assessPage("github_context", arguments(1, 50), result(1, 50))
        assertEquals(JsonPrimitive(50), small.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        assertEquals(JsonPrimitive(false), small.data.getValue("pagination_progress").jsonObject["complete"])
        for (page in 2..7) {
            val observed = boundary.assessPage("github_context", arguments(page, 50), result(page, 50))
            assertEquals(JsonPrimitive(page == 7), observed.data.getValue("pagination_progress").jsonObject["complete"])
        }
        val foreign = JsonObject(arguments(4) + ("commit_sha" to JsonPrimitive("c".repeat(40))))
        val foreignResult = JsonObject(result(4) + ("head_sha" to JsonPrimitive("c".repeat(40))))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", boundary.assessPage("github_context", foreign, foreignResult).errorCode)
    }

    @Test fun freshDispatchInvalidatesOlderRefreshAndResolvedAliases() {
        val boundary = GitHubPaginationBoundary()
        val symbolic = JsonObject(arguments(1) + ("base_sha" to JsonPrimitive("main")))
        val first = boundary.preparePage("github_context", symbolic).observation
        boundary.assessPage("github_context", symbolic, result(1), first)
        val oldContinuation = boundary.preparePage("github_context", arguments(2)).observation
        val refresh = arguments(1, refresh = true)
        val olderRefresh = boundary.preparePage("github_context", refresh).observation
        val fresh = boundary.preparePage("github_context", refresh).observation
        for ((args, token) in listOf(arguments(2) to oldContinuation, refresh to olderRefresh)) {
            val stale = boundary.assessPage("github_context", args, result(if (args == refresh) 1 else 2), token)
            assertEquals(JsonPrimitive("SUPERSEDED_PAGINATION_GENERATION"), stale.data["reason"])
            assertFalse("files" in stale.data)
        }
        val observed = boundary.assessPage("github_context", refresh, result(1), fresh)
        assertEquals(null, observed.errorCode)
        assertEquals(JsonPrimitive(100), observed.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
        val alias = boundary.preparePage("github_context", symbolic).observation
        assertEquals(JsonPrimitive("SUPERSEDED_PAGINATION_GENERATION"), boundary.assessPage("github_context", refresh, result(1), fresh).data["reason"])
        assertEquals(null, boundary.assessPage("github_context", symbolic, result(1), alias).errorCode)
    }

    @Test fun expiredPendingResponsesCannotJoinFreshCoverage() {
        var time = 0L
        val boundary = GitHubPaginationBoundary { time }
        val old = boundary.preparePage("github_context", arguments(2)).observation
        time = 300_000_000_001L
        val fresh = boundary.preparePage("github_context", arguments(1)).observation
        assertEquals(JsonPrimitive("SUPERSEDED_PAGINATION_GENERATION"), boundary.assessPage("github_context", arguments(2), result(2), old).data["reason"])
        val observed = boundary.assessPage("github_context", arguments(1), result(1), fresh)
        assertEquals(null, observed.errorCode)
        assertEquals(JsonPrimitive(100), observed.data.getValue("pagination_progress").jsonObject["unique_records_observed"])
    }

    @Test fun retainedCoverageAcceptsLateAggregateCompletionWithoutRelabelingFailedPages() {
        for (order in permutations(listOf(1, 2, 3, 4))) {
            val boundary = GitHubPaginationBoundary()
            val receipts = linkedMapOf<String, JsonObject>()
            for (page in order) {
                val args = arguments(page)
                val publicArgs = JsonObject(args + ("action" to JsonPrimitive("changes")))
                val withContinuation = JsonObject(
                    result(page) + if (page < 4) {
                        mapOf(
                            "continuation_call" to buildJsonObject {
                                put("tool", "github_context")
                                put("arguments", JsonObject(arguments(page + 1) + ("action" to JsonPrimitive("changes"))))
                            }
                        )
                    } else {
                        emptyMap()
                    }
                )
                val assessment = boundary.assessPage("github_context", args, withContinuation)
                receipts["p$page"] = buildJsonObject {
                    put("binding", "task")
                    put("repository", "owner/repository")
                    put("tool", "github_context")
                    put("trusted", true)
                    put("success", assessment.errorCode == null)
                    assessment.errorCode?.let { put("error_code", it) }
                    put("arguments", publicArgs)
                    put("result", assessment.data)
                }
            }
            val coverage = requireNotNull(GitHubComparisonCoverage.observe(receipts) { (it["arguments"] as? JsonObject)?.get("action") == JsonPrimitive("changes") })
            assertTrue("arrival order $order", coverage.complete)
            assertTrue("arrival order $order", coverage.pending.isEmpty())
            assertEquals(receipts, coverage.receipts)
        }
    }

    @Test fun aTruncatedEarlierTreeAndRowsBeyondAnObservedEndRemainUnqualified() {
        val boundary = GitHubPaginationBoundary()
        boundary.assessPage("github_context", arguments(1), JsonObject(result(1) + ("tree_complete" to JsonPrimitive(false))))
        for (page in 2..3) boundary.assessPage("github_context", arguments(page), result(page))
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", boundary.assessPage("github_context", arguments(4), result(4)).errorCode)
        val extra = JsonObject(result(5) + ("files" to JsonArray(listOf(buildJsonObject { put("filename", "unexpected.kt") }))))
        val changed = boundary.assessPage("github_context", arguments(5), extra)
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", changed.errorCode)
        assertEquals(JsonPrimitive(true), changed.data.getValue("pagination_progress").jsonObject["upstream_changed_during_traversal"])
        assertTrue(changed.data["complete"] == JsonPrimitive(false))
    }
}
