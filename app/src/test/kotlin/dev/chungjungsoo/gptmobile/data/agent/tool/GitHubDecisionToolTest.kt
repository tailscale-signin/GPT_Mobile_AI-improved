package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    @Test fun hydratedEngineeringMemoryAndRealDecisionCatalogAllowAVisibleProviderReply() = runBlocking {
        val profile = PlatformV2(uid = "profile", name = "Local Llama", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8080/v1", model = "local")
        val decision = GitHubDecisionTool(GitHubChatDecisions(GitHubRepositoryStateStore()), 1, 2, profile.uid, workspace, false, { true })
        val resolved = ResolvedAgentTool(decision, null, "GitHub decisions", decision.definition.name, decision.definition.name)
        val memory = PersonalDataProvenance(
            sourceToolId = "github_engineering_memory",
            sourceProfileUid = profile.uid,
            sourceRef = "repository-memory",
            categories = setOf(PersonalDataCategory.PRIVATE_CONTENT),
            retentionChatId = 1,
            localOnly = true,
            sourceConnectionUid = workspace.connectionUid
        )
        val context = PersonalDataContext(1, null, authorizedLocalOnlyGitHubConnectionUid = workspace.connectionUid)
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(tools.any { it.name == GitHubDecisionTool.NAME })
                emit(ProviderEvent.TextDelta("I can continue with your repository request."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = withContext(context) {
            context.add(listOf(memory))
            validateGitHubToolDestinations(profile, listOf(resolved))
            context.validateModel(profile)
            AgentRunner().run(session, listOf(decision)).toList()
        }
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { (it.event as? ProviderEvent.TextDelta)?.text?.isNotBlank() == true })
        assertFalse(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.Failed })
    }

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
