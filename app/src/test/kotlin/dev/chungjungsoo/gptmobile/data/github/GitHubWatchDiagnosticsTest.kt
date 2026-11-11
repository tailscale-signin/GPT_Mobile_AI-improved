package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubWatchDiagnosticsTest {
    private val watch = GitHubCheckWatch(
        "github-checks:" + "a".repeat(64), 13, "private-connection", "private-profile", "PrivateOwner", "private-repository",
        pullNumber = 17, headSha = "b".repeat(40), connectionUpdatedAt = 1, branch = "private-branch", permissionRevision = "private-revision"
    )

    @Test fun independentTargetsAndChangedConfigurationHaveDistinctSafeIdentities() {
        val first = GitHubWatchDiagnostics.metadata(watch, "POLL_STARTED", GitHubWatchTrigger.SUCCESSOR, "12345678-1234-1234-1234-123456789012", 0)
        val same = GitHubWatchDiagnostics.metadata(watch.copy(owner = "privateowner"), "POLL_COMPLETED", GitHubWatchTrigger.SUCCESSOR)
        assertEquals(first["target_hash"], same["target_hash"])
        assertNotEquals(first["target_hash"], GitHubWatchDiagnostics.metadata(watch.copy(pullNumber = 18), "POLL_STARTED", GitHubWatchTrigger.SUCCESSOR)["target_hash"])
        assertNotEquals(first["ref_hash"], GitHubWatchDiagnostics.metadata(watch.copy(headSha = "c".repeat(40)), "POLL_STARTED", GitHubWatchTrigger.SUCCESSOR)["ref_hash"])
        assertNotEquals(first["configuration_revision"], GitHubWatchDiagnostics.metadata(watch.copy(permissionRevision = "changed-permission"), "POLL_STARTED", GitHubWatchTrigger.SUCCESSOR)["configuration_revision"])
        for (private in listOf(watch.owner, watch.repo, watch.branch, watch.connectionUid, watch.profileUid, watch.headSha, watch.permissionRevision)) {
            assertFalse(first.toString().contains(private))
        }
    }

    @Test fun terminalConditionsAndExplicitRefreshAreReportedWithoutRawErrorText() {
        val terminal = GitHubWatchDiagnostics.metadata(watch.copy(status = "EXPIRED", detail = "private error body"), "TERMINAL", GitHubWatchTrigger.EXPLICIT_REFRESH)
        assertEquals(JsonPrimitive(true), terminal["terminal"])
        assertEquals(JsonPrimitive("EXPIRED"), terminal["terminal_condition"])
        assertEquals(JsonPrimitive("EXPLICIT_REFRESH"), terminal["trigger"])
        assertFalse(terminal.toString().contains("private error body"))
        val hostile = GitHubWatchDiagnostics.metadata(watch.copy(status = "private-status"), "private-event", GitHubWatchTrigger.SUCCESSOR, "private-work-id")
        assertFalse(hostile.toString().contains("private-"))
        assertFalse(hostile.containsKey("work_id"))
    }

    @Test fun aQueuedSuccessorOrTerminalWatchCannotAppendAnotherPollingChain() {
        assertTrue(GitHubWatchDiagnostics.shouldAppend(false, listOf("SUCCEEDED", "RUNNING")))
        assertFalse(GitHubWatchDiagnostics.shouldAppend(false, listOf("RUNNING", "ENQUEUED")))
        assertFalse(GitHubWatchDiagnostics.shouldAppend(false, listOf("RUNNING", "BLOCKED")))
        assertFalse(GitHubWatchDiagnostics.shouldAppend(true, listOf("SUCCEEDED")))
        assertTrue(GitHubWatchDiagnostics.shouldAppend(false, listOf("FAILED", "CANCELLED")))
    }
}
