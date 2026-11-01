package dev.chungjungsoo.gptmobile.data.github

/** Exact short status intents only: an audit or a repair keeps the full engineering workflow. */
internal object GitHubStatusRequest {
    fun matches(text: String): Boolean = text.trim().lowercase().trimEnd('.', '?', '!').trim() in setOf(
        "status",
        "repo status",
        "repository status",
        "github status",
        "git status",
        "show status",
        "show repository status",
        "what is the repository status"
    )

    const val INSTRUCTION = "Repository status request: inspect current repository/branch, recent commits, open pull requests and latest checks. Use at most four read-only calls, then answer. Do not read source files, resume an earlier task, make changes, or poll checks. Distinguish the latest workflow from all required checks; unknown working-tree or test results stay unknown."
}
