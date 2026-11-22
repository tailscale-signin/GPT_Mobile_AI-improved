package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentRunnerTerminalPublicationTest {
    @Test fun closedContinuationPreservesThePublishedAnswerAndOriginalFailureWithoutFallback() = runBlocking {
        assertAlreadyPublished(closedContinuation())
    }

    @Test fun reviewRefusalPreservesThePublishedAnswerAndOriginalFailureWithoutFallback() = runBlocking {
        assertAlreadyPublished(reviewRefusal())
    }

    @Test fun closedContinuationWithoutAnApplicationReportStillPublishesItsRetainedFallback() = runBlocking {
        for (previous in unpublishedControls()) assertFallback(closedContinuation(), previous)
    }

    @Test fun reviewRefusalWithoutAnApplicationReportStillPublishesItsRetainedFallback() = runBlocking {
        for (previous in unpublishedControls()) assertFallback(reviewRefusal(), previous)
    }

    @Test fun alreadyPublishedFailureDoesNotFinishTheSavedReceiptCheckpoint() = runBlocking {
        for (failure in listOf(closedContinuation(), reviewRefusal())) {
            val call = ProviderEvent.ToolCall("saved-read", "read_fixture", JsonObject(emptyMap()))
            val checkpoint = ToolBatchCheckpoint(null, "published-${failure.errorCode}")
            checkpoint.enqueue(listOf(call))
            checkpoint.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("Retained source evidence"), false))
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition(call.name, "Saved read", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("The saved read must not be repeated")
            }
            val session = PublicationSession(failure, applicationReport(ANSWER), fallbackCompletes = true)

            val events = AgentRunner(AgentRunLimits(githubTask = true), checkpoint = checkpoint).run(session, listOf(tool)).toList()

            assertEquals(0, session.fallbackCalls)
            assertEquals(listOf(call), checkpoint.pending())
            assertEquals(emptySet<String>(), checkpoint.recoverySnapshot().consumedCallIds)
            assertEquals(listOf(failure), events.providerEvents().filterIsInstance<ProviderEvent.Failed>())
            assertFalse(events.providerEvents().any { it == ProviderEvent.Completed })
            assertFalse(events.any { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished })
        }
    }

    private suspend fun assertAlreadyPublished(failure: ProviderEvent.Failed) {
        val session = PublicationSession(failure, applicationReport(ANSWER))
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, emptyList()).toList().providerEvents()

        assertEquals(1, session.rounds)
        assertEquals(0, session.fallbackCalls)
        assertEquals(listOf(ANSWER), events.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        assertEquals(listOf(failure), events.filterIsInstance<ProviderEvent.Failed>())
        assertEquals(failure, events.last())
        assertFalse(events.any { it == ProviderEvent.Completed })
    }

    private suspend fun assertFallback(failure: ProviderEvent.Failed, previous: ProviderEvent.TextDelta?) {
        val session = PublicationSession(failure, previous)
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, emptyList()).toList().providerEvents()

        assertEquals(1, session.rounds)
        assertEquals(1, session.fallbackCalls)
        assertEquals(listOf(FALLBACK), events.filterIsInstance<ProviderEvent.TextDelta>().filter { it.lineage.origin == OutputOrigin.APPLICATION_REPORT }.map { it.text }.filter(String::isNotBlank))
        if (previous != null) assertEquals(previous, events.filterIsInstance<ProviderEvent.TextDelta>().first())
        assertEquals(listOf(failure), events.filterIsInstance<ProviderEvent.Failed>())
        assertEquals(failure, events.last())
    }

    private class PublicationSession(
        private val failure: ProviderEvent.Failed,
        private val previous: ProviderEvent.TextDelta?,
        private val fallbackCompletes: Boolean = false
    ) : AgentProviderSession {
        var rounds = 0
        var fallbackCalls = 0

        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
            rounds++
            previous?.let { emit(it) }
            emit(failure)
        }

        override fun retainedFailureResponse(failure: ProviderEvent.Failed) = flow {
            fallbackCalls++
            assertEquals(this@PublicationSession.failure, failure)
            emit(ProviderEvent.TextDelta(FALLBACK, OutputLineage(OutputOrigin.APPLICATION_REPORT)))
            emit(if (fallbackCompletes) ProviderEvent.Completed else failure)
        }
    }

    private fun applicationReport(text: String) = ProviderEvent.TextDelta(text, OutputLineage(OutputOrigin.APPLICATION_REPORT))

    private fun unpublishedControls() = listOf(null, applicationReport(" \n\t"), ProviderEvent.TextDelta("An unverified model draft does not replace the retained report."))

    private fun closedContinuation() = ProviderEvent.Failed(
        "The published observations leave requirements unfinished.",
        errorCode = "GITHUB_TASK_INCOMPLETE",
        transportComplete = true,
        taskOutcome = "PARTIAL",
        invocationId = "verification-invocation",
        failureDetails = buildJsonObject {
            put("failed_stage", "acceptance_verification")
            put("logical_continuation_allowed", false)
            put("request_replay_allowed", false)
        }
    )

    private fun reviewRefusal() = ProviderEvent.Failed(
        "The answer review could not finish.",
        errorCode = "GITHUB_REVIEW_CAPACITY",
        transportComplete = true,
        taskOutcome = "PARTIAL",
        invocationId = "review-invocation",
        failureDetails = buildJsonObject {
            put("review_stage", "answer_review")
            put("request_replay_allowed", false)
        }
    )

    private fun List<AgentRunEvent>.providerEvents() = filterIsInstance<AgentRunEvent.Provider>().map { it.event }

    private companion object {
        const val ANSWER = "The inspected source findings are already available in this answer."
        const val FALLBACK = "Retained findings are available; the task remains incomplete."
    }
}
