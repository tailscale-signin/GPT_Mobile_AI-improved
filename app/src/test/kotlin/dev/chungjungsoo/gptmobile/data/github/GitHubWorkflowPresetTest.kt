package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
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
}
