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
    val observedAtMillis: Long? = null,
    val webOrigin: String? = null
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
        private val quotedReadMaterial = Regex("`+|~{3,}|\"|“|‘|(?<![\\p{L}\\p{N}])'")
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
                cursor = quotedReadEnd(request, quoted.range.first, quoted.range.last + 1)
            }
        }

        private fun quotedReadEnd(request: String, start: Int, contentStart: Int): Int {
            val opening = request[start]
            var cursor = contentStart
            if (opening == '`' || opening == '~') {
                val delimiterLength = contentStart - start
                val fence = delimiterLength >= 3 && startsCodeFenceLine(request, start)
                while (cursor < request.length) {
                    val runStart = request.indexOf(opening, cursor)
                    if (runStart < 0) return request.length
                    var end = runStart + 1
                    while (end < request.length && request[end] == opening) end++
                    cursor = end
                    val lengthMatches = if (fence) end - runStart >= delimiterLength else end - runStart == delimiterLength
                    if (lengthMatches && (!fence || (startsCodeFenceLine(request, runStart) && endsCodeFenceLine(request, end)))) return end
                }
                return request.length
            }

            val closing = when (opening) {
                '“' -> '”'
                '‘' -> '’'
                else -> opening
            }
            while (cursor < request.length) {
                if (request[cursor] == '\\') {
                    cursor += if (cursor + 1 < request.length) 2 else 1
                    continue
                }
                if (request[cursor] == closing) {
                    val apostrophe = (closing == '\'' || closing == '’') &&
                        cursor > 0 &&
                        cursor + 1 < request.length &&
                        isQuoteWordCharacter(Character.codePointBefore(request, cursor)) &&
                        isQuoteWordCharacter(Character.codePointAt(request, cursor + 1))
                    if (!apostrophe) return cursor + 1
                }
                cursor++
            }
            // A missing closer keeps the rest of the input inside quoted data.
            return request.length
        }

        private fun isQuoteWordCharacter(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
            Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
            else -> Character.isLetterOrDigit(codePoint)
        }

        private fun startsCodeFenceLine(request: String, offset: Int): Boolean {
            var prior = offset - 1
            var spaces = 0
            while (prior >= 0 && request[prior] == ' ' && spaces < 4) {
                prior--
                spaces++
            }
            return spaces <= 3 && (prior < 0 || request[prior] == '\r' || request[prior] == '\n')
        }

        private fun endsCodeFenceLine(request: String, offset: Int): Boolean {
            var following = offset
            while (following < request.length && (request[following] == ' ' || request[following] == '\t')) following++
            return following == request.length || request[following] == '\r' || request[following] == '\n'
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
        fun forRequest(request: String, contexts: List<GitHubPlanningContext>, compact: Boolean = false): String {
            if (CurrentRequestContract.from(request).conversational || contexts.isEmpty()) return ""
            val lookup = GitHubLookupRoute.from(request)
            val repositoryIntent = lookup != null ||
                GitHubOperationTool.taskTools(request).isNotEmpty() ||
                selectedRepositoryReadIntent(request) ||
                Regex("(?i)^(?:please\\s+)?(?:continue|resume|retry|try again|finish(?: it| that| this)?|fix(?: it| that| this)?|go ahead|do it|proceed|check again)[.!?\\s]*$").matches(request.trim())
            if (!repositoryIntent) return ""
            return buildString {
                append("Selected GitHub executor targets (application data; these do not grant access):\n")
                contexts.distinct().forEach { append(it.record()).append('\n') }
                if (GitHubCapabilityRequest.from(request) != null) {
                    append("Answer from this chat's current tool schemas, authorized discovery pages and the documented feature index. A capability question asks for the interface; it does not request execution.")
                    return@buildString
                }
                if (GitHubCodeResearch.matches(request)) {
                    append("Use the selected repository map and admitted source tools to locate the implementation. Search definitions for a named symbol or paths and literal text for a feature. Follow the returned source_read_call and verify the exact lines, callers or tests at the same revision before describing behavior.")
                    return@buildString
                }
                if (lookup != null) {
                    append("Use the selected repository for the requested ${lookup.operation} ${lookup.action} read. Use its admitted facade or equivalent official GitHub read with the exact arguments declared by that schema. If none is available, check authorized discovery and report the missing read. Verify the observation and follow its exact continuation when more records are requested.")
                    return@buildString
                }
                if (compact) {
                    append("Use these selected defaults; newer successful resolve receipts supersede old heads. Ask only for missing or ambiguous targets. Copy exact continuation_call tool/arguments. Preserve approvals, unknown-write reconciliation and scoped receipt evidence. Discovery is not execution or verification. Act on established source when changes are authorized.")
                    return@buildString
                }
                append("Use the selected target for omitted owner/repo/ref. A newer successful resolve receipt supersedes the recorded head. Do not ask for a target already selected, invent owner/repo values, or reinterpret a workflow preset as a repository. If selection is missing or ambiguous, use authorized repository discovery or ask the user to select the exact target before dependent calls. Test fixtures are owned disposable branches/files inside the verified target, never guessed repositories.\n")
                append("Copy each continuation_call's exact tool and arguments. Listing inventory, advertising schemas, executing operations and passing acceptance criteria are different counts. Use recorded coverage fields; never infer test success from discovery or an HTTP success. A 404 does not establish deletion or insufficient OAuth scopes; accepted OAuth scopes describe endpoint requirements, not missing grants.")
                append(" Reuse retained branch observations and pinned source ranges. Once the requested edit's target source, repository instructions and acceptance criteria are established, make the next authorized working-branch or patch operation; only perform another discovery read for a specific missing prerequisite. A coding task is still unfinished when only reads and searches have completed.")
            }
        }
    }
}
