package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubSourceReadCoverageTest {
    private fun identity(total: Int = 217) = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put("commit_sha", "a".repeat(40))
        put("blob_sha", "b".repeat(40))
        put("path", "src/Main.kt")
        put("total_lines", total)
    }

    private fun range(start: Int, end: Int, complete: Boolean = true) = buildJsonObject {
        put("start_line", start)
        put("end_line", end)
        put("range_complete", complete)
    }

    @Test fun fiveAdjacentSubrangesCertifyCompleteCaptureWithoutARepeatedWholeFileRead() {
        val items = listOf(range(1, 50), range(51, 100), range(101, 150), range(151, 200), range(201, 217))
        val coverage = GitHubSourceReadCoverage.merge(null, identity(), "first", items)
        assertEquals(JsonPrimitive(true), coverage["complete_file"])
        assertEquals(JsonPrimitive(false), coverage["read_required"])
        assertEquals(JsonPrimitive(false), coverage["already_read"])
        assertEquals(JsonPrimitive(217L), coverage["captured_lines"])
        assertEquals(JsonArray(emptyList()), coverage["unread_ranges"])
        assertEquals(1, coverage.getValue("captured_ranges").jsonArray.size)
        val repeated = GitHubSourceReadCoverage.merge(coverage, identity(), "second", listOf(range(1, 217)))
        assertEquals(JsonPrimitive(true), repeated["already_read"])
        assertEquals(JsonPrimitive(false), repeated["output_pages_verified"])
        assertEquals(JsonArray(listOf(JsonPrimitive("first"), JsonPrimitive("second"))), repeated["evidence_ids"])
    }

    @Test fun overlapsAcrossCallsDoNotDoubleCountAndGapsRemainExplicit() {
        val first = GitHubSourceReadCoverage.merge(null, identity(), "first", listOf(range(1, 80), range(120, 217)))
        val second = GitHubSourceReadCoverage.merge(first, identity(), "second", listOf(range(60, 100)))
        assertEquals(JsonPrimitive(false), second["complete_file"])
        assertEquals(JsonPrimitive(198L), second["captured_lines"])
        assertEquals(JsonObject(range(101, 119) - "range_complete"), second.getValue("unread_ranges").jsonArray.single())
        val final = GitHubSourceReadCoverage.merge(second, identity(), "third", listOf(range(101, 119)))
        assertEquals(JsonPrimitive(true), final["complete_file"])
        assertEquals(JsonPrimitive(217L), final["captured_lines"])
    }

    @Test fun byteTruncationOrOffsetFragmentsNeverClaimCompleteLineRanges() {
        val partial = GitHubSourceReadCoverage.merge(null, identity(5), "partial", listOf(range(1, 5, complete = false)))
        assertEquals(JsonPrimitive(false), partial["complete_file"])
        assertEquals(JsonPrimitive(0L), partial["captured_lines"])
        assertEquals(JsonObject(range(1, 5) - "range_complete"), partial.getValue("unread_ranges").jsonArray.single())
    }

    @Test fun repositoryCommitBlobAndPathChangesResetTheCoverage() {
        val all = GitHubSourceReadCoverage.merge(null, identity(), "first", listOf(range(1, 217)))
        for ((key, value) in listOf(
            "owner" to JsonPrimitive("another-owner"),
            "repo" to JsonPrimitive("another-repo"),
            "commit_sha" to JsonPrimitive("c".repeat(40)),
            "blob_sha" to JsonPrimitive("d".repeat(40)),
            "path" to JsonPrimitive("test/Main.kt")
        )) {
            val changed = GitHubSourceReadCoverage.merge(all, JsonObject(identity() + (key to value)), "changed", listOf(range(1, 20)))
            assertEquals(key, JsonPrimitive(false), changed["complete_file"])
            assertEquals(key, JsonPrimitive(false), changed["already_read"])
            assertEquals(JsonPrimitive(20L), changed["captured_lines"])
            assertEquals(JsonArray(listOf(JsonPrimitive("changed"))), changed["evidence_ids"])
        }
    }

    @Test fun invalidRangesAndBoundedGapsCannotCertifyCompleteCoverage() {
        val invalid = GitHubSourceReadCoverage.merge(null, identity(5), "invalid", listOf(range(-1, 5), range(1, 6), range(4, 3)))
        assertEquals(JsonPrimitive(0L), invalid["captured_lines"])
        val sparse = GitHubSourceReadCoverage.merge(null, identity(400), "many", (1..399 step 2).map { range(it, it) })
        assertEquals(JsonPrimitive(true), sparse["coverage_bounded"])
        assertEquals(JsonPrimitive(false), sparse["complete_file"])
        assertEquals(128, sparse.getValue("captured_ranges").jsonArray.size)
        assertFalse(sparse.getValue("unread_ranges").jsonArray.isEmpty())
    }

    @Test fun emptySourceIsCompleteOnlyAfterACompleteEmptyRead() {
        val absent = GitHubSourceReadCoverage.merge(null, identity(0), "none", emptyList())
        assertEquals(JsonPrimitive(false), absent["complete_file"])
        val captured = GitHubSourceReadCoverage.merge(absent, identity(0), "empty", listOf(JsonObject(range(1, 0) + ("complete_file" to JsonPrimitive(true)))))
        assertEquals(JsonPrimitive(true), captured["complete_file"])
        assertEquals(JsonPrimitive(false), captured["already_read"])
    }
}
