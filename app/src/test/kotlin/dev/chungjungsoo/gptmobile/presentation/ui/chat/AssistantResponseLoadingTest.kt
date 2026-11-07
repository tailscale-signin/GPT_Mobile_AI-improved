package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponseLoadingTest {
    @Test fun everyTerminalOutcomeStopsProgressEvenWhenLiveLoadingStateIsDelayed() {
        AgentRunStatus.terminal.forEach { status ->
            assertFalse(status, shouldShowAssistantResponseProgress(platformLoading = true, hasActiveRun = true, status))
        }
    }

    @Test fun activeWorkKeepsProgressVisibleUntilATerminalResultArrives() {
        assertTrue(shouldShowAssistantResponseProgress(platformLoading = false, hasActiveRun = true, AgentRunStatus.RUNNING))
        assertTrue(shouldShowAssistantResponseProgress(platformLoading = true, hasActiveRun = false, null))
        assertFalse(shouldShowAssistantResponseProgress(platformLoading = false, hasActiveRun = false, null))
    }
}
