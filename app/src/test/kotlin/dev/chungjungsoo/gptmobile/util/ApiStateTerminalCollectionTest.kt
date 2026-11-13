package dev.chungjungsoo.gptmobile.util

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiStateTerminalCollectionTest {
    @Test fun reportingLinkageFailureCannotReplaceTheProviderRejection() = runBlocking {
        val details = buildJsonObject {
            put("http_status", 429)
            put("failed_stage", "model_invocation")
            put("remote_acceptance", "NO")
            put("invocation_id", "attempt")
        }
        var saved = emptyList<AssistantTimelineItem>()
        mockkObject(IssueReportIncidents)
        try {
            every { IssueReportIncidents.chatFailure("RATE_LIMIT", false, null, any(), "attempt") } throws NoClassDefFoundError()
            val outcome = flowOf(ApiState.Error("Provider returned HTTP 429", errorCode = "RATE_LIMIT", failureDetails = details, invocationId = "attempt"))
                .collectApiStateUpdates(onUpdate = { _, _, timeline -> saved = timeline })
            assertEquals(ApiStateFlowOutcome.Failed("Provider returned HTTP 429", errorCode = "RATE_LIMIT"), outcome)
            val failure = saved.single { it.profileFailure != null }.profileFailure
            assertEquals("RATE_LIMIT", failure?.category)
            assertEquals("attempt", failure?.invocationId)
        } finally {
            unmockkObject(IssueReportIncidents)
        }
    }

    @Test
    fun doneClosesAnOtherwiseOpenProviderAndFlushesAllCharacters() = runBlocking {
        var closed = false
        var continuedAfterDone = false
        var visible = ""
        val outcome = withTimeout(1_000) {
            flow<ApiState> {
                try {
                    emit(ApiState.Success("Answer "))
                    emit(ApiState.Success("()[] / 🌸"))
                    emit(ApiState.Done)
                    continuedAfterDone = true
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }.collectApiStateUpdates(
                onUpdate = { text, _, _ -> visible = text },
                nanoTimeProvider = { 1L },
                publishIntervalMillis = 60_000L
            )
        }
        assertEquals(ApiStateFlowOutcome.Completed, outcome)
        assertEquals("Answer ()[] / 🌸", visible)
        assertTrue(closed)
        assertFalse(continuedAfterDone)
    }

    @Test
    fun errorIsTerminalEvenWhenTheProviderNeverCloses() = runBlocking {
        var closed = false
        var visible = ""
        val outcome = withTimeout(1_000) {
            flow<ApiState> {
                try {
                    emit(ApiState.Success("Partial answer"))
                    emit(ApiState.Error("Gateway disconnected"))
                    awaitCancellation()
                } finally {
                    closed = true
                }
            }.collectApiStateUpdates(onUpdate = { text, _, _ -> visible = text })
        }
        assertEquals(ApiStateFlowOutcome.Failed("Gateway disconnected"), outcome)
        assertEquals("Partial answer", visible)
        assertTrue(closed)
    }

    @Test
    fun lateFailureCannotOverwriteAnAlreadyCompletedAnswer() = runBlocking {
        val outcome = flowOf(
            ApiState.Success("Complete answer"),
            ApiState.Done,
            ApiState.Error("Late socket-close error")
        ).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        assertEquals(ApiStateFlowOutcome.Completed, outcome)
    }

    @Test
    fun lateDoneCannotTurnAnErrorIntoSuccess() = runBlocking {
        val outcome = flowOf(
            ApiState.Error("First terminal error"),
            ApiState.Success("Too late"),
            ApiState.Done
        ).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        assertEquals(ApiStateFlowOutcome.Failed("First terminal error"), outcome)
    }

    @Test
    fun reasoningOnlyCompletionIsNotInventedAsAVisibleAnswer() = runBlocking {
        var visible = "not updated"
        var thoughts = ""
        val outcome = flowOf(ApiState.Thinking("Reasoning only"), ApiState.Done)
            .collectApiStateUpdates(
                onUpdate = { text, thinking, _ ->
                    visible = text
                    thoughts = thinking
                }
            )
        assertTrue(outcome is ApiStateFlowOutcome.Failed)
        assertEquals("", visible)
        assertEquals("Reasoning only", thoughts)
    }
}
