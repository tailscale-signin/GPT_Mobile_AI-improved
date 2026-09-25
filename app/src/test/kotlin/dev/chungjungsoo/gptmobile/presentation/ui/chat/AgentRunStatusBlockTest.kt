package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunStatusBlockTest {
    @Test
    fun `duration clamps clock skew and requires terminal timing`() {
        val run = AgentRun(
            runId = "run-1",
            chatId = 7,
            userMessageId = 1,
            assistantMessageId = 2,
            profileUid = "profile-1",
            providerSnapshot = "OPENAI",
            modelSnapshot = "model",
            startedAt = 20,
            completedAt = 10
        )

        assertEquals(0L, agentRunDurationSeconds(run))
        assertEquals(null, agentRunDurationSeconds(run.copy(completedAt = null)))
    }

    @Test
    fun `buildDiagnosticsHudText formats model latency and telemetry when debug mode enabled`() {
        val run = AgentRun(
            runId = "run-1",
            chatId = 7,
            userMessageId = 1,
            assistantMessageId = 2,
            profileUid = "profile-1",
            providerSnapshot = "OPENAI",
            modelSnapshot = "gpt-4o",
            startedAt = 10,
            completedAt = 14
        )

        val textWithTelemetry = buildDiagnosticsHudText(
            agentRun = run,
            telemetryNotice = "Local: 24.5 tok/s",
            debugMode = true
        )
        assertEquals("gpt-4o • 4s • Local: 24.5 tok/s", textWithTelemetry)

        val textWithoutTelemetry = buildDiagnosticsHudText(
            agentRun = run,
            telemetryNotice = null,
            debugMode = true
        )
        assertEquals("gpt-4o • 4s", textWithoutTelemetry)
    }

    @Test
    fun `buildDiagnosticsHudText hides telemetry when debug mode disabled`() {
        val run = AgentRun(
            runId = "run-1",
            chatId = 7,
            userMessageId = 1,
            assistantMessageId = 2,
            profileUid = "profile-1",
            providerSnapshot = "OPENAI",
            modelSnapshot = "gpt-4o",
            startedAt = 10,
            completedAt = 14
        )

        val result = buildDiagnosticsHudText(
            agentRun = run,
            telemetryNotice = "Local: 30 tok/s",
            debugMode = false
        )
        assertNull(result)

        val nullResult = buildDiagnosticsHudText(
            agentRun = run,
            telemetryNotice = null,
            debugMode = false
        )
        assertEquals(null, nullResult)
    }
}
