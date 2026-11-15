package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.isGitHubTool
import dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringMemoryPreparationTest {
    private val context = GitHubConversationContext("selected", "owner", "repo", "main", "a".repeat(40), "profile")

    private fun tool(uid: String?, native: Boolean, authenticated: Boolean = true, name: String = "github_context") = ResolvedAgentTool(
        tool = mockk(),
        connectionUid = uid,
        connectionName = "GitHub",
        realToolName = name,
        modelToolName = name,
        nativeGitHub = native,
        nativeGitHubAuthenticated = authenticated
    )

    @Test fun localDecisionOnlyDoesNotAttemptHistoryOrAbortTheChat() = runTest {
        val decision = tool(null, native = false, name = "github_decide")
        assertTrue(decision.isGitHubTool()) // The prior name-based preparation condition admitted this local tool.
        var loads = 0
        var notices = 0
        val text = GitHubEngineeringMemoryPreparation.prepare(
            context,
            listOf(decision),
            load = {
                loads++
                error("No GitHub source was authorized")
            },
            unavailable = { notices++ }
        )
        assertEquals("", text)
        assertEquals(0, loads)
        assertEquals(0, notices)
    }

    @Test fun documentationUnrelatedAndAnonymousFallbackToolsDoNotAuthorizeHistory() = runTest {
        for (candidate in listOf(tool("selected", false, name = "gitmcp__search"), tool("other-account", true), tool(null, true, false))) {
            assertFalse(GitHubEngineeringMemoryPreparation.eligible(context, listOf(candidate)))
            val text = GitHubEngineeringMemoryPreparation.prepare(context, listOf(candidate), load = { error("Unauthorized memory load") }, unavailable = { error("No load was attempted") })
            assertEquals("", text)
        }
    }

    @Test fun matchingAuthenticatedAndExplicitPublicConnectionsRetainTheirOwnAuthorizedMemory() = runTest {
        for (authenticated in listOf(true, false)) {
            val candidate = tool("selected", true, authenticated)
            assertTrue(GitHubEngineeringMemoryPreparation.eligible(context, listOf(candidate)))
            val text = GitHubEngineeringMemoryPreparation.prepare(context, listOf(candidate), load = { "authorized history" }, unavailable = { error("No rejection") })
            assertEquals("authorized history", text)
        }
    }

    @Test fun aTypedRejectionAfterSuspensionReturnsNoPreparedHistoryAndNotifiesOnce() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rejection = GitHubEngineeringMemoryAccessRejected(GitHubEngineeringMemoryAccessRejected.Phase.REVALIDATE_SCOPE, GitHubEngineeringMemoryAccessRejected.Reason.CONNECTION_MISSING)
        val notices = mutableListOf<GitHubEngineeringMemoryAccessRejected>()
        val result = async {
            GitHubEngineeringMemoryPreparation.prepare(
                context,
                listOf(tool("selected", true)),
                load = {
                    entered.complete(Unit)
                    release.await()
                    throw rejection
                },
                unavailable = { notices += it }
            )
        }
        entered.await()
        release.complete(Unit)
        assertEquals("", result.await())
        assertEquals(listOf(rejection), notices)
    }

    @Test fun cancellationFatalAndUntypedAuthorityOrTransportFailuresPropagateUnchanged() = runTest {
        val failures = listOf(
            CancellationException("cancelled"),
            AssertionError("fatal"),
            IOException("transport"),
            SecurityException("authority"),
            DispatchPolicyChangedException(listOf("profile"))
        )
        for (failure in failures) {
            var notices = 0
            val caught = runCatching {
                GitHubEngineeringMemoryPreparation.prepare(context, listOf(tool("selected", true)), load = { throw failure }, unavailable = { notices++ })
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(0, notices)
        }
    }
}
