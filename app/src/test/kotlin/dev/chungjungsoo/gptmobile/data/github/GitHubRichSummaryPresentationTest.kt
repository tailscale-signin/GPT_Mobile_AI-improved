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

    @Test fun incompleteRecordedChecksKeepJobScopeCoverageAndNextAction() {
        val source = "Recorded checks for Job 7: 2 successful, 1 skipped, 0 in progress, 4 failed (incomplete captured list)."
        val result = GitHubRichSummaryPresentation.render(sample(source))
        assertTrue(result.startsWith("### Partial recorded check results"))
        assertTrue(result.contains("**Source:** Job 7"))
        assertTrue(result.contains("**Coverage:** The captured check list is incomplete"))
        assertTrue(result.contains("**Next:**"))
        assertTrue(result.contains("| Failed | 4 |"))
        assertEquals(4, GitHubRichSummaryPresentation.chartData(result).size)
        assertTrue(GitHubRichSummaryPresentation.csv(result)?.contains("Failed,4") == true)
    }

    @Test fun longObservationsKeepCompleteTableAndNextStepWithoutSplittingRows() {
        val longObservation = "The source read for Reader.kt returned a result for inspection. " + "Review the source. ".repeat(24)
        val result = GitHubRichSummaryPresentation.render(
            sample(
                longObservation,
                "The source read for Second.kt returned a result for inspection.",
                "Recorded checks for Job 7: 12 successful, 1 skipped, 0 in progress, 2 failed (complete captured list)."
            )
        )
        assertTrue(result.length <= 1600)
        assertTrue(result.contains("**Next:**"))
        assertTrue(result.endsWith("| Failed | 2 |"))
        assertEquals(4, GitHubRichSummaryPresentation.chartData(result).size)
    }

    @Test fun malformedSavedCheckTablesCannotBeChartedOrExported() {
        val canonical = GitHubRichSummaryPresentation.render(
            sample("Recorded checks: 3 successful, 2 skipped, 0 in progress, 1 failed (complete captured list).")
        )
        val duplicate = canonical.replace("| Failed | 1 |", "| Successful | 1 |")
        val missingState = canonical.replace("| Failed | 1 |", "| Other completed | 1 |")
        val extra = canonical + "\n| Failed | 1 |"
        listOf(duplicate, missingState, extra).forEach { invalid ->
            assertTrue(GitHubRichSummaryPresentation.chartData(invalid).isEmpty())
            assertNull(GitHubRichSummaryPresentation.csv(invalid))
        }
        assertEquals(4, GitHubRichSummaryPresentation.chartData(canonical).size)
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
        val output = GitHubRichSummaryPresentation.render(
            sample(
                "The source read for Failure.kt failed.",
                "The source read for Latest.kt returned a result for inspection."
            )
        )
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

    @Test fun alternatingRepeatedSourceReadsDoNotCreateRepeatedInsightCards() {
        var now = 0L
        val progress = GitHubChatProgress("Inspect sources") { now }
        progress.tick()
        fun read(path: String, id: String, failed: Boolean = false) {
            val args = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"path":"$path"}"""
            ) as kotlinx.serialization.json.JsonObject
            progress.observe(
                ApiState.GatewayProgressChanged(
                    GatewayProgress(
                        event = if (failed) "tool_failed" else "tool_completed",
                        toolName = "github_read",
                        toolCallId = id,
                        toolArgs = args
                    )
                )
            )
        }

        read("src/Alpha.kt", "alpha-1")
        now = 45_000L
        assertEquals(1, progress.tick().filterIsInstance<ApiState.ActivitySummary>().size)

        read("src/Beta.kt", "beta-1")
        now = 90_000L
        assertEquals(1, progress.tick().filterIsInstance<ApiState.ActivitySummary>().size)

        read("src/Alpha.kt", "alpha-2")
        now = 135_000L
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })

        read("src/Alpha.kt", "alpha-3", failed = true)
        now = 180_000L
        val newest = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertTrue(newest.text.contains("failed"))
    }

    @Test fun selectedNarrationDoesNotSuppressNewFactualEvidenceWhenNarratorIsUnavailable() {
        var now = 0L
        val progress = GitHubChatProgress("Inspect CI") { now }
        progress.tick()
        progress.observe(
            ApiState.ActivitySummary(
                "The selected model has read the requested workflow definition. Its current observation is limited to the source; no validation run has been verified.",
                modelAuthored = true
            )
        )
        now = 45_000L
        assertTrue(progress.tick().filterIsInstance<ApiState.ActivitySummary>().single().modelAuthored)

        val args = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"path":"src/Reader.kt"}"""
        ) as kotlinx.serialization.json.JsonObject
        progress.observe(
            ApiState.GatewayProgressChanged(
                GatewayProgress(
                    event = "tool_completed",
                    toolName = "github_read",
                    toolCallId = "new-evidence",
                    toolArgs = args
                )
            )
        )
        now = 90_000L
        val fallback = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertFalse(fallback.modelAuthored)
        assertTrue(fallback.text.startsWith("### Source inspection"))
        assertTrue(fallback.text.contains("Reader.kt"))
    }

    @Test fun clockOnlyChangesCannotGenerateANewActivitySummary() {
        var now = 0L
        val progress = GitHubChatProgress("Review CI") { now }
        progress.tick()
        progress.observe(
            ApiState.GatewayProgressChanged(
                GatewayProgress(
                    event = "tool_completed",
                    toolName = "github_read",
                    toolCallId = "first",
                    toolArgs = kotlinx.serialization.json.Json.parseToJsonElement("""{"path":"src/Reader.kt"}""") as kotlinx.serialization.json.JsonObject
                )
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
