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
        coEvery { workspace.protectedSynthesisCheckpoint("history") } returns buildJsonObject {
            put("kind", "requirements")
            put("scope", scope)
            put("commit_sha", context.commitSha)
            put("content", "PRIVATE_HISTORY_CANARY")
        }
        coEvery { semantic.rankEngineering(any(), any(), any()) } returns emptyMap()
        memory = GitHubEngineeringMemory(workspace, connections, semantic, decisions, authentication)
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

    @Test fun cancellationDuringRankingAndRemoteAuthorizationFailureRemainFatalToPreparation() = runTest {
        val cancelled = CancellationException("cancelled while ranking")
        coEvery { semantic.rankEngineering(any(), any(), any()) } throws cancelled
        assertSame(cancelled, runCatching { prepare() }.exceptionOrNull())
        assertTrue(notices.isEmpty())
        val denied = GitHubRequestRejected(403, "Authorization could not be established")
        coEvery { anyConstructed<GitHubWorkspaceClient>().execute(any(), any()) } throws denied
        assertSame(denied, runCatching { prepare() }.exceptionOrNull())
        assertTrue(notices.isEmpty())
    }
}
