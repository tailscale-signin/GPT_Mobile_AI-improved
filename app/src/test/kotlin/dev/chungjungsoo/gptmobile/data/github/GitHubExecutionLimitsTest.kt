package dev.chungjungsoo.gptmobile.data.github

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubExecutionLimitsTest {
    private val preset = GitHubWorkflowPreset.selected(GitHubWorkflowPreset.DEFAULT_ID)

    @Test fun integrationCannotExceedItsPresetOrExplicitUserLimit() {
        assertEquals(256, GitHubExecutionLimits.toolCalls("Test all GitHub integration tools", 512, preset))
        assertEquals(12, GitHubExecutionLimits.toolCalls("Test all GitHub integration tools with at most 12 tool calls", 512, preset))
        assertEquals(5, GitHubExecutionLimits.toolCalls("Test all GitHub integration tools with at most 12 tool calls", 5, preset))
        assertEquals(0, GitHubExecutionLimits.toolCalls("Test all GitHub integration tools with at most 0 tool calls", 512, preset))
    }

    @Test fun configuredZeroAndIntegerBoundariesCannotExpandAdmission() {
        assertEquals(0, GitHubExecutionLimits.toolCalls("Implement an issue", 0, preset))
        assertEquals(0, GitHubExecutionLimits.toolCalls("Implement an issue", Int.MIN_VALUE, preset))
        assertEquals(512, GitHubExecutionLimits.toolCalls("Implement an issue with at most 9999999999 tool calls", Int.MAX_VALUE, preset))
        assertEquals(7, GitHubExecutionLimits.toolCalls("Implement an issue. Maximum: 7 tool calls", 100, preset))
    }

    @Test fun aSelectedPresetUsesTheSameLimitBeforeBindingAndBeforeInference() {
        val task = GitHubWorkflowPreset.selected("diagnose_ci").prompt()
        val beforeBinding = GitHubExecutionLimits.toolCalls(task, 512, preset)
        assertEquals(32, beforeBinding)
        assertEquals(beforeBinding, GitHubExecutionLimits.toolCalls(task, beforeBinding, preset))
    }
}
