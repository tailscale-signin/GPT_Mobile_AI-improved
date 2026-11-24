package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.CurrentRequestContract
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The same frozen repository defaults used by a resolved native executor, without credentials. */
data class GitHubPlanningContext(
    val connectionUid: String,
    val toolSuffix: String,
    val repository: GitHubRepositoryContext?,
    val observedCommitSha: String? = null,
    val observedAtMillis: Long? = null
) {
    fun toolDescription(): String = repository?.let {
        " Selected repository: ${it.fullName}; ref: ${JsonPrimitive(it.ref)}. Omitted owner/repo/ref use this selection. Resolve it before dependent operations; never invent repository or fixture names."
    } ?: " No repository selected. Use explicit owner/repo or authorized discovery; never invent repository or fixture names."

    private fun record() = buildJsonObject {
        put("connection_uid", connectionUid)
        put("context_tool", "github_context$toolSuffix")
        repository?.let {
            put("owner", it.owner)
            put("repo", it.repo)
            put("ref", it.ref)
        }
        observedCommitSha?.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }?.let { put("observed_commit_sha", it) }
        observedAtMillis?.let { put("observed_at_epoch_ms", it) }
        put("access_verified_for_current_request", false)
    }

    companion object {
        private val quotedReadMaterial = Regex("(?s)```.*?(?:```|\\z)|~~~.*?(?:~~~|\\z)|(`+)|\"(?:\\\\(?:.|\\z)|[^\"\\\\])*(?:\"|\\z)|“[^”]*(?:”|\\z)|‘[^’]*(?:’|\\z)|(?<![\\p{L}\\p{N}])'[^'\\r\\n]*(?:'|\\z)")
        private val textReadObjectNames = "(?:sentences?|phrases?|quotes?|quotations?|questions?|examples?|poems?|stor(?:y|ies)|lyrics|fiction)"
        private val textReadObject = Regex("(?i)^(?:(?:me|us|the|this|that|a|an|these|those|following)\\s+){0,3}$textReadObjectNames\\b")
        private val namedReadObject = Regex("(?i)\\b$textReadObjectNames\\b")

        private fun readRequestProse(request: String): String = buildString(request.length) {
            var cursor = 0
            while (cursor < request.length) {
                val quoted = quotedReadMaterial.find(request, cursor)
                if (quoted == null) {
                    append(request, cursor, request.length)
                    break
                }
                append(request, cursor, quoted.range.first).append(' ')
                cursor = quoted.range.last + 1
                val delimiterLength = quoted.groups[1]?.value?.length ?: continue
                // Inline code may span lines; only a complete matching tick run
                // closes it. An unmatched opening run consumes the remaining data.
                while (cursor < request.length) {
                    val start = request.indexOf('`', cursor)
                    if (start < 0) {
                        cursor = request.length
                        break
                    }
                    var end = start + 1
                    while (end < request.length && request[end] == '`') end++
                    cursor = end
                    if (end - start == delimiterLength) break
                }
            }
        }

        private fun selectedRepositoryReadIntent(request: String): Boolean {
            // Quoted examples are data. Only the leading prose request can add
            // selected context; a later mention cannot promote a translation or poem.
            val plain = readRequestProse(request).lineSequence()
                .filterNot { it.trimStart().startsWith(">") || it.startsWith("    ") || it.startsWith('\t') }
                .joinToString("\n").trim()
            val target = "(?:repository|repo|project|codebase|source(?:\\s+tree)?|implementation|files?)"
            val selection = "(?:(?:this|the|these)(?:\\s+(?:selected|current))?|selected|current)\\s+$target"
            val read = "(?:what|which|where|why|how|show|list|inspect|review|explain|summari[sz]e|describe|compare|find|locate|does|do|is|are)"
            val polite = "(?:please\\s+)?(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?"
            val direct = Regex("(?i)^$polite$read\\b\\s*").find(plain)
            if (direct != null) {
                val objectText = plain.substring(direct.range.last + 1)
                val selectedTarget = Regex("(?i)^([^\\r\\n.!?;:]{0,160})\\b$selection\\b").find(objectText) ?: return false
                val objectPrefix = selectedTarget.groupValues[1]
                // Modifiers do not turn a creative object into a repository read.
                // Material after the selected target does not change its read intent.
                return !textReadObject.containsMatchIn(objectText) &&
                    !namedReadObject.containsMatchIn(objectPrefix)
            }
            val scoped = Regex("(?i)^$polite(?:in|within)\\s+$selection\\s*,\\s*(?:please\\s+)?$read\\b\\s*").find(plain)
                ?: return false
            return !textReadObject.containsMatchIn(plain.substring(scoped.range.last + 1))
        }

        /** Repository selection is data, not permission or proof of a fresh head/access check. */
        fun forRequest(request: String, contexts: List<GitHubPlanningContext>): String {
            if (CurrentRequestContract.from(request).conversational || contexts.isEmpty()) return ""
            val repositoryIntent = GitHubOperationTool.taskTools(request).isNotEmpty() ||
                selectedRepositoryReadIntent(request) ||
                Regex("(?i)^(?:please\\s+)?(?:continue|resume|retry|try again|finish(?: it| that| this)?|fix(?: it| that| this)?|go ahead|do it|proceed|check again)[.!?\\s]*$").matches(request.trim())
            if (!repositoryIntent) return ""
            return buildString {
                append("Selected GitHub executor targets (application data; these do not grant access):\n")
                contexts.distinct().forEach { append(it.record()).append('\n') }
                append("Use the selected target for omitted owner/repo/ref. A newer successful resolve receipt supersedes the recorded head. Do not ask for a target already selected, invent owner/repo values, or reinterpret a workflow preset as a repository. If selection is missing or ambiguous, use authorized repository discovery or ask the user to select the exact target before dependent calls. Test fixtures are owned disposable branches/files inside the verified target, never guessed repositories.\n")
                append("Copy each continuation_call's exact tool and arguments. Listing inventory, advertising schemas, executing operations and passing acceptance criteria are different counts. Use recorded coverage fields; never infer test success from discovery or an HTTP success. A 404 does not establish deletion or insufficient OAuth scopes; accepted OAuth scopes describe endpoint requirements, not missing grants.")
                append(" Reuse retained branch observations and pinned source ranges. Once the requested edit's target source, repository instructions and acceptance criteria are established, make the next authorized working-branch or patch operation; only perform another discovery read for a specific missing prerequisite. A coding task is still unfinished when only reads and searches have completed.")
            }
        }
    }
}
