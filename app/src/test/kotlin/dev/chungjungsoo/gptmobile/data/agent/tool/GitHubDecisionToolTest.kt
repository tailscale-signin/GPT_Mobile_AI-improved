package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubDecisionToolTest {
    private val workspace = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "profile")
    private val arguments = Json.parseToJsonElement("""{"question":"Which layout?","options":[{"label":"Compact"},{"label":"Spacious"}],"recommended":0}""").jsonObject

    @Test fun choiceReturnsUserInputWithoutMutationAuthorityAndReplaysTheSavedAnswer() = runBlocking {
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore())
        var offers = 0
        val tool = GitHubDecisionTool(decisions, 1, 2, "profile", workspace, false, { true }, { offers++ })
        val request = async { tool.execute("first", arguments) }
        val id = decisions.waiting.first { it.isNotEmpty() }.single()
        assertFalse(request.isCompleted)
        decisions.respond(id, 1, GitHubChatDecisions.workspaceKey(workspace), 1)
        val result = request.await()
        assertFalse(result.isError)
        assertFalse(result.dispatched)
        val receipt = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals("Spacious", receipt.getValue("user_answer").jsonPrimitive.content)
        assertTrue(receipt.getValue("instruction").jsonPrimitive.content.contains("not write approval"))
        assertTrue(decisions.decisions.value.single().delivered)
        assertEquals(result.content, tool.execute("retry", arguments).content)
        assertEquals(1, offers)
    }

    @Test fun revokingWorkspaceWhileWaitingRetainsAnswerAndRejectsFurtherExecution() = runBlocking {
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore())
        var allowed = true
        val tool = GitHubDecisionTool(decisions, 1, 2, "profile", workspace, false, { allowed })
        val request = async { runCatching { tool.execute("first", arguments) } }
        val id = decisions.waiting.first { it.isNotEmpty() }.single()
        allowed = false
        decisions.respond(id, 1, GitHubChatDecisions.workspaceKey(workspace), 0)
        assertTrue(request.await().isFailure)
        assertEquals("Compact", decisions.decisions.value.single().answer)
        assertFalse(decisions.decisions.value.single().delivered)
        assertTrue(runCatching { tool.execute("again", arguments) }.isFailure)
    }

    @Test fun stopIsAnExplicitTerminalResultAndMalformedQuestionsAreNotOffered() = runBlocking {
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore())
        val tool = GitHubDecisionTool(decisions, 1, 2, "profile", workspace, false, { true })
        assertTrue(runCatching { tool.execute("invalid", Json.parseToJsonElement("""{"question":"","options":[]}""").jsonObject) }.isFailure)
        assertTrue(decisions.decisions.value.isEmpty())
        val request = async { tool.execute("stop", arguments) }
        val id = decisions.waiting.first { it.isNotEmpty() }.single()
        decisions.dismiss(id, 1)
        val result = request.await()
        assertTrue(result.isError)
        assertEquals("GITHUB_USER_STOP", result.errorCode)
        assertFalse(result.dispatched)
    }
}
