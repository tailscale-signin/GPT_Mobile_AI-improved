package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GitHubNativeDecisionReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApprovalRetryTest {
    @Test fun unchangedApprovalDirectsTheUserToTheExactCardWithoutOpeningGenericRetry() {
        val message = requireNotNull(gitHubApprovalRetryBlock("WAITING_FOR_APPROVAL: The staged patch is retained.", pendingCard = true))
        assertTrue(message.contains("existing approval card"))
        assertTrue(message.contains("Approve & Commit, Reject"))
        assertTrue(message.contains("Continue and Retry are paused"))
        assertTrue(gitHubApprovalRetryBlock("Review the saved change to continue.", pendingCard = true) != null)
    }

    @Test fun resolvedOrMissingCardAllowsLocalReconciliationOfTheSavedTask() {
        assertEquals(null, gitHubApprovalRetryBlock("WAITING_FOR_APPROVAL: The staged patch is retained.", pendingCard = false))
        assertEquals(null, gitHubApprovalRetryBlock("OUTPUT_CONTINUATION_PENDING_TOOLS", pendingCard = true))
        assertEquals(null, gitHubApprovalRetryBlock(null, pendingCard = true))
    }

    @Test fun approvedContinuationShowsOnlyTheSavedDecisionSummary() {
        val sha = "a".repeat(40)
        val request = "Commit $sha was approved and published. Continue from the saved task using this exact commit. Subscribe with github_watch_checks when CI is pending; do not repeat the commit."
        val text = "GitHub workspace: owner/repo\nBranch: main\n\n$request"
        val receipt = GitHubNativeDecisionReceipt("patch", 1, 2, "fix/feature", "Repair feature\nAdditional details", sha, false)
        assertEquals(GitHubDecisionBubbleData("Accepted commit", "Repair feature · fix/feature"), gitHubCommitDecisionBubble(text, listOf(receipt)))
        assertEquals(GitHubDecisionBubbleData("Saved commit continuation", sha.take(10)), gitHubCommitDecisionBubble(text, emptyList()))
        assertEquals(null, gitHubCommitDecisionBubble(request, listOf(receipt)))
    }
}
