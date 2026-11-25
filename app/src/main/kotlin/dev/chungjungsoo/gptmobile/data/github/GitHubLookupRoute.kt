package dev.chungjungsoo.gptmobile.data.github

/** One read can answer these requests. Discovery remains available for follow-up evidence. */
internal enum class GitHubLookupRoute(val operation: String, val action: String, val step: String, target: String) {
    ISSUES("github_issue", "list", "list_issues", """(?:(?:open|closed) )?issues"""),
    ISSUE("github_issue", "context", "issue_context", """issue #?[1-9]\d*"""),
    PULL_REQUESTS("github_pr", "list", "list_pull_requests", """(?:(?:open|closed) )?(?:prs|pull requests)"""),
    PULL_REQUEST("github_pr", "context", "pr_context", """(?:(?:changes|files) (?:in|for) )?(?:pr|pull request) #?[1-9]\d*"""),
    BRANCHES("github_context", "branches", "list_branches", "branches"),
    COMMITS("github_context", "commits", "list_commits", "commits"),
    WORKFLOWS("github_ci", "workflows", "list_workflows", "workflows"),
    RUNS("github_ci", "runs", "list_workflow_runs", "(?:workflow|ci) runs"),
    RELEASES("github_release", "list", "list_releases", "releases?"),
    TAGS("github_release", "tags", "list_tags", "tags");

    private val targetPattern = Regex(target)

    companion object {
        private val requestPattern = Regex(
            """(?:please )?(?:(?:can|could|would) you (?:please )?)?(?:list|show(?: me)?|inspect|read|summari[sz]e|what (?:is|are)|which) """ +
                """(?:(?:all|the|our|latest|recent|git|github|last \d+) )*(.+?)(?: (?:in|on|for|from) (?:the |this |our |my )?(?:github )?repo(?:sitory)?)?"""
        )

        private val spaces = Regex("[ \\t]+")

        fun from(task: String): GitHubLookupRoute? {
            val request = GitHubTaskContinuity.request(task)
            if (request.length > 240 || '\n' in request || '\r' in request) return null
            val prose = request.trimEnd('.', '!', '?').trim().lowercase(java.util.Locale.ROOT).replace(spaces, " ")
            val target = requestPattern.matchEntire(prose)?.groupValues?.get(1) ?: return null
            return entries.firstOrNull { it.targetPattern.matches(target) }
        }
    }
}
