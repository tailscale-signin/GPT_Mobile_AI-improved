package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubRepositoryBindingTest {
    private val boundSha = "a".repeat(40)
    private val historicalSha = "b".repeat(40)
    private val newerSha = "c".repeat(40)
    private val tree = "d".repeat(40)
    private val binding = GitHubConversationContext("connection", "owner", "repo", "main", boundSha, "profile")

    private fun commit(sha: String) = buildJsonObject {
        put("sha", sha)
        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", tree) }) })
    }

    @Test fun historicalFirstAndHistoricalRefreshPreserveTheFrozenTaskSelection() = runTest {
        val observed = mutableListOf<String>()
        val session = GitHubRepositorySession(
            "account",
            "connection",
            "conversation",
            "task",
            GitHubRepositoryStateStore(),
            request = { path ->
                observed += path
                commit(path.substringAfterLast('/'))
            },
            commit = { _, _ -> error("Read-only fixture") },
            initialRepositoryContext = binding
        )
        for (refresh in listOf(false, true)) {
            val historical = session.resolve(
                "owner",
                "repo",
                buildJsonObject {
                    put("commit_sha", historicalSha)
                    put("refresh", refresh)
                }
            )
            assertEquals(historicalSha, historical.commitSha)
            val selected = requireNotNull(session.snapshot())
            assertEquals(boundSha, selected.commitSha)
            assertEquals("main", selected.ref)
        }
        assertEquals("/repos/owner/repo/commits/$boundSha", observed.first())
        assertEquals(1, observed.count { it.endsWith(boundSha) })
        assertFalse(observed.any { it.endsWith("/main") })
    }

    @Test fun concurrentExplicitSelectionWinsOverAnInFlightInitialBinding() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = GitHubRepositorySession(
            "account",
            "connection",
            "conversation",
            "task",
            GitHubRepositoryStateStore(),
            request = { path ->
                val selector = path.substringAfterLast('/')
                if (selector == boundSha) {
                    started.complete(Unit)
                    release.await()
                }
                commit(if (selector == "feature") newerSha else selector)
            },
            commit = { _, _ -> error("Read-only fixture") },
            initialRepositoryContext = binding
        )
        val historical = async { session.resolve("owner", "repo", buildJsonObject { put("commit_sha", historicalSha) }) }
        started.await()
        session.resolve(
            "owner",
            "repo",
            buildJsonObject {
                put("ref", "feature")
                put("refresh", true)
            },
            selectContext = true
        )
        release.complete(Unit)
        assertEquals(historicalSha, historical.await().commitSha)
        val selected = requireNotNull(session.snapshot())
        assertEquals(newerSha, selected.commitSha)
        assertEquals("feature", selected.ref)
    }

    @Test fun anUnrelatedRepositoryCannotBorrowTheInitialBinding() = runTest {
        val observed = mutableListOf<String>()
        val session = GitHubRepositorySession(
            "account",
            "connection",
            "conversation",
            "task",
            GitHubRepositoryStateStore(),
            request = { path ->
                observed += path
                commit(historicalSha)
            },
            commit = { _, _ -> error("Read-only fixture") },
            initialRepositoryContext = binding
        )
        session.resolve("other", "source", buildJsonObject { put("commit_sha", historicalSha) })
        assertEquals(listOf("/repos/other/source/commits/$historicalSha"), observed)
        assertEquals("other", session.snapshot()?.owner)
    }
}
