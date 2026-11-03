package dev.chungjungsoo.gptmobile.data.github

internal data class GitHubWorkflowPreset(val id: String, val title: String, val objective: String, val acceptance: List<String>, val toolCalls: Int, val minutes: Int) {
    fun prompt() = "$objective\n\nAcceptance criteria:\n" + acceptance.joinToString("\n") { "- $it" } +
        "\n\nBudget: at most $toolCalls tool calls and $minutes minutes. Preserve a checkpoint and identify remaining work if the budget ends.\n[github-workflow-preset:$id]"

    companion object {
        val all = listOf(
            GitHubWorkflowPreset("review_branch", "Review branch", "Review the selected branch against its base.", listOf("Inspect the exact head/base revisions and changed files", "Prioritize actionable correctness findings with file/line evidence", "Report validation observed and remaining uncertainty"), 64, 20),
            GitHubWorkflowPreset("diagnose_ci", "Diagnose CI", "Diagnose CI failures on the selected revision.", listOf("Inspect the current workflow attempt, failed jobs and relevant log ranges", "Separate infrastructure and code hypotheses and cite their evidence", "Suggest specific repairs; do not claim unexecuted tests passed"), 48, 15),
            GitHubWorkflowPreset("implement_issue", "Implement issue", "Implement the issue described below on the selected feature branch.", listOf("Resolve each requested behavior with reviewed source changes", "Execute relevant tests at the changed revision", "Retain publication receipts and list unresolved acceptance criteria"), 256, 60),
            GitHubWorkflowPreset("prepare_release", "Prepare release", "Prepare a release for the selected revision.", listOf("Verify requested build, test and security checks", "Inspect package/version, signing identity and artifact provenance", "Prepare release notes and identify the exact artifacts; publication requires the user's release instruction"), 96, 30),
            GitHubWorkflowPreset("test_integration", "Test integration", "Test all authorized GitHub integration tools.", listOf("Discover the authorized tool catalog and observe representative reads before pagination", "Exercise permitted mutations only in an owned disposable fixture and verify cleanup", "Bind acceptance to the running app build and exact repository revision", "Keep device, live account and mocked test outcomes separate"), 256, 60)
        )

        fun fromRequest(request: String): GitHubWorkflowPreset? = all.firstOrNull { "[github-workflow-preset:${it.id}]" in request }
    }
}
