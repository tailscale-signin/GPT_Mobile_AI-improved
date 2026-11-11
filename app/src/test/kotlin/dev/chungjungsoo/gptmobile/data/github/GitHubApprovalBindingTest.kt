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

    @Test fun automaticCommitApprovalIsRestrictedToOwnedSelfTestFixturePaths() {
        val fixtureBranch = "codex-self-test/${"b".repeat(20)}"
        val fixture = listOf(EngineeringFilePatch("docs/codex-self-test/${"b".repeat(20)}/fixture.txt", "phase: created", "phase: staged"))
        assertTrue(eligibleAutomaticEngineeringPatch(fixtureBranch, "main", fixture))
        assertFalse(eligibleAutomaticEngineeringPatch("fix/normal-change", "main", fixture))
        assertFalse(eligibleAutomaticEngineeringPatch(fixtureBranch, "main", listOf(fixture.single().copy(path = "app/src/Main.kt"))))
    }

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
            val receipt = requireNotNull(GitHubApprovalBinding.receipt(restored, "saved-task", workspace, branch, "Repair CI", publication(true), false))
            assertEquals(42, receipt.sourceMessageId)
            assertEquals(sha, receipt.sha)
            assertEquals("Repair CI", receipt.summary)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectedReceiptRequiresExplicitDecisionAndCancellationBeforePublication() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val branch = "fix/saved-patch"
        GitHubApprovalBinding.bind(store, "task", workspace, branch, 31, 42)
        assertNull(GitHubApprovalBinding.receipt(store, "task", workspace, branch, "Repair CI", null, true))
        GitHubApprovalBinding.markDecision(store, "task", workspace, branch, automatic = false)
        assertNull(GitHubApprovalBinding.receipt(store, "task", workspace, branch, "Repair CI", null, false))
        assertNull(GitHubApprovalBinding.receipt(store, "task", workspace.copy(connectionUid = "another"), branch, "Repair CI", null, true))
        val receipt = requireNotNull(GitHubApprovalBinding.receipt(store, "task", workspace, branch, "Repair CI", null, true))
        assertEquals(31, receipt.chatId)
        assertNull(receipt.sha)
    }

    @Test fun approvalLifecycleAndFixtureChoicePersistAgainstTheExactPatchBinding() = runBlocking {
        val directory = Files.createTempDirectory("github-approval-events").toFile()
        try {
            val store = GitHubRepositoryStateStore(directory)
            val branch = "codex-self-test/" + "c".repeat(20)
            val base = "a".repeat(40)
            val patch = "d".repeat(64)
            GitHubApprovalBinding.bind(store, "task-events", workspace, branch, 31, 42, expectedHeadSha = base, patchDigest = patch)
            GitHubApprovalBinding.recordEvent(store, "task-events", "approval_card_created")
            GitHubApprovalBinding.recordEvent(store, "task-events", "approval_card_shown")
            GitHubApprovalBinding.recordEvent(store, "task-events", "approval_card_clicked", buildJsonObject { put("choice", "REJECT") })
            GitHubApprovalBinding.markDecision(store, "task-events", workspace, branch, automatic = false)
            GitHubApprovalBinding.keepFixture(store, "task-events", workspace, branch)

            val restored = GitHubRepositoryStateStore(directory)
            assertTrue(GitHubApprovalBinding.fixtureKept(restored, "task-events"))
            assertEquals(
                listOf("approval_card_created", "approval_card_shown", "approval_card_clicked", "approval_card_rejected", "approval_card_keep_fixture"),
                GitHubApprovalBinding.events(restored, "task-events").map { it["event"]?.toString()?.trim('"') }
            )
            val conflict = runCatching {
                GitHubApprovalBinding.bind(restored, "task-events", workspace, branch, 31, 42, expectedHeadSha = base, patchDigest = "e".repeat(64))
            }.exceptionOrNull()
            assertTrue(conflict is IllegalArgumentException)
            assertFalse(GitHubApprovalBinding.fixtureKept(restored, "other-task"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun localCancellationReceiptResolvesOnlyTheExactRejectedPatchAndKeepsItsLocalOrigin() = runBlocking {
        val store = GitHubRepositoryStateStore()
        val branch = "codex-self-test/${"e".repeat(20)}"
        val expectedHead = "a".repeat(40)
        val patchDigest = "b".repeat(64)
        val staged = buildJsonObject {
            put("tool", "github_write")
            put("receipt_id", "staged-receipt")
            put("binding", "task-binding")
            put("repository", "owner/repo")
            put("trusted", true)
            put("success", true)
            put("arguments", buildJsonObject {
                put("action", "commit")
                put("branch", branch)
                put("expected_head_sha", expectedHead)
            })
            put("result", buildJsonObject {
                put("engineering_task_id", "cancelled-task")
                put("status", "AWAITING_APPROVAL")
                put("committed", false)
                put("chat_id", 31)
                put("source_message_id", 42)
                put("branch", branch)
                put("expected_head_sha", expectedHead)
                put("patch_digest", patchDigest)
            })
        }
        GitHubApprovalBinding.bind(store, "cancelled-task", workspace, branch, 31, 42, expectedHeadSha = expectedHead, patchDigest = patchDigest)
        GitHubApprovalBinding.recordEvent(store, "cancelled-task", "approval_card_clicked", buildJsonObject { put("choice", "REJECT") })
        assertNull(GitHubApprovalBinding.localCancellationReceipt(store, workspace, staged))
        GitHubApprovalBinding.markDecision(store, "cancelled-task", workspace, branch, automatic = false, cancellationConfirmed = true)
        val cancellation = requireNotNull(GitHubApprovalBinding.localCancellationReceipt(store, workspace, staged))
        assertEquals("LOCAL_DURABLE_APPROVAL_EVENT", cancellation["evidence_origin"]?.toString()?.trim('"'))
        assertEquals("CANCELLED_BEFORE_PUBLICATION", ((cancellation["result"] as kotlinx.serialization.json.JsonObject)["approval_state"])?.toString()?.trim('"'))
        assertTrue(GitHubPendingApproval.snapshot(mapOf("staged" to staged, "cancelled" to cancellation)).isEmpty())
        GitHubApprovalBinding.keepFixture(store, "cancelled-task", workspace, branch)
        val keepDecision = requireNotNull(GitHubApprovalBinding.localFixtureDecisionReceipt(store, workspace, staged))
        assertEquals("approval_card_keep_fixture", keepDecision["local_resolution_event"]?.toString()?.trim('"'))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), (keepDecision["result"] as kotlinx.serialization.json.JsonObject)["keep_fixture"])
        val wrongPatch = JsonObject(staged + mapOf("result" to JsonObject(staged.getValue("result").let { (it as JsonObject) + mapOf("patch_digest" to kotlinx.serialization.json.JsonPrimitive("c".repeat(64))) })))
        assertNull(GitHubApprovalBinding.localCancellationReceipt(store, workspace, wrongPatch))
    }

    @Test fun approvalDedupeKeyChangesForAnyDifferentRunBinding() {
        val branch = "codex-self-test/fixture"
        val sha = "a".repeat(40)
        val patch = "b".repeat(64)
        val key = GitHubApprovalBinding.dedupeKey(31, "Owner/Repo", branch, sha, patch, "task")
        assertEquals(key, GitHubApprovalBinding.dedupeKey(31, "owner/repo", branch, sha, patch, "task"))
        assertFalse(key == GitHubApprovalBinding.dedupeKey(32, "owner/repo", branch, sha, patch, "task"))
        assertFalse(key == GitHubApprovalBinding.dedupeKey(31, "owner/repo", "$branch-2", sha, patch, "task"))
        assertFalse(key == GitHubApprovalBinding.dedupeKey(31, "owner/repo", branch, "c".repeat(40), patch, "task"))
        assertFalse(key == GitHubApprovalBinding.dedupeKey(31, "owner/repo", branch, sha, "d".repeat(64), "task"))
    }
}
