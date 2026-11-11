package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.github.GitHubLiveProcess
import dev.chungjungsoo.gptmobile.data.github.GitHubLiveStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStatusSummaryTest {
    private fun run(id: String, status: String, parent: String? = null) = GitHubLiveProcess(id, id, status, null, null, parentId = parent)

    @Test fun mismatchedRevisionCanTrackTheRunButCannotRepairTheDraft() {
        val old = "a".repeat(40)
        val head = "b".repeat(40)
        val pull = Json.parseToJsonElement("""{"number":42,"draft":true,"head":{"sha":"$head"}}""") as JsonObject
        val row = run("91", "failure").copy(headSha = old, runId = 91, attempt = 2)
        val target = GitHubStatusActionTarget.from(pull, row)
        assertFalse(target.canFix)
        assertTrue(target.canTrack)
        val arguments = target.watchArguments("owner", "repo")
        assertEquals(JsonPrimitive(old), arguments["commit_sha"])
        assertEquals(JsonPrimitive(91L), arguments["run_id"])
        assertFalse("pr_number" in arguments)
        val repair = GitHubDraftRepair("owner/repo", 42, head, "feature/old", "main", 91, 2)
        assertTrue(repair.command().contains("from $head"))
        assertTrue(repair.command().contains("open a new draft pull request into `main`"))
        assertFalse(repair.branch == repair.sourceBranch)
    }

    @Test fun expandedJobsCannotInflateWorkflowCounts() {
        val state = GitHubLiveStatus(rows = listOf(run("workflow", "failure"), run("job", "failure", "workflow"), run("other", "success")), phase = "READY", checkedAt = 1000)
        val summary = gitHubStatusSummary(state, 1000)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.passed)
        assertEquals("Checks need attention", summary.headline)
    }

    @Test fun skippedCancelledAndUnknownResultsNeverBecomePassed() {
        val state = GitHubLiveStatus(rows = listOf(run("one", "success"), run("two", "skipped"), run("three", "cancelled"), run("four", "neutral")), phase = "READY", checkedAt = 1000)
        val summary = gitHubStatusSummary(state, 1000)
        assertEquals(1, summary.passed)
        assertEquals(3, summary.other)
        assertEquals("Review check results", summary.headline)
    }

    @Test fun expiredOrFailedObservationsLoseTheCurrentStatusBadge() {
        val state = GitHubLiveStatus(rows = listOf(run("one", "in_progress")), phase = "READY", checkedAt = 1000)
        val aged = gitHubStatusSummary(state, 61_001)
        val failed = gitHubStatusSummary(state.copy(phase = "STALE"), 1001)
        listOf(aged, failed).forEach {
            assertTrue(it.stale)
            assertEquals("Saved", it.badge)
            assertEquals("Refresh needed", it.headline)
            assertEquals(1, it.running)
        }
    }

    @Test fun initialAndChangingFiltersDoNotDisplayUnmeasuredCountsAsZero() {
        listOf(GitHubLiveStatus(), GitHubLiveStatus(phase = "LOADING", checkedAt = 1000)).forEach {
            val summary = gitHubStatusSummary(it, 100_000)
            assertFalse(summary.measured)
            assertEquals("Checking", summary.badge)
        }
        val empty = gitHubStatusSummary(GitHubLiveStatus(phase = "EMPTY", checkedAt = 1000), 1000)
        assertTrue(empty.measured)
        assertEquals(0, empty.passed)
        assertEquals("No checks to show", empty.headline)
    }

    @Test fun accessFailureWithoutAnObservationDoesNotClaimSavedData() {
        val summary = gitHubStatusSummary(GitHubLiveStatus(phase = "AUTHORIZATION"), 1000)
        assertFalse(summary.measured)
        assertEquals("Unavailable", summary.badge)
        assertEquals("Reconnect GitHub", summary.headline)
    }
}
