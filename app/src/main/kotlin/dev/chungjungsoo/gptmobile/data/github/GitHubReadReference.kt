package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A new object number inherits at most the kind of the immediately preceding read. */
internal data class GitHubReadReference(val number: Int, val kind: GitHubLookupRoute?) {
    val question: String get() = when (kind) {
        GitHubLookupRoute.ISSUE -> "Show issue #$number"
        GitHubLookupRoute.PULL_REQUEST -> "Show PR #$number"
        else -> "Do you mean issue #$number or PR #$number?"
    }

    fun resolve(previous: String, previousWorkspace: GitHubConversationContext?, workspace: GitHubConversationContext): GitHubReadReference {
        if (kind != null || previousWorkspace?.sameWorkspace(workspace) != true) return this
        val request = scopedText(previous, workspace) ?: return this
        val prior = parse(request, workspace)?.kind ?: GitHubLookupRoute.from(request)
        return copy(
            kind = when (prior) {
                GitHubLookupRoute.ISSUE, GitHubLookupRoute.ISSUES -> GitHubLookupRoute.ISSUE
                GitHubLookupRoute.PULL_REQUEST, GitHubLookupRoute.PULL_REQUESTS -> GitHubLookupRoute.PULL_REQUEST
                else -> null
            }
        )
    }

    fun permits(operation: String?, args: JsonObject): Boolean {
        val field = when (kind) {
            GitHubLookupRoute.PULL_REQUEST -> "pull_number"
            GitHubLookupRoute.ISSUE -> "issue_number"
            else -> return false
        }
        val id = args[field] as? JsonPrimitive ?: return false
        return operation == kind.operation &&
            !id.isString &&
            id.intOrNull == number &&
            (args["action"] == null || args["action"] == JsonPrimitive("context") || args["action"] == JsonPrimitive("headers")) &&
            args.keys.all { it == field || it in readFields }
    }

    companion object {
        private val readFields = setOf("owner", "repo", "action", "include_health", "include_files", "include_checks", "include_patches", "expected_head_sha", "expected_base_sha", "check_page", "page", "per_page", "response_detail", "verify_live")
        private val followUp = Regex("""(?:(?:(?:what|how) about )?(pr|pull request|issue) |(?:what|how) about )?#([1-9][0-9]{0,9})[?!.]?""", RegexOption.IGNORE_CASE)

        val clarificationLineage = OutputLineage(OutputOrigin.APPLICATION_REPORT)

        suspend fun forTurn(
            current: MessageV2,
            users: List<MessageV2>,
            assistants: List<List<MessageV2>>,
            history: List<MessageV2>,
            workspace: GitHubConversationContext,
            acceptedWorkspace: suspend (MessageV2) -> GitHubConversationContext?
        ): GitHubReadReference? {
            if (current.platformType != null || current.attachments.isNotEmpty() || users.lastOrNull() != current) return null
            val candidate = parse(current.content, workspace)
            if (candidate?.kind != null) return candidate
            val choice = when (scopedText(current.content, workspace)?.lowercase(java.util.Locale.ROOT)) {
                "issue" -> GitHubLookupRoute.ISSUE
                "pr", "pull request" -> GitHubLookupRoute.PULL_REQUEST
                else -> null
            }
            if (candidate == null && choice == null) return null
            // Require the same immediately preceding user message in both the transcript
            // and the history admitted to this request. Privacy-isolated turns have none.
            val admitted = users.getOrNull(users.lastIndex - 1) ?: return candidate
            val previous = history.asSequence().filter { it.platformType == null && it.chatId == current.chatId && it.id < current.id }
                .maxByOrNull { it.id }?.takeIf { it == admitted && it.attachments.isEmpty() } ?: return candidate
            val priorWorkspace = acceptedWorkspace(previous)
            if (priorWorkspace?.sameWorkspace(workspace) != true) return candidate
            if (candidate != null) return candidate.resolve(previous.content, priorWorkspace, workspace)
            val bare = parse(previous.content, workspace)?.takeIf { it.kind == null } ?: return null
            val answer = history.singleOrNull {
                it.linkedMessageId == previous.id &&
                    it.chatId == current.chatId &&
                    it.platformType == workspace.mainProfileUid &&
                    it.id > previous.id &&
                    it.id < current.id
            } ?: return null
            if (answer.content != bare.question || answer.revisions.isNotEmpty() || !assistants.flatten().contains(answer)) return null
            val text = answer.timeline.filter { it.type == AssistantTimelineItemType.TEXT && it.content.isNotEmpty() }
            if (text.singleOrNull()?.let { it.content == bare.question && it.outputLineage == clarificationLineage } != true) return null
            return bare.copy(kind = choice)
        }

        fun parse(text: String, workspace: GitHubConversationContext): GitHubReadReference? {
            val request = scopedText(text, workspace) ?: return null
            val parts = followUp.matchEntire(request)?.groupValues ?: return null
            val number = parts[2].toIntOrNull() ?: return null
            return GitHubReadReference(
                number,
                when (parts[1].lowercase(java.util.Locale.ROOT)) {
                    "pr", "pull request" -> GitHubLookupRoute.PULL_REQUEST
                    "issue" -> GitHubLookupRoute.ISSUE
                    else -> null
                }
            )
        }

        private fun scopedText(text: String, workspace: GitHubConversationContext): String? {
            if (GitHubTaskContinuity.sharesLookupWorkspace(text, text, workspace, workspace) != true) return null
            return GitHubTaskContinuity.request(text).takeUnless { it.startsWith("GitHub workspace:") }
        }
    }
}
