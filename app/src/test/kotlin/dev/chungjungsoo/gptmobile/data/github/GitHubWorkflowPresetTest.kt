package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWorkflowPresetTest {
    @Test fun unknownSavedChoiceFallsBackToHeavyWork() {
        assertEquals("heavy_work", GitHubWorkflowPreset.selected("removed-choice").id)
        assertEquals(512, GitHubWorkflowPreset.selected("heavy_work").toolCalls)
        assertEquals(1440, GitHubWorkflowPreset.selected("heavy_work").minutes)
    }

    @Test fun explicitTaskPresetRetainsItsBoundedBudget() {
        assertEquals(256, GitHubWorkflowPreset.fromRequest("[github-workflow-preset:test_integration]")?.toolCalls)
        assertEquals(48, GitHubWorkflowPreset.selected("diagnose_ci").toolCalls)
    }

    @Test fun integrationPresetRetainsPendingPagesWithinItsSamplingBudget() {
        val selected = GitHubWorkflowPreset.selected("test_integration")
        val prompt = selected.prompt()
        assertEquals(256, selected.toolCalls)
        assertTrue(GitHubChecklistScope.supportedFixturePlan(prompt))
        assertTrue(prompt.contains("First preflight"))
        assertTrue(prompt.contains("Never invent saved-view cursors"))
        assertTrue(prompt.contains("FIXTURE_BLOCKED"))
        assertTrue(prompt.contains("pending remote continuations"))
        assertFalse(GitHubChecklistScope.exhaustive(prompt))
        assertFalse(GitHubChecklistScope.readOnly(prompt))
        assertFalse(GitHubChecklistScope.exhaustive(GitHubWorkflowPreset.selected("review_branch").prompt()))
    }
}
