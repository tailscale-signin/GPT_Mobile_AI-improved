package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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

    @Test fun duplicatesAcrossPagesDoNotProveCoverageAndRemoteCapHasAnExactGap() {
        val inventory = GitHubPullFileInventory()
        fun observe(page: Int, total: Int, rows: JsonArray) = inventory.observe("/repos/o/r", 7, base, head, total, 100, page, rows, false)
        observe(1, 101, files(0, 100))
        val duplicate = observe(2, 101, files(0, 1))
        assertEquals(JsonPrimitive(false), duplicate["complete"])
        assertEquals(JsonPrimitive(1), duplicate["missing"])
        var capped = duplicate
        for (page in 1..30) capped = observe(page, 3007, files((page - 1) * 100, page * 100))
        assertEquals(JsonPrimitive("GITHUB_PR_FILE_LIMIT"), capped["reason"])
        assertEquals(JsonPrimitive(3000), capped["observed_unique"])
        assertEquals(JsonPrimitive(7), capped["missing"])
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
