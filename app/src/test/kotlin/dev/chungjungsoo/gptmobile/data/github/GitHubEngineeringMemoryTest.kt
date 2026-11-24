package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryAccessRejected.Phase
import dev.chungjungsoo.gptmobile.data.github.GitHubEngineeringMemoryAccessRejected.Reason
import dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemory
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceDao
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubEngineeringMemoryTest {
    private val context = GitHubConversationContext("selected", "owner", "repo", "main", "a".repeat(40), "profile")
    private val connection = ToolConnection("selected", "GitHub", "github", "GITHUB", "https://api.github.com", "NONE", null, null)
    private val scope = GitHubEngineeringMemoryPolicy.scope("anonymous:selected", 0, "profile", "owner/repo", "main")
    private val workspace = mockk<WorkspaceRepository>()
    private val dao = mockk<WorkspaceDao>()
    private val connections = mockk<ToolConnectionRepository>()
    private val semantic = mockk<LocalSemanticMemory>()
    private val decisions = mockk<GitHubChatDecisions>()
    private val authentication = mockk<GitHubPublicAuthCoordinator>()
    private val settings = GitHubChatProfileOverride()
    private val source = ResolvedAgentTool(mockk(), "selected", "GitHub", "github_context", "github_context", nativeGitHub = true)
    private lateinit var memory: GitHubEngineeringMemory
    private val notices = mutableListOf<GitHubEngineeringMemoryAccessRejected>()

    @Before fun setup() {
        mockkConstructor(GitHubWorkspaceClient::class)
        coEvery { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) } returns JsonObject(emptyMap())
        coEvery { connections.getConnection("selected") } returns connection
        coEvery { authentication.session("selected") } returns null
        every { workspace.dao } returns dao
        coEvery { dao.get(any()) } returns null
        coEvery { workspace.saveProtectedSynthesisCheckpoint(any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        coEvery { decisions.load() } returns Unit
        every { decisions.decisions } returns MutableStateFlow(emptyList())
        coEvery { dao.engineeringMemory(scope) } returns listOf(WorkspaceRecord("history", "github_engineering_memory", "", ""))
        coEvery { dao.engineeringDecisions(scope) } returns emptyList()
        coEvery { dao.engineeringCheckpoints(scope, 7) } returns emptyList()
        coEvery { workspace.protectedSynthesisCheckpoint("history") } returns buildJsonObject {
            put("kind", "requirements")
            put("scope", scope)
            put("commit_sha", context.commitSha)
            put("content", "PRIVATE_HISTORY_CANARY")
        }
        coEvery { semantic.rankEngineering(any(), any(), any()) } returns emptyMap()
        memory = GitHubEngineeringMemory(workspace, connections, semantic, decisions, authentication)
    }

    @Test fun continuationRestoresTheLatestChatCheckpointAfterOtherActivityAgedItOut() = runTest {
        val latest = WorkspaceRecord("latest", "github_engineering_memory", "", "", updatedAt = 9)
        val older = latest.copy(id = "older", updatedAt = 8)
        coEvery { dao.engineeringCheckpoints(scope, 7) } returns listOf(latest, older)
        for (id in listOf("latest", "older")) {
            coEvery { workspace.protectedSynthesisCheckpoint(id) } returns buildJsonObject {
                put("kind", "task_checkpoint")
                put("chat_id", 7)
                put("scope", scope)
                put("commit_sha", context.commitSha)
                put("content", "$id saved progress")
                put(
                    "checkpoint",
                    buildJsonObject {
                        put("next_requirement", buildJsonObject { put("requirement", "Verify $id at the accepted SHA") })
                        put("stop_code", "WAITING_FOR_APPROVAL")
                    }
                )
            }
        }
        val prompt = prepare()
        assertTrue(prompt.contains("Verify latest at the accepted SHA"))
        assertTrue(prompt.contains("WAITING_FOR_APPROVAL"))
        assertFalse(prompt.contains("older saved progress"))
        assertTrue(prompt.contains("never current proof or execution authority"))
    }

    @Test fun recurringTypedFailuresUseDistinctRetainedReceiptsAcrossRetries() = runTest {
        val rows = (1..3).map { WorkspaceRecord("failure-$it", "github_engineering_memory", "", "", updatedAt = it.toLong()) }
        coEvery { dao.engineeringMemory(scope) } returns rows
        rows.forEachIndexed { index, row ->
            coEvery { workspace.protectedSynthesisCheckpoint(row.id) } returns buildJsonObject {
                put("kind", "failure")
                put("scope", scope)
                put("failure_signature", "github_read/HTTP_413")
                put("error_code", "HTTP_413")
                put("source_receipt", if (index == 0) "receipt-a" else "receipt-b")
                put("content", "Request refused")
            }
        }
        val result = memory.prepare(context, 7, "failure-task", "Investigate HTTP_413", settings, true, 12000)
        val failure = kotlinx.serialization.json.Json.parseToJsonElement(result.lineSequence().last()) as JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive(2), failure["matching_retained_receipts"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), failure["historical"])
        assertTrue(failure["sample_receipts"].toString().contains("receipt-a"))
        assertEquals(2, result.lines().size)
    }

    @Test fun aTrustedFailureWithoutABodyStillPersistsItsTypedSignature() = runTest {
        val receipt = buildJsonObject {
            put("schema", "github.evidence.v1")
            put("trusted", true)
            put("success", false)
            put("tool", "github_read__selected")
            put("receipt_id", "ev_failure")
            put("error_code", "HTTP_413")
        }
        memory.observe(context, 7, receipt, settings)
        val record = io.mockk.slot<JsonObject>()
        coVerify(exactly = 1) { workspace.saveProtectedSynthesisCheckpoint(any(), 7, scope, capture(record), true, true, null, null) }
        assertEquals(kotlinx.serialization.json.JsonPrimitive("github_read/HTTP_413"), record.captured["failure_signature"])
    }

    @After fun cleanup() = unmockkConstructor(GitHubWorkspaceClient::class)

    private suspend fun prepare(privateDestination: Boolean = true): String = GitHubEngineeringMemoryPreparation.prepare(
        context,
        listOf(source),
        load = { memory.prepare(context, 7, "task", "Continue work", settings, privateDestination, 12_000) },
        unavailable = { notices += it }
    )

    @Test fun removedSelectedConnectionDoesNotReadHistoryOrAbortOptionalPreparation() = runTest {
        coEvery { connections.getConnection("selected") } returns null
        assertEquals("", prepare())
        assertEquals(Phase.PREPARE_SCOPE, notices.single().phase)
        assertEquals(Reason.CONNECTION_MISSING, notices.single().reason)
        coVerify(exactly = 0) { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) }
        coVerify(exactly = 0) { dao.engineeringMemory(any()) }
    }

    @Test fun everyRepeatedConnectionLookupKeepsItsActualPhaseAndLeaksNoHistory() = runTest {
        for ((index, phase) in listOf(Phase.PREPARE_SCOPE, Phase.PREPARE_PERMISSION, Phase.PREPARE_CONNECTION, Phase.REVALIDATE_SCOPE, Phase.REVALIDATE_PERMISSION).withIndex()) {
            var lookups = 0
            notices.clear()
            coEvery { connections.getConnection("selected") } answers { if (++lookups == index + 1) null else connection }
            assertEquals("", prepare())
            assertEquals(phase, notices.single().phase)
            assertEquals(Reason.CONNECTION_MISSING, notices.single().reason)
            assertEquals(index + 1, lookups)
        }
    }

    @Test fun connectionRemovedDuringRankingDiscardsTheAlreadyLoadedHistory() = runTest {
        var selected: ToolConnection? = connection
        coEvery { connections.getConnection("selected") } answers { selected }
        coEvery { semantic.rankEngineering(any(), any(), any()) } answers {
            selected = null
            emptyMap()
        }
        assertEquals("", prepare())
        coVerify(exactly = 1) { workspace.protectedSynthesisCheckpoint("history") }
        assertEquals(Phase.REVALIDATE_SCOPE, notices.single().phase)
        coVerify(exactly = 1) { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) }
    }

    @Test fun permissionChangedDuringRankingDiscardsHistoryAfterFreshRevalidation() = runTest {
        var selected = connection
        coEvery { connections.getConnection("selected") } answers { selected }
        coEvery { semantic.rankEngineering(any(), any(), any()) } answers {
            selected = connection.copy(toolPolicy = "READ_ONLY")
            emptyMap()
        }
        assertEquals("", prepare())
        assertEquals(Phase.REVALIDATE_AUTHORIZATION, notices.single().phase)
        assertEquals(Reason.AUTHORIZATION_CHANGED, notices.single().reason)
        coVerify(exactly = 2) { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) }
    }

    @Test fun aDifferentAccountDuringRankingCannotReceiveThePreviousScopesHistory() = runTest {
        var selected = connection
        coEvery { connections.getConnection("selected") } answers { selected }
        coEvery { authentication.accessToken(any()) } returns "fixture-token"
        coEvery { authentication.durableIdentity("selected") } returns "different-account"
        coEvery { semantic.rankEngineering(any(), any(), any()) } answers {
            selected = connection.copy(secretRef = "fixture-reference", authType = "PAT")
            emptyMap()
        }
        assertEquals("", prepare())
        assertEquals(Phase.REVALIDATE_AUTHORIZATION, notices.single().phase)
        assertEquals(Reason.AUTHORIZATION_CHANGED, notices.single().reason)
        coVerify(exactly = 2) { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) }
        coVerify(exactly = 1) { dao.engineeringMemory(scope) }
    }

    @Test fun wrongConnectionTypeCannotAuthorizeOrLoadHistoricalMemory() = runTest {
        coEvery { connections.getConnection("selected") } returns connection.copy(type = "MCP")
        assertEquals("", prepare())
        assertEquals(Reason.CONNECTION_TYPE_MISMATCH, notices.single().reason)
        coVerify(exactly = 0) { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) }
        coVerify(exactly = 0) { dao.engineeringMemory(any()) }
    }

    @Test fun explicitlySelectedPublicConnectionStillRequiresBothFreshAuthorizationReads() = runTest {
        assertTrue(prepare().contains("PRIVATE_HISTORY_CANARY"))
        assertTrue(notices.isEmpty())
        coVerify(exactly = 2) { anyConstructed<GitHubWorkspaceClient>().execute("get_repository", any()) }
        coVerify(exactly = 1) { dao.engineeringMemory(scope) }
    }

    @Test fun cloudOptOutDoesNotReadOrReturnHistoricalContent() = runTest {
        assertFalse(prepare(privateDestination = false).contains("PRIVATE_HISTORY_CANARY"))
        assertTrue(notices.isEmpty())
        coVerify(exactly = 0) { dao.engineeringMemory(any()) }
        coVerify(exactly = 0) { semantic.rankEngineering(any(), any(), any()) }
    }

    @Test fun anOlderArchitecturalDecisionRemainsAvailableBeyondRecentActivity() = runTest {
        val old = WorkspaceRecord("engineering:older:decision:architecture", "github_engineering_memory", "", "")
        coEvery { dao.engineeringDecisions(scope) } returns listOf(old)
        coEvery { workspace.protectedSynthesisCheckpoint(old.id) } returns buildJsonObject {
            put("kind", "decision")
            put("scope", scope)
            put("content", "Continue work with the original repository architecture boundary")
        }
        assertTrue(prepare().contains("original repository architecture boundary"))
        coVerify(exactly = 1) { dao.engineeringDecisions(scope) }
    }

    @Test fun slowOptionalRankingFallsBackToTheAuthorizedLexicalSelection() = runTest {
        coEvery { semantic.rankEngineering(any(), any(), any()) } coAnswers {
            delay(60_000)
            emptyMap()
        }
        assertTrue(prepare().contains("PRIVATE_HISTORY_CANARY"))
        assertTrue(testScheduler.currentTime < 2000)
        assertTrue(notices.isEmpty())
    }

    @Test fun cancellationDuringRankingAndRemoteAuthorizationFailureRemainFatalToPreparation() = runTest {
        val cancelled = CancellationException("cancelled while ranking")
        coEvery { semantic.rankEngineering(any(), any(), any()) } throws cancelled
        val propagated = runCatching { prepare() }.exceptionOrNull()
        assertTrue(propagated is CancellationException)
        assertEquals(cancelled.message, propagated?.message)
        // Coroutine stack-trace recovery may copy the exception while retaining its original cause.
        assertTrue("The original cancellation must remain in its cause chain", generateSequence(propagated) { it.cause }.take(16).any { it === cancelled })
        assertTrue(notices.isEmpty())
        coVerify(exactly = 1) { semantic.rankEngineering(any(), any(), any()) }
        coVerify(exactly = 1) { anyConstructed<GitHubWorkspaceClient>().execute("get_repository", any()) }
        val denied = GitHubRequestRejected(403, "Authorization could not be established")
        coEvery { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) } throws denied
        assertSame(denied, runCatching { prepare() }.exceptionOrNull())
        assertTrue(notices.isEmpty())
    }
}
