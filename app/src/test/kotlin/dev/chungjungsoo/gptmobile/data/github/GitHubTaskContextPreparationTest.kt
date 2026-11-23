package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemory
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTaskContextPreparationTest {
    private val context = GitHubConversationContext("selected", "owner", "repo", "main", "a".repeat(40), "profile")
    private val connection = ToolConnection("selected", "GitHub", "github", "GITHUB", "https://api.github.com", "NONE", null, null)
    private val maps = mockk<PhoneRepositoryGraphRepository>()
    private val semantic = mockk<LocalSemanticMemory>()
    private val connections = mockk<ToolConnectionRepository>()
    private val source = ResolvedAgentTool(mockk(), "selected", "GitHub", "github_context", "github_context", nativeGitHub = true)
    private val preparation = GitHubTaskContextPreparation(maps, semantic, connections)
    private val receipt = buildJsonObject { put("selected_ranges", JsonArray(listOf(buildJsonObject { put("excerpt", "AUTHORIZED_SOURCE_CANARY") }))) }

    @Test fun disabledSmallAndUnauthorizedTurnsDoNotReadAnyRepository() = runTest {
        assertEquals("", preparation.prepare(context, 1, "parser task", GitHubContextVariant.CURRENT, 12_000, listOf(source), GitHubEngineeringMemoryMode.HYBRID))
        assertEquals("", preparation.prepare(context, 1, "parser task", GitHubContextVariant.TASK, 4096, listOf(source), GitHubEngineeringMemoryMode.HYBRID))
        assertEquals("", preparation.prepare(context, 1, "parser task", GitHubContextVariant.TASK, 12_000, emptyList(), GitHubEngineeringMemoryMode.HYBRID))
        coVerify(exactly = 0) { connections.getConnection(any()) }
        coVerify(exactly = 0) { maps.executeForMap(any(), any(), any()) }
    }

    @Test fun preparedSourceIsInjectedAsUntrustedDataAfterCurrentConnectionRevalidation() = runTest {
        coEvery { connections.getConnection("selected") } returns connection
        coEvery { maps.executeForMap(context, any(), true) } returns response()
        val result = preparation.prepare(context, 1, "parser task", GitHubContextVariant.TASK, 12_000, listOf(source), GitHubEngineeringMemoryMode.HYBRID)
        assertTrue(result.contains("untrusted source data"))
        assertTrue(result.contains("AUTHORIZED_SOURCE_CANARY"))
        coVerify(exactly = 2) { connections.getConnection("selected") }
        coVerify(exactly = 0) { semantic.rankEngineering(any(), any(), any()) }
    }

    @Test fun connectionChangeAfterPreparationDiscardsSource() = runTest {
        coEvery { connections.getConnection("selected") } returnsMany listOf(connection, connection.copy(updatedAt = connection.updatedAt + 1))
        coEvery { maps.executeForMap(context, any(), true) } returns response()
        assertEquals("", preparation.prepare(context, 1, "parser task", GitHubContextVariant.TASK, 12_000, listOf(source), GitHubEngineeringMemoryMode.HYBRID))
    }

    @Test fun cancellationStillCancelsTheOriginalTask() = runTest {
        coEvery { connections.getConnection("selected") } returns connection
        val cancelled = CancellationException("task cancelled")
        coEvery { maps.executeForMap(context, any(), true) } throws cancelled
        assertSame(cancelled, runCatching { preparation.prepare(context, 1, "parser task", GitHubContextVariant.TASK, 12_000, listOf(source), GitHubEngineeringMemoryMode.HYBRID) }.exceptionOrNull())
    }

    @Test fun semanticScopeReusesUnchangedSourcesAcrossHeadsButSeparatesAccounts() {
        val original = GitHubTaskContextPreparation.semanticScope(context, 1, 10, "account-a")
        assertEquals(original, GitHubTaskContextPreparation.semanticScope(context.copy(commitSha = "b".repeat(40)), 1, 10, "account-a"))
        assertNotEquals(original, GitHubTaskContextPreparation.semanticScope(context, 1, 10, "account-b"))
        assertNotEquals(original, GitHubTaskContextPreparation.semanticScope(context, 2, 10, "account-a"))
        assertNotEquals(original, GitHubTaskContextPreparation.semanticScope(context, 1, 11, "account-a"))
    }

    @Test fun sessionOnlySemanticPreparationNeverReadsOrWritesHistoricalVectors() = runTest {
        coEvery { connections.getConnection("selected") } returns connection
        coEvery { maps.executeForMap(context, any(), true) } coAnswers {
            val ranker = requireNotNull(currentCoroutineContext()[GitHubTaskContextRanking])
            assertEquals("SESSION_ONLY_MEMORY_POLICY", ranker.fallbackReason)
            assertNull(ranker.rank("parser task", mapOf("Parser.kt" to "current source"), "admitted-account"))
            response()
        }
        assertTrue(preparation.prepare(context, 1, "parser task", GitHubContextVariant.SEMANTIC, 12_000, listOf(source), GitHubEngineeringMemoryMode.SESSION_ONLY).contains("AUTHORIZED_SOURCE_CANARY"))
        coVerify(exactly = 0) { semantic.rankEngineering(any(), any(), any()) }
    }

    @Test fun retainedSemanticPreparationUsesOnlyTheAdmittedAccountScope() = runTest {
        coEvery { connections.getConnection("selected") } returns connection
        coEvery { semantic.rankEngineering(any(), any(), any()) } returns mapOf("Parser.kt" to 1.0)
        coEvery { maps.executeForMap(context, any(), true) } coAnswers {
            val ranker = requireNotNull(currentCoroutineContext()[GitHubTaskContextRanking])
            assertNull(ranker.fallbackReason)
            assertEquals(mapOf("Parser.kt" to 1.0), ranker.rank("parser task", mapOf("Parser.kt" to "current source"), "admitted-account"))
            response()
        }
        for (mode in listOf(GitHubEngineeringMemoryMode.PERSISTENT, GitHubEngineeringMemoryMode.HYBRID)) {
            assertTrue(preparation.prepare(context, 1, "parser task", GitHubContextVariant.SEMANTIC, 12_000, listOf(source), mode).contains("AUTHORIZED_SOURCE_CANARY"))
        }
        coVerify(exactly = 2) { semantic.rankEngineering("parser task", GitHubTaskContextPreparation.semanticScope(context, 1, connection.updatedAt, "admitted-account"), mapOf("Parser.kt" to "current source")) }
    }

    private fun response() = GatewayGraphResponse(receipt, "task_context_bundle", "", "On this phone", durableIndexJobs = false, modelAccess = "NATIVE_GITHUB_AUTHORIZED")
}
