package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolBatchCheckpoint
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataContext
import dev.chungjungsoo.gptmobile.data.permissions.PersonalDataProvenance
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

    @Test fun choiceParksRunnerAndRestoresTheOriginalInvocationWithoutAnotherPaidDiscoveryRound() = runBlocking {
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore())
        val tool = GitHubDecisionTool(decisions, 1, 2, "profile", workspace, false, { true })
        val checkpoint = ToolBatchCheckpoint(null, "original-task")
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                if (exchanges.isEmpty()) {
                    emit(ProviderEvent.ToolCall("original-choice-call", tool.definition.name, arguments))
                } else {
                    assertTrue(exchanges.any { exchange -> exchange.results.any { result -> result.content.toString().contains("Spacious") } })
                    emit(ProviderEvent.TextDelta("Continuing with the saved choice."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val parked = AgentRunner(checkpoint = checkpoint).run(session, listOf(tool)).toList()
        assertEquals(1, rounds)
        assertEquals(GitHubDecisionTool.PENDING, parked.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertEquals(listOf("original-choice-call"), checkpoint.recoverySnapshot().pending.map { it.callId })
        assertEquals(0, checkpoint.recoverySnapshot().unknownCount)
        val decision = decisions.decisions.value.single()
        assertTrue(decisions.respond(decision.id, 1, decision.workspace, 1).accepted)
        assertFalse(decisions.respond(decision.id, 1, decision.workspace, 0).accepted)
        val resumed = AgentRunner(checkpoint = checkpoint).run(session, listOf(tool)).toList()
        assertEquals(2, rounds)
        assertTrue(resumed.filterIsInstance<AgentRunEvent.Provider>().any { it.event is ProviderEvent.TextDelta })
        assertTrue(decisions.decisions.value.single().delivered)
    }

    @Test fun customBaselineOptionWaitsForAValidRevisionEvenWhenSuggestionsAreEnabled() = runBlocking {
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore())
        val tool = GitHubDecisionTool(decisions, 1, 2, "profile", workspace, false, { true }, useSuggestion = { true })
        val args = Json.parseToJsonElement("""{"question":"Choose a baseline","options":[{"label":"Before this commit"},{"label":"Choose a tag","enter_value":true}],"recommended":1}""").jsonObject
        val pending = tool.execute("baseline", args)
        assertEquals(GitHubDecisionTool.PENDING, pending.errorCode)
        assertTrue(decisions.waiting.value.isEmpty())
        val decision = decisions.decisions.value.single()
        val id = decision.id
        assertTrue(decision.options.last().enterValue)
        assertTrue(runCatching { decisions.respond(id, 1, decision.workspace, option = 1) }.isFailure)
        assertTrue(runCatching { decisions.respond(id, 1, decision.workspace, customAnswer = "invalid revision") }.isFailure)
        decisions.respond(id, 1, decision.workspace, customAnswer = "release/v18")
        assertFalse(tool.execute("baseline-resume", args).isError)
        assertEquals("release/v18", decisions.decisions.value.single().answer)
    }

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
        val pending = tool.execute("first", arguments)
        assertEquals(GitHubDecisionTool.PENDING, pending.errorCode)
        assertTrue(decisions.waiting.value.isEmpty())
        val id = decisions.decisions.value.single().id
        decisions.respond(id, 1, GitHubChatDecisions.workspaceKey(workspace), 1)
        val result = tool.execute("resumed", arguments)
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
        assertEquals(GitHubDecisionTool.PENDING, tool.execute("first", arguments).errorCode)
        val id = decisions.decisions.value.single().id
        allowed = false
        decisions.respond(id, 1, GitHubChatDecisions.workspaceKey(workspace), 0)
        assertTrue(runCatching { tool.execute("resume", arguments) }.isFailure)
        assertEquals("Compact", decisions.decisions.value.single().answer)
        assertFalse(decisions.decisions.value.single().delivered)
        assertTrue(runCatching { tool.execute("again", arguments) }.isFailure)
    }

    @Test fun stopIsAnExplicitTerminalResultAndMalformedQuestionsAreNotOffered() = runBlocking {
        val decisions = GitHubChatDecisions(GitHubRepositoryStateStore())
        val tool = GitHubDecisionTool(decisions, 1, 2, "profile", workspace, false, { true })
        assertTrue(runCatching { tool.execute("invalid", Json.parseToJsonElement("""{"question":"","options":[]}""").jsonObject) }.isFailure)
        assertTrue(decisions.decisions.value.isEmpty())
        assertEquals(GitHubDecisionTool.PENDING, tool.execute("stop", arguments).errorCode)
        val id = decisions.decisions.value.single().id
        decisions.dismiss(id, 1)
        val result = tool.execute("stop-resume", arguments)
        assertTrue(result.isError)
        assertEquals("GITHUB_USER_STOP", result.errorCode)
        assertFalse(result.dispatched)
    }
}
