package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubPaginationBoundary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubPullFileInventoryTest {
    private val head = "b".repeat(40)
    private val base = "a".repeat(40)

    private fun files(start: Int, end: Int) = JsonArray(
        (start until end).map { index ->
            buildJsonObject {
                put("filename", "file/$index")
                put("sha", "c".repeat(40))
                if (index == 415) {
                    put("status", "renamed")
                    put("previous_filename", "old-name")
                }
            }
        }
    )

    @Test fun terminalContextRecoversOnlyMissingPagesAtTheObservedRevisions() {
        val inventory = GitHubPullFileInventory()
        val boundary = GitHubPaginationBoundary()
        val original = buildJsonObject {
            put("action", "context")
            put("pull_number", 30)
            put("page", 4)
            put("verify_live", true)
            put("check_page", 3)
            put("include_patches", true)
        }
        fun observe(page: Int): GitHubPaginationBoundary.Assessment {
            val rows = files((page - 1) * 30, minOf(page * 30, 111))
            val coverage = inventory.observe("/repos/o/r", 30, base, head, 111, 30, page, rows, false)
            val data = buildJsonObject {
                put("head_sha", head)
                put("base_sha", base)
                put("files_included", true)
                put("files_total", 111)
                put("files_returned", rows.size)
                put("files", rows)
                put("effective_per_page", 30)
                put("changed_file_coverage", coverage)
                put("has_more", coverage.getValue("has_more"))
            }
            return boundary.assessPage(
                "github_pr",
                buildJsonObject {
                    put("action", "pr_context")
                    put("page", page)
                },
                data
            )
        }
        observe(1)
        observe(3)
        val terminal = observe(4)
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", terminal.errorCode)
        val progress = terminal.data.getValue("pagination_progress") as JsonObject
        assertEquals(JsonPrimitive(81), progress["unique_records_observed"])
        assertEquals(JsonArray(listOf(1, 3, 4).map(::JsonPrimitive)), progress["remote_pages_observed"])
        val gap = progress.getValue("first_unobserved_page")
        assertEquals(JsonPrimitive(2), gap)
        val correction = requireNotNull(GitHubPullFileInventory.recoveryArguments(original, terminal.data, gap))
        assertEquals(JsonPrimitive(2), correction["page"])
        assertEquals(JsonPrimitive(30), correction["per_page"])
        assertEquals(JsonPrimitive(30), correction["pull_number"])
        assertEquals(JsonPrimitive(head), correction["expected_head_sha"])
        assertEquals(JsonPrimitive(base), correction["expected_base_sha"])
        assertEquals(JsonPrimitive(true), correction["include_patches"])
        assertEquals(JsonPrimitive(true), correction["include_files"])
        assertEquals(JsonPrimitive(false), correction["include_checks"])
        assertEquals(JsonPrimitive(false), correction["include_health"])
        assertNull(correction["verify_live"])
        assertNull(correction["check_page"])
        val stale = JsonObject(original + ("expected_head_sha" to JsonPrimitive("d".repeat(40))))
        assertNull(GitHubPullFileInventory.recoveryArguments(stale, terminal.data, gap))
        val recovered = observe(2)
        assertNull(recovered.errorCode)
        assertEquals(JsonPrimitive(false), recovered.data["has_more"])
        assertEquals(JsonPrimitive(true), (recovered.data.getValue("changed_file_coverage") as JsonObject)["complete"])
        val otherHead = JsonObject(recovered.data + ("head_sha" to JsonPrimitive("d".repeat(40))))
        val args = buildJsonObject {
            put("action", "pr_context")
            put("page", 2)
        }
        assertEquals("GITHUB_PAGINATION_INCOMPLETE", boundary.assessPage("github_pr", args, otherHead).errorCode)
    }

    @Test fun terminalSnapshotKeepsIssuingOnlyUnseenPagesWhenSeveralAreMissing() {
        val inventory = GitHubPullFileInventory()
        fun page(number: Int) = inventory.observe("/repos/o/r", 7, base, head, 4, 1, number, files(number - 1, number), false)
        assertEquals(JsonPrimitive(1), page(4)["first_unobserved_page"])
        assertEquals(JsonPrimitive(2), page(1)["first_unobserved_page"])
        assertEquals(JsonPrimitive(3), page(2)["first_unobserved_page"])
        val complete = page(3)
        assertEquals(JsonPrimitive(true), complete["complete"])
        assertEquals(JsonPrimitive(false), complete["has_more"])
        assertNull(complete["first_unobserved_page"])
    }

    @Test fun duplicatesAcrossPagesDoNotProveCoverageAndRemoteCapHasAnExactGap() {
        val inventory = GitHubPullFileInventory()
        fun observe(page: Int, total: Int, rows: JsonArray) = inventory.observe("/repos/o/r", 7, base, head, total, 100, page, rows, false)
        observe(1, 101, files(0, 100))
        val duplicate = observe(2, 101, files(0, 1))
        assertEquals(JsonPrimitive(false), duplicate["complete"])
        assertEquals(JsonPrimitive(1), duplicate["missing"])
        assertNull(duplicate["first_unobserved_page"])
        var capped = duplicate
        for (page in 1..30) capped = observe(page, 3007, files((page - 1) * 100, page * 100))
        assertEquals(JsonPrimitive("GITHUB_PR_FILE_LIMIT"), capped["reason"])
        assertEquals(JsonPrimitive(3000), capped["observed_unique"])
        assertEquals(JsonPrimitive(7), capped["missing"])
        assertNull(capped["first_unobserved_page"])
        assertEquals(JsonPrimitive(false), capped["has_more"])
    }

    @Test fun oneTerminalPageCannotBorrowCoverageFromAnotherHeadOrAChangedPage() {
        val inventory = GitHubPullFileInventory()
        fun observe(revision: String, page: Int, index: Int, refresh: Boolean = false) =
            inventory.observe("/repos/o/r", 7, base, revision, 2, 1, page, files(index, index + 1), refresh)
        observe(head, 1, 0)
        val other = observe("d".repeat(40), 2, 1)
        assertEquals(JsonPrimitive(false), other["complete"])
        assertEquals(JsonPrimitive(1), other["missing"])
        observe(head, 1, 3)
        assertEquals(JsonPrimitive(false), observe(head, 2, 1)["complete"])
        observe(head, 1, 0, refresh = true)
        assertEquals(JsonPrimitive(true), observe(head, 2, 1)["complete"])
    }
}
