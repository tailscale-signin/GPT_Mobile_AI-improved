package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActivityLinesTest {
    private fun line(text: String, summary: Boolean = false) = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, text, progressCheckpoint = true, statusSummary = summary)

    @Test fun retainedPreparationPreviewFollowsFirstSummaryWithoutReorderingLaterUpdates() {
        val lines = gitHubActivityLines(listOf(line("Preparing"), line("First summary", true), line("Reading"), line("Next summary", true), line("Checking")), "fix")
        assertEquals(listOf("First summary", "Preparing", "Reading", "Next summary", "Checking"), lines.map { it.content })
    }

    @Test fun absentSummaryAndCompactWindowStillStartWithTextSummary() {
        val lines = gitHubActivityLines(listOf(line("Preparing"), line("Reading"), line("Checking"), line("Finishing")), "status")
        assertTrue(lines.first().statusSummary)
        val visible = gitHubVisibleActivityLines(lines, compact = true)
        assertTrue(visible.first().statusSummary)
        assertEquals(listOf("Preparing", "Reading", "Checking", "Finishing"), visible.drop(1).map { it.content })
    }

    @Test fun searchFallbackFollowsSummaryAndParticipatesInThePreviewWindow() {
        val lines = gitHubActivityLines(listOf(line("Checking available websites", true)), "review", searchActive = true)
        assertEquals(listOf("Checking available websites", "Searching websites"), lines.map { it.content })
        assertTrue(lines.first().statusSummary)
        assertTrue(!lines.last().statusSummary)
        assertEquals(lines, gitHubVisibleActivityLines(lines, compact = true))
    }

    @Test fun searchFallbackUsesAnIntroductionAndDoesNotDuplicateAnExistingPreview() {
        val empty = gitHubActivityLines(emptyList(), "review", searchActive = true)
        assertTrue(empty.first().statusSummary)
        assertEquals("Searching websites", empty.last().content)
        val existing = gitHubActivityLines(listOf(line("Summary", true), line("Reading websites")), "review", searchActive = true)
        assertEquals(listOf("Summary", "Reading websites"), existing.map { it.content })
    }

    @Test fun historyThresholdCountsTwentyEntriesIndependentlyOfWrappingAndRetainsSummary() {
        val lines = listOf(line("Summary\nwith\nseveral\nlines", true)) + (1..19).map { line("Step $it") }
        assertEquals(lines, gitHubVisibleActivityLines(lines, true))
        val history = lines + (20..49).map { line("Step $it") }
        val visible = gitHubVisibleActivityLines(history, true)
        assertTrue(visible.first().statusSummary)
        assertEquals(20, visible.size)
        assertEquals(20, gitHubVisibleActivityLines(lines + line("Step 20"), true).size)
        assertEquals("Step 49", visible.last().content)
        assertEquals(40, gitHubVisibleActivityLines(history, true, entryLimit = 40).size)
        assertEquals(history, gitHubVisibleActivityLines(history, true, entryLimit = 60))
    }

    @Test fun repeatedCountsReplaceTheSamePublicLineAndSummaryDoesNotRecur() {
        val lines = gitHubActivityLines(listOf(line("Summary", true), line("Checks: 1/8 complete. Reading files."), line("Other summary", true), line("Summary", true), line("Checks: 3/8 complete; 1 failed. Checking jobs.")), "test all")
        assertEquals(1, lines.count { it.content.startsWith("Checks:") })
        assertEquals("Checks: 3/8 complete; 1 failed", lines[1].content)
        assertEquals("github-check-counts", lines[1].progressId)
        assertEquals(1, lines.count { it.content == "Summary" })
    }

    @Test fun independentCountersUpdateTheirOriginalRowsAndSurviveHistoryTrimming() {
        val timeline = listOf(line("Summary", true), line("Check 1/8"), line("Files: 2/6")) +
            (1..30).map { line("Reviewing item $it") } + listOf(line("Check 3/8"), line("Files: 5/6"))
        val lines = gitHubActivityLines(timeline, "test")
        assertEquals(listOf("Summary", "Check 3/8", "Files: 5/6"), lines.take(3).map { it.content })
        val visible = gitHubVisibleActivityLines(lines, compact = true)
        assertEquals(20, visible.size)
        assertEquals(listOf("Summary", "Check 3/8", "Files: 5/6"), visible.take(3).map { it.content })
        assertEquals("Reviewing item 30", visible.last().content)
    }

    @Test fun detailedModelSummariesRetainTheirTextAndAuthorshipWhileAcknowledgementIsDeterministic() {
        val first = line("The gateway received the request.\nI have verified the response schema and will check the source files next.", true).copy(modelAuthored = true)
        val second = line("The gateway returned the file list.\nTwo files contain the affected function; I will check their callers next.", true).copy(modelAuthored = true)
        assertEquals(listOf(first, second), gitHubActivityLines(listOf(first, second), "fix"))
        assertFalse(gitHubActivityLines(emptyList(), "fix").single().modelAuthored)
    }

    @Test fun retainedPublicFindingsReplaceLaterGenericSummaryInThePrimaryView() {
        val details = "## Findings\n\n- The staged patch is retained.\n- [Check details](https://github.com/owner/repo/actions/runs/1)\n\nApproval is still required."
        val public = line(details).copy(modelAuthored = true, progressId = "saved-findings")
        val generic = line("I have completed 5 repository operations. Reviewing the latest result and the next step. Next, I will review these results and continue the remaining requested work. Any remaining checks or blocked operations stay visible until their results can be verified.", true)
        val waiting = line("Waiting for approval before committing.")
        val history = listOf(public, generic, waiting, AssistantTimelineItem(AssistantTimelineItemType.TEXT, generic.content))
        val primary = gitHubResponsePresentation(generic.content, history)
        assertEquals(details, primary.text)
        assertTrue(primary.progressOnly)
        assertEquals(details, primary.timeline.single { it.type == AssistantTimelineItemType.TEXT }.content)
        assertEquals("saved-findings", primary.timeline.last().progressId)
        assertTrue(primary.timeline.any { it.content == waiting.content })
        assertEquals(details, public.content)
        assertTrue(history.any { it.content == generic.content })
        assertTrue(history.any { it.content == details && !it.statusSummary })
        // The exact historical ticker form remains recognizable when its original
        // status-summary row was pruned; no broad prose heuristic is needed.
        assertEquals(details, gitHubResponsePresentation(generic.content, history - generic).text)
    }

    @Test fun mainSummaryNeverPromotesReasoningToolBodiesOrPrivateDelegation() {
        val public = line("The build log identifies the failing check.").copy(modelAuthored = true)
        val privateItems = listOf(
            public.copy(type = AssistantTimelineItemType.THINKING, content = "private reasoning"),
            public.copy(type = AssistantTimelineItemType.TOOL, content = "raw tool result"),
            public.copy(toolSequence = 1, content = "tool diagnostic"),
            public.copy(progressCheckpoint = false, content = "internal notice"),
            public.copy(delegationInvocationId = "private-review", content = "private review"),
            public.copy(content = "<think>private inline reasoning</think>")
        )
        assertEquals(public.content, gitHubPublicSummary(listOf(public) + privateItems)?.content)
        assertEquals(null, gitHubPublicSummary(privateItems))
    }

    @Test fun selectedModelProgressIsPromotedInActivityAndSurvivesLaterGenericTickers() {
        val model = line("The selected head has 18 checks. I will resolve the comparison base next.")
            .copy(modelAuthored = true, progressId = "invocation-8-progress")
        val ticker = line("I have completed 5 repository operations. Reviewing the latest result and the next step. Next, I will review these results and continue the remaining requested work. Any remaining checks or blocked operations stay visible until their results can be verified.", true)
        val count = line("Checks: 16/18 complete.")
        val retained = listOf(ticker, model, ticker, count)
        val projected = gitHubActivityLines(retained, "Review the branch")
        assertEquals(model.copy(statusSummary = true), projected.first())
        assertTrue(projected.first().modelAuthored)
        assertEquals("invocation-8-progress", projected.first().progressId)
        assertFalse(projected.any { it.content == ticker.content })
        assertTrue(projected.any { it.progressId == "github-check-counts" })
        assertFalse(model.statusSummary)
    }

    @Test fun modelProgressDoesNotSuppressMeasuredConnectionOrFailureNotices() {
        val model = line("The repository context is loaded.").copy(modelAuthored = true)
        val measured = line("Connection unavailable; the queued request is retained.", true)
        val lines = gitHubActivityLines(listOf(model, measured), "Inspect")
        assertEquals(listOf(model.copy(statusSummary = true), measured), lines)
    }

    @Test fun latestPublicSummaryKeepsItsFullSavedBodyAndUpdatesTheMainView() {
        val earlier = line("Earlier findings").copy(modelAuthored = true)
        val latest = line("## Latest findings\n\n" + "A retained observation.\n".repeat(200)).copy(modelAuthored = true)
        val primary = gitHubResponsePresentation("", listOf(earlier, latest))
        assertEquals(latest.content, primary.text)
        assertEquals(latest.content, primary.timeline.single { it.type == AssistantTimelineItemType.TEXT }.content)
        assertTrue(primary.progressOnly)
    }

    @Test fun substantiveAnswerKeepsPriorityAndGenericOnlyHistoryCannotInventOne() {
        val public = line("I will inspect the workflow next.").copy(modelAuthored = true)
        val report = "## Results\n\nNo failed runs were observed in the returned set."
        val timeline = listOf(public, AssistantTimelineItem(AssistantTimelineItemType.TEXT, report))
        assertEquals(GitHubResponsePresentation(report, timeline), gitHubResponsePresentation(report, timeline))
        assertEquals(GitHubResponsePresentation("", timeline), gitHubResponsePresentation("", timeline))
        val generic = line("Reviewing the latest result.", true)
        assertEquals(GitHubResponsePresentation(generic.content, listOf(generic)), gitHubResponsePresentation(generic.content, listOf(generic)))
    }
}
