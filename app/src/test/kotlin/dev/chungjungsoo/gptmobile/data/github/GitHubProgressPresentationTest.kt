package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubProgressPresentationTest {
    @Test fun previewSymbolsDistinguishWebFileEditsChecksAndWaiting() {
        val examples = mapOf(
            "Searching the internet for relevant information." to GitHubProgressPresentation.PreviewKind.WEB,
            "Reading the relevant web page." to GitHubProgressPresentation.PreviewKind.WEB,
            "Examining files." to GitHubProgressPresentation.PreviewKind.INSPECT,
            "Searching for the code and files related to the task." to GitHubProgressPresentation.PreviewKind.INSPECT,
            "Reading the relevant source files." to GitHubProgressPresentation.PreviewKind.INSPECT,
            "Editing the source files." to GitHubProgressPresentation.PreviewKind.EDIT,
            "Inspecting the build and test results." to GitHubProgressPresentation.PreviewKind.CHECKS,
            "Checking the pull request and its current review state." to GitHubProgressPresentation.PreviewKind.BRANCH,
            "Waiting for the build checks." to GitHubProgressPresentation.PreviewKind.WAIT,
            "A step needs attention." to GitHubProgressPresentation.PreviewKind.ATTENTION,
            "Reviewing the latest result." to GitHubProgressPresentation.PreviewKind.REVIEW,
            "Preparing the next repository operation." to GitHubProgressPresentation.PreviewKind.ACTIVITY,
            "" to GitHubProgressPresentation.PreviewKind.ACTIVITY,
            "Contest information." to GitHubProgressPresentation.PreviewKind.ACTIVITY
        )
        examples.forEach { (text, kind) -> assertEquals(text, kind, GitHubProgressPresentation.previewKind(text)) }
    }

    @Test fun taskIntroductionIsSpecificBoundedAndRedactsCredentials() {
        val introduction = GitHubProgressPresentation.introduction("status")
        org.junit.Assert.assertTrue(introduction.contains("repository status") && introduction.contains("pull requests"))
        val task = GitHubProgressPresentation.taskLabel("Fix login ghp_12345678901234567890\nDo not expose this hidden second line")
        org.junit.Assert.assertTrue(task.contains("Fix login"))
        org.junit.Assert.assertFalse(task.contains("ghp_") || task.contains("hidden second line"))
        org.junit.Assert.assertTrue(GitHubProgressPresentation.taskLabel("a".repeat(500)).length <= 120)
    }

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
        assertEquals("Preparing the requested repository checks", GitHubProgressPresentation.activity("Status"))
        assertEquals("Preparing the requested repository checks", GitHubProgressPresentation.activity("\n  "))
    }
}
