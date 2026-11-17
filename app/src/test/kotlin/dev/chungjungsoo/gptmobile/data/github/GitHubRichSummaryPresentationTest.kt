package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRichSummaryPresentationTest {
    private fun sample(vararg steps: String) = GitHubProgressSnapshot(
        revision = 1,
        task = "Review CI",
        completed = 1,
        failed = 0,
        activity = "Inspecting files",
        recentSteps = steps.toList(),
        next = "Inspect the failed step"
    )

    @Test fun sourceObservationProducesHeadingAndShortBulletsWithoutAnInventedFix() {
        val output = GitHubRichSummaryPresentation.render(sample("The source read for Reader.kt returned a result for inspection."))
        assertTrue(output.startsWith("### Source inspection"))
        assertTrue(output.contains("- **Observed:**"))
        assertTrue(output.contains("Reader.kt"))
        assertTrue(output.contains("- **Next:**"))
        assertFalse(output.contains("All tests passed"))
        assertFalse(output.contains("| Check state |"))
        assertTrue(GitHubRichSummaryPresentation.isRich(output))
    }

    @Test fun theSameFactsDoNotChangeWhenOnlyTheClockOrNextStepChanges() {
        val first = sample("The workflow definition returned a result for inspection.")
        val later = first.copy(revision = 19, pending = 3, activity = "Retrying", next = "Review another check")
        assertEquals(GitHubRichSummaryPresentation.evidenceKey(first), GitHubRichSummaryPresentation.evidenceKey(later))
        assertTrue(GitHubRichSummaryPresentation.evidenceKey(first).isNotBlank())
        assertFalse(GitHubRichSummaryPresentation.evidenceKey(first) == GitHubRichSummaryPresentation.evidenceKey(later.copy(recentSteps = first.recentSteps + "Job 7 failed.")))
    }

    @Test fun originalObservedCheckCountsBuildTableChartAndCsvFromTheSameNumbers() {
        val source = "Recorded checks for Job 7: 15 successful, 18 skipped, 0 in progress, 2 failed (complete captured list). These are existing results, not a new validation run."
        val output = GitHubRichSummaryPresentation.render(sample(source))
        assertTrue(output.startsWith("### Recorded check results"))
        assertTrue(output.contains("| Check state | Count |"))
        assertTrue(output.contains("| Successful | 15 |"))
        assertTrue(output.contains("| Skipped | 18 |"))
        assertTrue(output.contains("| Failed | 2 |"))
        val series = GitHubRichSummaryPresentation.chartData(output)
        assertEquals(4, series.size)
        assertEquals(15, series[0].count)
        assertEquals(18, series[1].count)
        assertEquals(2, series[3].count)
        assertEquals("Check state,Count\r\nSuccessful,15\r\nSkipped,18\r\nIn progress,0\r\nFailed,2\r\n", GitHubRichSummaryPresentation.csv(output))
    }

    @Test fun singleNonzeroCategoryOrIncompleteFactsDoNotCreateAMisleadingChart() {
        val allSuccessful = GitHubRichSummaryPresentation.render(
            sample("Recorded checks: 9 successful, 0 skipped, 0 in progress, 0 failed (complete captured list).")
        )
        assertTrue(allSuccessful.contains("| Successful | 9 |"))
        assertTrue(GitHubRichSummaryPresentation.chartData(allSuccessful).isEmpty())
        assertTrue(GitHubRichSummaryPresentation.csv(allSuccessful)?.contains("Successful,9") == true)
        val ungrounded = GitHubRichSummaryPresentation.render(
            sample("Recorded checks: 9 successful, 0 skipped, 0 in progress, 0 failed.")
        )
        assertFalse(ungrounded.contains("| Check state |"))
        assertTrue(GitHubRichSummaryPresentation.chartData(ungrounded).isEmpty())
        assertNull(GitHubRichSummaryPresentation.csv(ungrounded))
    }

    @Test fun errorsStayVisibleEvenWhenTheNewestResultIsNotAFailure() {
        val output = GitHubRichSummaryPresentation.render(sample(
            "The source read for Failure.kt failed.",
            "The source read for Latest.kt returned a result for inspection."
        ))
        assertTrue(output.contains("Failure.kt failed"))
        assertTrue(output.contains("Latest.kt"))
        assertTrue(output.contains("Attention"))
        assertFalse(output.contains("successfully fixed"))
    }

    @Test fun duplicateObservationsAndEmptySnapshotsStayCompact() {
        val duplicated = sample(
            "The source read for Reader.kt returned a result.",
            "The source read for Reader.kt returned a result.",
            "The source read for Reader.kt returned a result."
        )
        val output = GitHubRichSummaryPresentation.render(duplicated)
        assertEquals(1, Regex("Reader.kt").findAll(output).count())
        assertEquals("", GitHubRichSummaryPresentation.render(sample()))
    }

    @Test fun clockOnlyChangesCannotGenerateANewActivitySummary() {
        var now = 0L
        val progress = GitHubChatProgress("Review CI") { now }
        progress.tick()
        progress.observe(
            ApiState.GatewayProgressChanged(
                GatewayProgress(event = "tool_completed", toolName = "github_read", toolCallId = "first")
            )
        )
        now = 45_000L
        val first = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertTrue(first.text.startsWith("### "))
        progress.observe(ApiState.ProgressCheckpoint("Reviewing another file"))
        now = 90_000L
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })
    }
}
