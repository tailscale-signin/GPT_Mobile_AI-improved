package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.Serializable

/** User-editable prompts are stored with the selected GitHub profile. */
@Serializable
data class GitHubQuickWorkflow(val id: String, val title: String, val prompt: String) {
    companion object {
        val defaults = listOf(
            GitHubQuickWorkflow("integration", "Test GitHub integration (supported fixtures)", GitHubWorkflowPreset.selected("test_integration").prompt()),
            GitHubQuickWorkflow("review", "Review this branch", GitHubWorkflowPreset.selected("review_branch").prompt()),
            GitHubQuickWorkflow("ci", "Diagnose failing checks", GitHubWorkflowPreset.selected("diagnose_ci").prompt()),
            GitHubQuickWorkflow("status", "Repository health report", "Inspect this repository and current branch, open pull requests, required checks, recent failures and outstanding review decisions. Report confirmed status, blockers and the next useful actions. Do not modify the repository."),
            GitHubQuickWorkflow("improve", "Find and fix a repository issue", "Inspect the repository instructions and relevant tests, identify the highest-confidence actionable correctness issue, and implement its complete fix on a new feature branch. Add a behavior regression, run relevant validation, review the diff and prepare a draft pull request. Report the exact changes and any validation that could not run.")
        )
    }
}
