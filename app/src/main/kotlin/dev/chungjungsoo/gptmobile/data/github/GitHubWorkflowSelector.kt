package dev.chungjungsoo.gptmobile.data.github

/** REST accepts an ID or basename, while discovery returns repository-relative paths. */
internal fun normalizeGitHubWorkflowSelector(value: String): String {
    val selector = value.trim().removePrefix(".github/workflows/")
    require(selector.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) && selector !in setOf(".", "..")) {
        "Use a workflow ID, filename, or .github/workflows/<filename> from github_ci action=workflows."
    }
    return selector
}
