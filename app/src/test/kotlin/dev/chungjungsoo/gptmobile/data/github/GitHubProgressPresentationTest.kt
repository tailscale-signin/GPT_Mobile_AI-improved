package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubProgressPresentationTest {
    @Test fun previewHidesInternalExecutionVocabulary() {
        assertEquals("Reviewing the work and checking the next step.", GitHubProgressPresentation.preview("Validating schema receipt before dispatch"))
        assertEquals("Reviewing the source files.", GitHubProgressPresentation.preview("Reviewing the source files."))
        assertEquals("", GitHubProgressPresentation.preview("  "))
    }

    @Test fun heartbeatShowsCurrentActivityWithoutRepeatingItsTaskOrElapsedTime() {
        assertEquals("Checking workflows", GitHubProgressPresentation.activity("Implement the requested change\nChecking workflows · 47s elapsed\nVerified the branch"))
    }

    @Test fun statusPrefixAndInlineClockAreRemovedWithoutChangingMeaningfulStatusText() {
        assertEquals("Indexing", GitHubProgressPresentation.activity("Status: Indexing · 00:04:12"))
        assertEquals("Reading files", GitHubProgressPresentation.activity("Status\nReading files"))
        assertEquals("Checking commit status", GitHubProgressPresentation.activity("Checking commit status"))
    }

    @Test fun longAndEmptyActivitiesHaveOneStableAccessibleLabel() {
        assertEquals("Reading files", GitHubProgressPresentation.activity("  Reading   files... \nAnother milestone"))
        assertEquals("Working in your repository", GitHubProgressPresentation.activity("Status"))
        assertEquals("Working in your repository", GitHubProgressPresentation.activity("\n  "))
    }
}
