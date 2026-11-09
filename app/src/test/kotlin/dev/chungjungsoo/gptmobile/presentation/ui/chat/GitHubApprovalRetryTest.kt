package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApprovalRetryTest {
    @Test fun unchangedApprovalDirectsTheUserToTheExactCardWithoutOpeningGenericRetry() {
        val message = gitHubApprovalRetryBlock("WAITING_FOR_APPROVAL: The staged patch is retained.", pendingCard = true)
        assertTrue(message?.contains("approve or reject the saved patch") == true)
    }

    @Test fun resolvedOrMissingCardAllowsLocalReconciliationOfTheSavedTask() {
        assertEquals(null, gitHubApprovalRetryBlock("WAITING_FOR_APPROVAL: The staged patch is retained.", pendingCard = false))
        assertEquals(null, gitHubApprovalRetryBlock("OUTPUT_CONTINUATION_PENDING_TOOLS", pendingCard = true))
        assertEquals(null, gitHubApprovalRetryBlock(null, pendingCard = true))
    }
}
