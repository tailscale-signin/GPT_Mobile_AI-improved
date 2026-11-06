package dev.chungjungsoo.gptmobile.data.github

/** REST accepts an ID or basename, while discovery returns repository-relative paths. */
internal fun normalizeGitHubWorkflowSelector(value: String): String {
    val selector = value.trim().removePrefix(".github/workflows/")
    val numeric = selector.all(Char::isDigit)
    require(
        selector.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")) &&
            selector !in setOf(".", "..") &&
            (!numeric || selector.toLongOrNull()?.let { it > 0 } == true)
    ) {
        "Use a workflow ID, filename, or .github/workflows/<filename> from github_ci action=workflows."
    }
    return selector
}
