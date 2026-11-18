package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
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
        assertTrue(output.startsWith("### Reviewing Reader.kt"))
        assertFalse(output.contains("**Observed:**"))
        assertTrue(output.contains("Reader.kt"))
        assertTrue(output.contains("- I’ll "))
        assertFalse(output.contains("All tests passed"))
        assertFalse(output.contains("| Check state |"))
        assertTrue(GitHubRichSummaryPresentation.isRich(output))
    }

    @Test fun twoSourceReadsDescribeDifferentFilesInsteadOfRecyclingAHeading() {
        val first = GitHubRichSummaryPresentation.render(sample("The source read for Renderer.kt returned a result for inspection."))
        val second = GitHubRichSummaryPresentation.render(sample("The source read for Audio.kt returned a result for inspection."))
        assertTrue(first.startsWith("### Reviewing Renderer.kt"))
        assertTrue(second.startsWith("### Reviewing Audio.kt"))
        listOf(first, second).forEach { text ->
            assertTrue(text.contains("no repair has been verified"))
            assertFalse(text.contains("Repository findings") || text.contains("**Next:**") || text.contains("**Observed:**"))
        }
    }

    @Test fun failedSourceReadRemainsVisibleAlongsideLaterEvidence() {
        val output = GitHubRichSummaryPresentation.render(
            sample(
                "The source read for Failure.kt failed.",
                "The source read for Latest.kt returned a result for inspection."
            )
        )
        assertTrue(output.startsWith("### Failure.kt needs attention"))
        assertTrue(output.contains("Reading Failure.kt failed"))
        assertTrue(output.contains("Latest.kt"))
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
        val csv = requireNotNull(GitHubRichSummaryPresentation.csv(output))
        assertTrue(csv.startsWith("Check state,Count,Source,Captured list,Observation\r\n"))
        assertTrue(csv.contains("Successful,15,\"Job 7\",Complete,Recorded results; not a new test execution\r\n"))
        assertEquals(5, csv.trim().lines().size)
    }

    @Test fun workbookPreservesNumericCountsAndStoresTheSourceAsText() {
        val markdown = GitHubRichSummaryPresentation.render(
            sample("Recorded checks for =SUM(1,2)&<job>: 15 successful, 4 skipped, 0 in progress, 1 failed (incomplete captured list).")
        )
        val output = ByteArrayOutputStream()
        GitHubSummaryWorkbook.write(output, requireNotNull(GitHubRichSummaryPresentation.checkTable(markdown)))
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes()
            }
        }
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml"),
            parts.keys
        )
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val sheet = factory.newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue("xl/worksheets/sheet1.xml")))
        val cells = sheet.getElementsByTagNameNS("*", "c")
        val byRef = (0 until cells.length).map { cells.item(it) }.associateBy { it.attributes.getNamedItem("r").nodeValue }
        assertEquals("n", byRef.getValue("B2").attributes.getNamedItem("t").nodeValue)
        assertEquals("15", byRef.getValue("B2").textContent)
        assertEquals("inlineStr", byRef.getValue("C2").attributes.getNamedItem("t").nodeValue)
        assertEquals("=SUM(1,2)&<job>", byRef.getValue("C2").textContent)
        assertEquals("Incomplete", byRef.getValue("D2").textContent)
        assertEquals(0, sheet.getElementsByTagNameNS("*", "f").length)
    }

    @Test fun incompleteRecordedChecksKeepJobScopeCoverageAndNextAction() {
        val source = "Recorded checks for Job 7: 2 successful, 1 skipped, 0 in progress, 4 failed (incomplete captured list)."
        val result = GitHubRichSummaryPresentation.render(sample(source))
        assertTrue(result.startsWith("### Partial recorded check results"))
        assertTrue(result.contains("**Source:** Job 7"))
        assertTrue(result.contains("**Coverage:** The captured check list is incomplete"))
        assertTrue(result.contains("- I’ll "))
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
        assertTrue(result.contains("- I’ll "))
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

    @Test fun copiedPartialTablesKeepTheirSourceCoverageAndSpreadsheetSafety() {
        val source = "Recorded checks for =SUM(1,2): 12 successful, 1 skipped, 0 in progress, 2 failed (incomplete captured list)."
        val output = GitHubRichSummaryPresentation.render(sample(source))
        val csv = requireNotNull(GitHubRichSummaryPresentation.csv(output))
        assertTrue(csv.contains("\"'=SUM(1,2)\",Incomplete,Recorded results; not a new test execution"))
        assertTrue(GitHubRichSummaryPresentation.chartData(output).isNotEmpty())
        val incompleteWithoutDisclosure = output.lines().filterNot { it.startsWith("- **Coverage:**") }.joinToString("\n")
        assertNull(GitHubRichSummaryPresentation.csv(incompleteWithoutDisclosure))
        assertTrue(GitHubRichSummaryPresentation.chartData(incompleteWithoutDisclosure).isEmpty())
    }

    @Test fun csvQuotesSourceLabelsAndRemovesOnlyMarkdownEscaping() {
        val output = GitHubRichSummaryPresentation.render(
            sample("Recorded checks for Job \"CI_linux\", run 7: 3 successful, 1 skipped, 0 in progress, 1 failed (complete captured list).")
        )
        assertTrue(requireNotNull(GitHubRichSummaryPresentation.csv(output)).contains("\"Job \"\"CI_linux\"\", run 7\",Complete"))
    }

    @Test fun escapedLongFindingsCannotCutOffTheFinalCountTable() {
        val special = "[".repeat(220)
        val output = GitHubRichSummaryPresentation.render(
            sample(
                "Source failed $special",
                "Recorded checks for ${"_".repeat(90)}: 100000 successful, 100000 skipped, 100000 in progress, 100000 failed, 100000 other completed (incomplete captured list)."
            ).copy(next = special)
        )
        assertTrue(output.length <= 1600)
        assertTrue(output.contains("- I’ll "))
        assertTrue(output.endsWith("| Other completed | 100000 |"))
        assertEquals(5, GitHubRichSummaryPresentation.chartData(output).size)
        assertTrue(requireNotNull(GitHubRichSummaryPresentation.csv(output)).contains("Incomplete"))
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
        now = 180_000L
        assertEquals(1, progress.tick().filterIsInstance<ApiState.ActivitySummary>().size)

        read("src/Alpha.kt", "alpha-2")
        now = 315_000L
        assertTrue(progress.tick().none { it is ApiState.ActivitySummary })

        read("src/Alpha.kt", "alpha-3", failed = true)
        now = 450_000L
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
        now = 180_000L
        val fallback = progress.tick().filterIsInstance<ApiState.ActivitySummary>().single()
        assertFalse(fallback.modelAuthored)
        assertTrue(fallback.text.startsWith("### Reviewing Reader.kt"))
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
