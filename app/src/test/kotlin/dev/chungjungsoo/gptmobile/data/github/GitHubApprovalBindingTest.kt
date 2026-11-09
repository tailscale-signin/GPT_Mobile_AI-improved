package dev.chungjungsoo.gptmobile.data.github

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApprovalBindingTest {
    private val workspace = GitHubConversationContext("account", "owner", "repo", "main", "a".repeat(40), "selected-profile")

    @Test fun exactFixtureCardSurvivesRestartOnlyWithinItsOriginalWorkspace() = runBlocking {
        val directory = Files.createTempDirectory("github-approval-binding").toFile()
        try {
            val branch = "codex-self-test/" + "b".repeat(20)
            GitHubApprovalBinding.bind(GitHubRepositoryStateStore(directory), "original-task", workspace, branch, 31, 42)
            val restored = GitHubRepositoryStateStore(directory)
            assertTrue(GitHubApprovalBinding.belongs(restored, "original-task", workspace, branch))
            assertFalse(GitHubApprovalBinding.belongs(restored, "other-task", workspace, branch))
            assertFalse(GitHubApprovalBinding.belongs(restored, "original-task", workspace.copy(mainProfileUid = "other-profile"), branch))
            assertFalse(GitHubApprovalBinding.belongs(restored, "original-task", workspace.copy(connectionUid = "other-account"), branch))
            assertFalse(GitHubApprovalBinding.belongs(restored, "original-task", workspace.copy(ref = "other-work"), branch))
            assertFalse(GitHubApprovalBinding.belongs(restored, "original-task", workspace, "other-fixture"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun publicationWakeupKeepsOriginalChatAndStopsOnlyAfterQueueAcceptance() = runBlocking {
        val directory = Files.createTempDirectory("github-publication-wakeup").toFile()
        try {
            val branch = "codex-self-test/" + "b".repeat(20)
            val sha = "c".repeat(40)
            val contract = GitHubApprovalBinding.profileContract("OPENROUTER", "https://provider.example/v1", null, true)
            GitHubApprovalBinding.bind(GitHubRepositoryStateStore(directory), "saved-task", workspace, branch, 31, 42, "saved-model", contract)
            fun publication(verified: Boolean) = buildJsonObject {
                put("engineering_task_id", "saved-task")
                put("branch", branch)
                put("sha", sha)
                put("parent_sha", workspace.commitSha)
                put("approval_state", "PUBLISHED")
                put("remote_state_verified", verified)
            }
            val restored = GitHubRepositoryStateStore(directory)
            assertNull(GitHubApprovalBinding.continuation(restored, "saved-task", workspace, branch, publication(false)))
            val continuation = requireNotNull(GitHubApprovalBinding.continuation(restored, "saved-task", workspace, branch, publication(true)))
            assertEquals(31, continuation.chatId)
            assertEquals(42, continuation.sourceMessageId)
            assertEquals(sha, continuation.sha)
            assertTrue(GitHubApprovalBinding.matchesProfile(continuation, "saved-model", "OPENROUTER", "https://provider.example/v1", null, true))
            assertFalse(GitHubApprovalBinding.matchesProfile(continuation, "changed-model", "OPENROUTER", "https://provider.example/v1", null, true))
            assertFalse(GitHubApprovalBinding.matchesProfile(continuation, "saved-model", "OPENROUTER", "https://changed.example/v1", null, true))
            assertFalse(GitHubApprovalBinding.matchesProfile(continuation, "saved-model", "OPENROUTER", "https://provider.example/v1", null, false))
            assertNull(GitHubApprovalBinding.continuation(restored, "saved-task", workspace.copy(mainProfileUid = "another"), branch, publication(true)))
            GitHubApprovalBinding.continuationQueued(restored, "saved-task", sha)
            assertNull(GitHubApprovalBinding.continuation(GitHubRepositoryStateStore(directory), "saved-task", workspace, branch, publication(true)))
        } finally {
            directory.deleteRecursively()
        }
    }
}
