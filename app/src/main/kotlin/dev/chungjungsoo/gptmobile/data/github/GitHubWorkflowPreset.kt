package dev.chungjungsoo.gptmobile.data.github

internal data class GitHubWorkflowPreset(val id: String, val title: String, val objective: String, val acceptance: List<String>, val toolCalls: Int, val minutes: Int) {
    fun prompt() = "$objective\n\nAcceptance criteria:\n" + acceptance.joinToString("\n") { "- $it" } +
        "\n\nBudget: at most $toolCalls tool calls and $minutes minutes. Preserve a checkpoint and identify remaining work if the budget ends.\n[github-workflow-preset:$id]" +
        if (id == "test_integration") " " + GitHubChecklistScope.PRESET_SCOPE else ""

    companion object {
        const val DEFAULT_ID = "heavy_work"
        val all = listOf(
            GitHubWorkflowPreset(DEFAULT_ID, "Heavy work", "Complete the requested repository work.", listOf("Preserve the user’s scope and acceptance criteria", "Save progress and verify results before claiming completion"), 512, 1440),
            GitHubWorkflowPreset("review_branch", "Review branch", "Review the selected branch against its base.", listOf("Inspect the exact head/base revisions and changed files", "Prioritize actionable correctness findings with file/line evidence", "Report validation observed and remaining uncertainty"), 64, 20),
            GitHubWorkflowPreset("diagnose_ci", "Diagnose CI", "Diagnose CI failures on the selected revision.", listOf("Inspect the current workflow attempt, failed jobs and relevant log ranges", "Separate infrastructure and code hypotheses and cite their evidence", "Suggest specific repairs; do not claim unexecuted tests passed"), 48, 15),
            GitHubWorkflowPreset("implement_issue", "Implement issue", "Implement the issue described below on the selected feature branch.", listOf("Identify the exact workspace, baseline, build and write prerequisites", "Select a reproducible defect with pinned source and test evidence", "Stage the targeted fix and regression", "Run the narrow regression and requested validation at the changed revision", "Review the diff, then retain separate receipts for each requested publication step"), 256, 60),
            GitHubWorkflowPreset("prepare_release", "Prepare release", "Prepare a release for the selected revision.", listOf("Verify requested build, test and security checks", "Inspect package/version, signing identity and artifact provenance", "Prepare release notes and identify the exact artifacts; publication requires the user's release instruction"), 96, 30),
            GitHubWorkflowPreset("test_integration", "Test integration", "Test the authorized GitHub integration using the supported fixture test matrix. First preflight the exact repository, model context capacity, native tool catalog, workflow/job prerequisites and effective fixture approval mode. If prerequisites are absent, stop the blocked lane before expensive reads and retain BLOCKED or NOT_TESTED rather than pretending the test ran. Sample representative actions and their next page. Report remaining history separately. Exhaust the authorized tool catalog and the selected test target. Never invent saved-view cursors, widen permissions, or replay an uncertain write. Report unsupported actions and conditional user decisions separately; do not claim every action was executed.", listOf("Discover the authorized tool catalog and observe representative reads before pagination", "Classify each capability READY, APPROVAL_BLOCKED, FIXTURE_BLOCKED, WORKFLOW_INPUT_REQUIRED or LIVE_EVIDENCE_REQUIRED before bulk execution", "Exercise permitted mutations only in an owned disposable fixture and verify cleanup", "Bind acceptance to the running app build and exact repository revision", "Retain exact-SHA test receipts and pending remote continuations without spending the final verification reserve on discovery", "Keep device, live account and mocked test outcomes separate"), 256, 60)
        )

        private val implementationRequest = Regex("""(?i)\b(?:implement|fix|patch|repair|refactor)\b""")
        private val planningOnly = Regex("""(?i)\b(?:read.only|without (?:editing|modifying|changing)|do not (?:implement|edit|change)|(?:plan|design) (?:only|before)|(?:explain|suggest|describe) (?:how|a fix))\b""")
        fun implementation(request: String): Boolean = !GitHubChecklistScope.readOnly(request) &&
            !planningOnly.containsMatchIn(request) &&
            !request.trimStart().startsWith("how ", ignoreCase = true) &&
            (fromRequest(request)?.let { it.id == "implement_issue" } ?: implementationRequest.containsMatchIn(request))

        fun selected(id: String): GitHubWorkflowPreset = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }

        fun fromRequest(request: String): GitHubWorkflowPreset? = all.firstOrNull { "[github-workflow-preset:${it.id}]" in request }
    }
}
