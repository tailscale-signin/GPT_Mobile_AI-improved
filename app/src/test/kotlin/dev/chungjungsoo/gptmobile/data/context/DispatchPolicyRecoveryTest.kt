package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchPolicyRecoveryTest {
    @Test fun nextActionChangePreservesSpecificCauseAndRequiresRevalidationAfterDispatch() {
        val failure = DispatchPolicyChangedException(listOf("githubProfileOverride", "githubProfileOverride.nextActionPolicy"))
        val classified = ErrorClassification.classify(IllegalStateException("Stopped during continuation", failure))
        assertTrue(classified.userMessage.contains("next action policy changed"))
        assertTrue(classified.userMessage.contains("revalidate"))
        assertFalse(classified.userMessage.contains("Permissions changed"))
        assertFalse(classified.isRetryable)
        val fence = PreparationReplayFence()
        fence.dispatched()
        assertFalse(fence.canRebuild(failure, 0))
        assertFalse(fence.canResumeGitHubChecklist(failure, 0, durableChecklist = true))
    }

    @Test fun permissionRevocationCannotBeRelabeledAsAnAdvisoryPolicyChange() {
        val failure = DispatchPolicyChangedException(listOf("providerPermissions", "githubProfileOverride.nextActionPolicy"))
        assertTrue(failure.recoveryMessage().contains("permissions"))
        assertFalse(failure.recoveryMessage().contains("The next action policy changed."))
    }
}
