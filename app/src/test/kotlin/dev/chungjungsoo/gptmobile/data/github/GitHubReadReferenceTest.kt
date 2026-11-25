package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadReferenceTest {
    private val workspace = GitHubConversationContext("account", "owner", "repo", "feature", "a".repeat(40), "profile")
    private val header = "GitHub workspace: owner/repo\r\nBranch: feature\r\n\r\n"
    private fun parse(text: String) = GitHubReadReference.parse(text, workspace)

    @Test fun explicitObjectIsAStandaloneReadEvenAfterADifferentTask() {
        assertEquals("Show PR #43", parse("What about PR #43?")?.question)
        assertEquals("Show issue #71", parse(header + "How about issue #71?")?.question)
        assertEquals("Show PR #43", parse("what about pull request #43")?.resolve("Close issue #40", null, workspace)?.question)
        assertEquals(GitHubLookupRoute.PULL_REQUEST, GitHubLookupRoute.from(requireNotNull(parse("What about PR #43?")).question))
    }

    @Test fun aBareNumberCanUseOnlyOneTypeFromAnAdjacentReadInTheSameWorkspace() {
        val bare = requireNotNull(parse(header + "What about #43?"))
        assertEquals("Show issue #43", bare.resolve("Show issue #42", workspace, workspace).question)
        assertEquals("Show PR #43", bare.resolve(header + "List open pull requests", workspace, workspace).question)
        assertEquals("Show PR #43", bare.resolve("What about PR #42?", workspace.copy(commitSha = "b".repeat(40)), workspace).question)
        for (prior in listOf("Merge PR #42", "Show PR #42 and issue #41", "Search for \"issue #40\"", "The weather", "What about #42?", "> Show PR #42", header + header + "Show PR #42", header.replace("feature", "main") + "Show PR #42")) {
            assertNull(prior, bare.resolve(prior, workspace, workspace).kind)
        }
        for (prior in listOf(null, workspace.copy(connectionUid = "other"), workspace.copy(ref = "main"), workspace.copy(repo = "other"), workspace.copy(mainProfileUid = "other"))) {
            assertNull(bare.resolve("Show PR #42", prior, workspace).kind)
        }
    }

    @Test fun bothRepliesOfferedByTheClarificationResolveWithoutPriorHistory() {
        val question = requireNotNull(parse("What about #43?")).question
        val replies = Regex("(?:issue|PR) #[0-9]+").findAll(question).map { it.value }.toList()
        assertEquals(listOf("Show issue #43", "Show PR #43"), replies.map { parse(it)?.question })
    }

    @Test fun aOneWordChoiceRequiresTheAdjacentAppClarificationAndItsRecordedWorkspace() = runTest {
        val bare = MessageV2(10, 4, content = "What about #43?", platformType = null)
        val question = requireNotNull(parse(bare.content)).question
        val item = AssistantTimelineItem(AssistantTimelineItemType.TEXT, question, outputLineage = GitHubReadReference.clarificationLineage)
        val answer = MessageV2(11, 4, content = question, platformType = "profile", linkedMessageId = 10, timeline = listOf(item))
        val reply = MessageV2(12, 4, content = "PR", platformType = null)
        suspend fun resolved(
            current: MessageV2 = reply,
            response: MessageV2 = answer,
            scope: GitHubConversationContext? = workspace,
            prior: MessageV2 = bare,
            extra: List<MessageV2> = emptyList()
        ): GitHubReadReference? = GitHubReadReference.forTurn(current, listOf(prior, current), listOf(listOf(response)), listOf(prior, response, current) + extra, workspace) { scope }
        assertEquals("Show PR #43", resolved()?.question)
        assertEquals("Show issue #43", resolved(reply.copy(content = header + "issue"))?.question)
        assertNull(resolved(reply.copy(content = "merge PR")))
        assertNull(resolved(reply.copy(content = "issue and PR")))
        assertNull(resolved(scope = null))
        assertNull(resolved(scope = workspace.copy(ref = "main")))
        assertNull(resolved(scope = workspace.copy(connectionUid = "elsewhere")))
        assertNull(resolved(response = answer.copy(linkedMessageId = 1)))
        assertNull(resolved(response = answer.copy(platformType = "other")))
        assertNull(resolved(response = answer.copy(timeline = listOf(item.copy(outputLineage = OutputLineage(OutputOrigin.MODEL_TEXT))))))
        assertNull(resolved(prior = bare.copy(content = "What about #44?")))
        assertNull(resolved(extra = listOf(bare.copy(id = 11, content = "different task"))))
        assertNull(GitHubReadReference.forTurn(reply, listOf(reply), emptyList(), listOf(bare, answer, reply), workspace) { workspace })
    }

    @Test fun writesQuotedTextUnrelatedRequestsAndMixedReferencesNeverInherit() {
        for (text in listOf("Merge #43", "What about merging PR #43?", "What about PR #43? Merge it", "What about #43 and #45?", "Is issue #43 reproducible?", "What about #0?", "What about #2147483648?", "\"What about #43?\"", "> What about PR #43?", "What about #43?\nDo it", header.replace("owner/repo", "other/repo") + "What about #43?")) {
            assertNull(text, parse(text))
        }
    }

    @Test fun executionIsLimitedToTheSelectedObjectAndReadActions() {
        val target = requireNotNull(parse("What about issue #43?"))
        fun args(action: String, field: String = "issue_number", number: Int = 43) = mapOf("action" to JsonPrimitive(action), field to JsonPrimitive(number))
        assertTrue(target.permits("github_issue", JsonObject(args("context"))))
        assertTrue(target.permits("github_issue", JsonObject(args("headers"))))
        assertFalse(target.permits("github_issue", JsonObject(args("update"))))
        assertFalse(target.permits("github_issue", JsonObject(args("context", number = 44))))
        assertFalse(target.permits("github_pr", JsonObject(args("context", "pull_number"))))
        assertFalse(target.permits("github_issue", JsonObject(args("context") + ("view_cursor" to JsonPrimitive("other")))))
    }
}
