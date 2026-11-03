package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import org.junit.Assert.assertEquals
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
        assertEquals(listOf("Reading", "Checking", "Finishing"), visible.drop(1).map { it.content })
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
}
