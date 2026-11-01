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

    const val INSTRUCTION = "Status: inspect the current repository/branch, recent commits, open PRs and latest checks with at most four read-only calls. Do not read source, resume work, edit or poll. Separate latest workflow from required checks; unknown worktree/tests stay unknown."
}
