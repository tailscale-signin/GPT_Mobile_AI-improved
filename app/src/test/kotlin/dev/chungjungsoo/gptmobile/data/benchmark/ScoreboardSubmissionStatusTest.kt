package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreboardSubmissionStatusTest {
    @Test fun `open submission without validator comment stays pending`() {
        val status = classifyScoreboardSubmission("open", emptyList())
        assertEquals(ScoreboardSubmissionStatus.State.PENDING, status.state)
    }

    @Test fun `validation failure is surfaced while issue stays open`() {
        val status = classifyScoreboardSubmission("open", listOf("Scoreboard validation failed: Missing required trial data."))
        assertEquals(ScoreboardSubmissionStatus.State.REJECTED, status.state)
        assertEquals("Missing required trial data.", status.message)
    }

    @Test fun `accepted submission is confirmed after issue closes`() {
        val status = classifyScoreboardSubmission("closed", listOf("Accepted as a self-reported benchmark. Stored."))
        assertEquals(ScoreboardSubmissionStatus.State.ACCEPTED, status.state)
    }

    @Test fun `acceptance comment alone does not confirm publication`() {
        val status = classifyScoreboardSubmission("open", listOf("Accepted as a self-reported benchmark."))
        assertEquals(ScoreboardSubmissionStatus.State.PENDING, status.state)
    }
}
