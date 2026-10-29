package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubEngineeringApi
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCheckWatchTest {
    private val head = "a".repeat(40)
    private val watch = GitHubCheckWatch("watch", 1, "connection", "profile", "owner", "repo", 758, null, head, 1)
    private fun repository() = GitHubCheckWatchRepository(mockk(relaxed = true), mockk(), mockk(), mockk(), mockk(), mockk())

    @Test fun movedPullRequestHeadStopsBeforeCheckingTheOldRevision() = runTest {
        val paths = mutableListOf<String>()
        val api = object : GitHubEngineeringApi({ "token" }) {
            override suspend fun request(path: String, method: String, body: JSONObject?): String {
                paths += path
                return """{"head":{"sha":"${"b".repeat(40)}"}}"""
            }
        }
        val result = repository().inspect(watch, api)
        assertEquals("STALE", result.status)
        assertTrue(result.terminal)
        assertEquals(listOf("/repos/owner/repo/pulls/758"), paths)
    }

    @Test fun emptyChecksNeverReportSuccess() = runTest {
        val api = object : GitHubEngineeringApi({ "token" }) {
            override suspend fun request(path: String, method: String, body: JSONObject?): String = when {
                "/pulls/" in path -> """{"head":{"sha":"$head"}}"""
                "/check-runs" in path -> """{"total_count":0,"check_runs":[]}"""
                else -> """{"total_count":0,"state":"pending","statuses":[]}"""
            }
        }
        val result = repository().inspect(watch, api)
        assertEquals("NONE", result.status)
        assertFalse(result.terminal)
    }

    @Test fun incompleteOrSkippedWorkflowCannotBecomePassed() {
        fun status(raw: String) = workflowWatchStatus(Json.parseToJsonElement(raw).jsonObject)
        assertEquals("RUNNING", status("""{"status":"in_progress","conclusion":null}"""))
        assertEquals("SKIPPED", status("""{"status":"completed","conclusion":"skipped"}"""))
        assertEquals("UNKNOWN", status("""{"status":"completed","conclusion":null}"""))
        assertEquals("FAILED", status("""{"status":"completed","conclusion":"failure"}"""))
        assertEquals("PASSED", status("""{"status":"completed","conclusion":"success"}"""))
    }

    @Test fun preloadedContextIsBoundedAndIncludesThePinnedHeadAndDirtyState() {
        val context = GitHubConversationContext("connection", "owner", "repo", "feature", head, "profile", stagedFileCount = 2, recentCommits = List(100) { "b".repeat(500) + "\nuntrusted continuation" }, observedAtMillis = 123)
        val prompt = context.promptContext()
        assertTrue(prompt.contains("Pinned head: $head"))
        assertTrue(prompt.contains("2 changed file(s)"))
        assertFalse(prompt.contains("untrusted continuation"))
        assertTrue(prompt.length < 2000)
    }
}
