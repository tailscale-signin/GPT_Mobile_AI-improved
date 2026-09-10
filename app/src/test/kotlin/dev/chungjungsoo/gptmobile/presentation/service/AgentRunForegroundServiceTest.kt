package dev.chungjungsoo.gptmobile.presentation.service

import android.content.Context
import android.content.res.Resources
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AgentRunForegroundServiceTest {
    @Test
    fun `completion notification posts only for background nonempty to empty transition`() {
        assertTrue(shouldNotifyAgentRunsCompleted(wasActive = true, isActive = false, isAppBackground = true))
    }

    @Test
    fun `completion notification skips initial empty and foreground completion`() {
        assertFalse(shouldNotifyAgentRunsCompleted(wasActive = false, isActive = false, isAppBackground = true))
        assertFalse(shouldNotifyAgentRunsCompleted(wasActive = true, isActive = false, isAppBackground = false))
        assertFalse(shouldNotifyAgentRunsCompleted(wasActive = true, isActive = true, isAppBackground = true))
    }

    @Test
    fun `destroy interrupts only while service still owns active runs`() {
        assertTrue(shouldInterruptAgentRunsOnDestroy(stoppedBecauseInactive = false, hasActiveRuns = true))
        assertFalse(shouldInterruptAgentRunsOnDestroy(stoppedBecauseInactive = true, hasActiveRuns = false))
        assertFalse(shouldInterruptAgentRunsOnDestroy(stoppedBecauseInactive = false, hasActiveRuns = false))
    }

    @Test
    fun `resolveNotificationContentText shows phase prefill for single run`() {
        val mockContext = mock(Context::class.java)
        `when`(mockContext.getString(R.string.agent_run_phase_prefill)).thenReturn("Processing prompt…")

        val runs = listOf(
            ActiveAgentRun(runId = "run-1", chatId = 1, profileUid = "p1", phase = LocalInferencePhase.PREFILL)
        )

        val text = resolveNotificationContentText(mockContext, runs)
        assertEquals("Processing prompt…", text)
    }

    @Test
    fun `resolveNotificationContentText shows phase generating for single run`() {
        val mockContext = mock(Context::class.java)
        `when`(mockContext.getString(R.string.agent_run_phase_generating)).thenReturn("Generating response…")

        val runs = listOf(
            ActiveAgentRun(runId = "run-1", chatId = 1, profileUid = "p1", phase = LocalInferencePhase.GENERATING)
        )

        val text = resolveNotificationContentText(mockContext, runs)
        assertEquals("Generating response…", text)
    }

    @Test
    fun `resolveNotificationContentText falls back to plural count when multiple runs active`() {
        val mockContext = mock(Context::class.java)
        val mockResources = mock(Resources::class.java)
        `when`(mockContext.resources).thenReturn(mockResources)
        `when`(mockResources.getQuantityString(R.plurals.agent_runs_active, 2, 2)).thenReturn("2 agent runs active")

        val runs = listOf(
            ActiveAgentRun(runId = "run-1", chatId = 1, profileUid = "p1", phase = LocalInferencePhase.PREFILL),
            ActiveAgentRun(runId = "run-2", chatId = 2, profileUid = "p2", phase = LocalInferencePhase.GENERATING)
        )

        val text = resolveNotificationContentText(mockContext, runs)
        assertEquals("2 agent runs active", text)
    }
}
