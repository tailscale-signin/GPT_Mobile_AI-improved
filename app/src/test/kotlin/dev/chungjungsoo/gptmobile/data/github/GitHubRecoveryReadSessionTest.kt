package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.delegation.DelegationFallback
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRecoveryReadSessionTest {
    private val scope = GitHubConversationContext("connection", "owner", "repo", "main", "a".repeat(40), "primary")
    private val search = GitHubOperationTool.definition("github_search", "github_search__native")
    private val read = GitHubOperationTool.definition("github_read", "github_read__native")
    private val selection = GitHubRecoveryReadSession.Selection("approved-profile", "recovery-model", """{"kind":"search","term":"retryTask"}""")
    private val calls = mutableListOf<List<AgentToolExchange>>()
    private val primary = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.Completed).also { calls += listOf(exchanges) }
    }

    @Test fun secondProfileSelectsOnePinnedReadAndTheOriginalPrimaryReceivesItsResult() = runTest {
        var selections = 0
        val session = GitHubRecoveryReadSession(primary, scope, setOf(search.name), "recovery-call", select = {
            assertEquals(listOf(search), it)
            selections++
            selection
        }, admit = { choice, _ -> choice.profileUid == "approved-profile" })
        val tools = listOf(search, read)
        val events = session.streamRound(tools, emptyList()).toList()
        assertTrue(calls.isEmpty())
        val call = events.filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(search.name, call.name)
        assertEquals(JsonPrimitive(scope.owner), call.arguments["owner"])
        assertEquals(JsonPrimitive(scope.repo), call.arguments["repo"])
        assertEquals(JsonPrimitive(scope.commitSha), call.arguments["commit_sha"])
        assertEquals(JsonPrimitive(2), call.arguments["max_new_blobs"])
        val result = AgentToolResult(call.callId, ToolResultContent.Text("src/Retry.kt"), false, executionReceipt = JsonObject(mapOf("head_sha" to JsonPrimitive(scope.commitSha))))
        val completed = listOf(AgentToolExchange(listOf(call), listOf(result)))
        assertEquals(listOf(ProviderEvent.Completed), session.streamRound(tools, completed).toList())
        assertEquals(1, selections)
        assertEquals(result, calls.single().single().results.single())
    }

    @Test fun staleAuthorityHiddenOrDuplicateToolsAndUnsafeSelectionsReturnControlToPrimary() = runTest {
        val call = requireNotNull(GitHubRecoveryReadSession.parse(selection.response, scope, listOf(search), "prior"))
        suspend fun checkAdmission(tools: List<AgentToolDefinition>, previous: List<AgentToolExchange>, body: String, allowed: Boolean) {
            val session = GitHubRecoveryReadSession(primary, scope, setOf(search.name), "next", { selection.copy(response = body) }, { _, _ -> allowed })
            assertEquals(listOf(ProviderEvent.Completed), session.streamRound(tools, previous).toList())
        }
        checkAdmission(listOf(search), emptyList(), selection.response, false)
        checkAdmission(listOf(search), listOf(AgentToolExchange(listOf(call), emptyList())), selection.response, true)
        checkAdmission(listOf(search.copy(modelVisible = false)), emptyList(), selection.response, true)
        checkAdmission(listOf(search), emptyList(), """{"kind":"search","term":"Retry","repo":"other"}""", true)
        checkAdmission(listOf(search), emptyList(), """{"kind":"write","path":"foo"}""", true)
        assertEquals(5, calls.size)
    }

    @Test fun parentRunnerExecutesTheBoundedSelectionAndResumesWithVerifiedEvidence() = runTest {
        var executions = 0
        val reader = object : AgentTool {
            override val definition = search
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                assertEquals(JsonPrimitive(scope.commitSha), arguments["commit_sha"])
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("src/Retry.kt"), false, executionReceipt = JsonObject(mapOf("head_sha" to JsonPrimitive(scope.commitSha))))
            }
        }
        val writer = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(
                ProviderEvent.TextDelta((exchanges.single().results.single().content as ToolResultContent.Text).text),
                ProviderEvent.Completed
            )
        }
        val escalated = GitHubRecoveryReadSession(writer, scope, setOf(search.name), "one-read", { selection }, { _, _ -> true })
        val events = AgentRunner(AgentRunLimits(maxRounds = 2, maxToolCalls = 1)).run(escalated, listOf(reader)).toList()
        val finished = events.filterIsInstance<AgentRunEvent.ToolFinished>().single()
        assertEquals(1, executions)
        assertEquals(JsonPrimitive(scope.commitSha), finished.result.executionReceipt?.get("head_sha"))
        assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { (it.event as? ProviderEvent.TextDelta)?.text == "src/Retry.kt" })
    }

    @Test fun recoveryTransportRetryCannotInvokeTheAlternateProfileTwiceOrForwardPriorReceipts() = runTest {
        val session = GitHubRecoveryReadSession.singleGeneration(primary)
        val prior = AgentToolExchange(emptyList(), listOf(AgentToolResult("old", ToolResultContent.Text("private source"), false)))
        assertEquals(listOf(ProviderEvent.Completed), session.streamRound(listOf(search), listOf(prior)).toList())
        val retry = session.streamRound(emptyList(), emptyList()).toList().single() as ProviderEvent.Failed
        assertEquals("GITHUB_RECOVERY_PROFILE_LIMIT", retry.errorCode)
        assertEquals(listOf(emptyList<AgentToolExchange>()), calls)
    }

    @Test fun rangeSelectionIsBoundedAndCannotOverrideScopeOrSpecifyAnArbitraryTool() {
        fun parse(body: String) = GitHubRecoveryReadSession.parse(body, scope, listOf(read), "one")
        val call = requireNotNull(parse("""{"kind":"read","path":"src/file.kt","line":12}"""))
        val range = call.arguments.getValue("ranges").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(32), range["max_lines"])
        assertEquals(JsonPrimitive(12), range["start_line"])
        assertEquals(JsonPrimitive("src/file.kt"), range["path"])
        assertNull(parse("""{"kind":"read","path":"../private","line":1}"""))
        assertNull(parse("""{"kind":"read","path":"file","line":0}"""))
        assertNull(parse("""{"kind":"read","path":"file","line":1,"ref":"main"}"""))
        assertNull(parse("""{"kind":"search","term":"src"}"""))
    }

    @Test fun executionRequiresDistinctNamedConsentAndExplicitRemoteWorkers() {
        val owner = PlatformV2(uid = scope.mainProfileUid, name = "Primary", model = "primary-model")
        val local = PlatformV2(uid = "local", name = "Device", model = "local-model", compatibleType = ClientType.LITERT_LM)
        val cloud = PlatformV2(uid = "remote", name = "Cloud", model = "stronger", compatibleType = ClientType.OPENAI)
        val profiles = listOf(owner, local, cloud)
        val policy = ModelDelegationSettings(enabled = true, approvedWorkerUids = listOf(local.uid, cloud.uid), fallbackPolicy = DelegationFallback.APPROVED_ORDER)
        val options = GitHubChatProfileOverride(intelligenceLevel = GitHubIntelligenceLevel.MAXIMUM_INTELLIGENCE)
        fun choices(remote: Boolean) = GitHubRecoveryConsult.candidates(options, scope, owner, profiles, policy.withRemoteWorkersAllowed(remote), execution = true)
        val localOnly = choices(false)
        assertFalse(cloud in localOnly.values)
        val localGrant = localOnly.entries.single { it.value == local }.key
        assertNotEquals(localGrant, GitHubRecoveryConsult.consent(scope, owner, local))
        val cloudGrant = choices(true).entries.single { it.value == cloud }.key
        assertNull(options.recoveryExecutionConsent)
        assertNull(localOnly[cloudGrant])
        assertNull(GitHubRecoveryConsult.candidates(options, scope.copy(ref = "other"), owner, profiles, policy.withRemoteWorkersAllowed(true), execution = true)[cloudGrant])
        assertNull(GitHubRecoveryConsult.candidates(options, scope, owner, listOf(cloud.copy(model = "changed")), policy.withRemoteWorkersAllowed(true), execution = true)[cloudGrant])
        assertNull(GitHubRecoveryConsult.candidates(options, scope, owner, profiles, policy.copy(fallbackPolicy = DelegationFallback.ASK).withRemoteWorkersAllowed(true), execution = true)[cloudGrant])
    }
}
