package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolCapability
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool

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

    /** Match an admitted facade or the exact selected read published by an official MCP connection. */
    fun matchesTool(tool: AgentToolDefinition): Boolean {
        if (!tool.modelVisible) return false
        if (GitHubOperationTool.operationName(tool.name) == operation) return true
        if (AgentToolCapability.GITHUB !in tool.capabilities) return false
        return matchesOfficialRead(tool.name.substringAfterLast("__"))
    }

    fun matchesOfficialRead(remote: String): Boolean {
        if (!GitHubMcpRepositoryTool.isReadOnlyOperation(remote)) return false
        return when (this) {
            ISSUE -> remote in setOf("get_issue", "issue_read")
            PULL_REQUEST -> remote in setOf("get_pull_request", "pull_request_read")
            else -> remote == step
        }
    }

    data class Request(
        val route: GitHubLookupRoute,
        val number: Int?,
        val state: String?,
        val pageSize: Int?,
        val newestFirst: Boolean,
        val filesOnly: Boolean,
        val patches: Boolean,
        val requestedCount: Int? = pageSize,
        val exhaustive: Boolean = false
    )

    companion object {
        private val requestPattern = Regex(
            """(?:please )?(?:(?:can|could|would) you (?:please )?)?(?:list|show(?: me)?|inspect|read|summari[sz]e|what (?:is|are)|which) """ +
                """((?:(?:all|the|our|latest|recent|git|github|last \d+) )*)(.+?)(?: (?:in|on|for|from) (?:the |this |our |my )?(?:github )?repo(?:sitory)?)?"""
        )

        private val spaces = Regex("[ \\t]+")

        private val count = Regex("last ([0-9]+)")
        private val record = Regex("[0-9]+$")

        fun from(task: String): GitHubLookupRoute? = request(task)?.route

        fun request(task: String): Request? {
            val request = GitHubTaskContinuity.request(task)
            if (request.length > 240 || '\n' in request || '\r' in request) return null
            val prose = request.trimEnd('.', '!', '?').trim().lowercase(java.util.Locale.ROOT).replace(spaces, " ")
            val match = requestPattern.matchEntire(prose) ?: return null
            val (modifiers, target) = match.destructured
            val route = entries.firstOrNull { it.targetPattern.matches(target) } ?: return null
            val counts = count.findAll(modifiers).toList()
            if (counts.size > 1) return null
            val size = counts.singleOrNull()?.groupValues?.get(1)?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: return null }
            val number = if (route == ISSUE || route == PULL_REQUEST) {
                if (size != null) return null
                record.find(target)?.value?.toIntOrNull() ?: return null
            } else {
                null
            }
            val state = if (route == ISSUES || route == PULL_REQUESTS) {
                when {
                    target.startsWith("closed ") -> "closed"
                    target.startsWith("open ") -> "open"
                    "all" in modifiers.split(' ') -> "all"
                    else -> null
                }
            } else {
                null
            }
            val newestFirst = (route == ISSUES || route == PULL_REQUESTS) && (size != null || "recent " in modifiers || "latest " in modifiers)
            val exhaustive = "all" in modifiers.split(' ')
            val pageSize = size?.coerceAtMost(100) ?: 10.takeIf { route == COMMITS && !exhaustive }
            return Request(route, number, state, pageSize, newestFirst, target.startsWith("files ") || target.startsWith("changes "), target.startsWith("changes "), size ?: pageSize, exhaustive)
        }
    }
}

/** An ordinary chat may use an authorized official MCP read against one explicitly supplied repository. */
internal data class GitHubMcpRepositoryRead(val repository: GitHubRepositoryContext, val route: GitHubLookupRoute?) {
    fun matchesOperation(name: String): Boolean = route?.matchesOfficialRead(name) ?: (name in setOf("get_repository", "get_file_contents"))

    companion object {
        private val owner = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
        private val inspect = Regex("""(?i)(?:please )?(?:inspect|read|show(?: me)?|fetch|check|review) (?:the )?(?:github )?repo(?:sitory)?""")
        private val preposition = Regex("""(?i) (?:in|for|on|from|of)$""")

        fun from(request: String): GitHubMcpRepositoryRead? {
            val text = request.trim().trimEnd('.', '!', '?')
            if (text.length > 360 || '\n' in text || '\r' in text) return null
            val source = text.substringAfterLast(' ', "")
            if ('/' !in source) return null
            val (account, name) = runCatching { normalizeGitHubRepository(null, source) }.getOrNull() ?: return null
            if (account == null || name == null || !owner.matches(account)) return null
            val prefix = text.substringBeforeLast(' ').trim()
            val route = GitHubLookupRoute.from(prefix.replace(preposition, ""))
            if (route == null && !inspect.matches(prefix)) return null
            return GitHubMcpRepositoryRead(GitHubRepositoryContext(account, name, ""), route)
        }
    }
}
