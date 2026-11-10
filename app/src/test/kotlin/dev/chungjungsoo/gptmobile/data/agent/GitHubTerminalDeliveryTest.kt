package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTerminalDeliveryTest {
    private fun provider(event: ProviderEvent) = AgentRunEvent.Provider(event)

    @Test fun interruptedTaskPublishesObservedFactsAndItsExactRecoveryStep() = runTest {
        val call = ProviderEvent.ToolCall(
            "pr-list",
            "github_pr",
            buildJsonObject {
                put("action", "list")
                put("state", "open")
            }
        )
        val result = AgentToolResult(
            call.callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("items", JsonArray(emptyList()))
                    put("has_more", false)
                    put("page", 1)
                }
            ),
            false
        )
        val failure = ProviderEvent.Failed(
            "Saved batch needs reconciliation",
            errorCode = "OUTPUT_CONTINUATION_PENDING_TOOLS",
            failureDetails = buildJsonObject {
                put("failed_stage", "tool_reconciliation")
                put("next_action", "RECONCILE_TOOL_RECEIPTS")
            }
        )
        val events = flowOf(AgentRunEvent.ToolFinished(call, result), provider(failure)).withGitHubTerminalDelivery(true).toList()
        val text = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("Open pull requests observed: 0 (complete captured list)"))
        assertTrue(text.contains("pr-list"))
        assertTrue(text.contains("tool_reconciliation"))
        assertTrue(text.contains("RECONCILE_TOOL_RECEIPTS"))
        assertTrue(text.contains("do not establish completion"))
    }

    @Test fun failureWithoutTextProducesOneVisibleIncompleteResponseAndPreservesCause() = runTest {
        val failure = ProviderEvent.Failed("Llama is offline", errorCode = "LOCAL_SERVICE_UNREACHABLE")
        val events = flowOf(provider(failure), provider(failure), provider(ProviderEvent.Completed))
            .withGitHubTerminalDelivery(true).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(1, events.filterIsInstance<ProviderEvent.TextDelta>().size)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("BLOCKED"))
        assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, events.filterIsInstance<ProviderEvent.TextDelta>().single().lineage.origin)
        assertEquals(listOf(failure), events.filterIsInstance<ProviderEvent.Failed>())
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    @Test fun knownRateLimitRejectionPreservesAnEmptyAnswerForDurableWaitAdmission() = runTest {
        val limit = dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision("OPENROUTER", "profile", "selected", 100_000, "scope", "PROVIDER_RATE_LIMITED", true, httpStatus = 429)
        val failure = ProviderEvent.Failed("HTTP 429", providerLimit = limit, invocationId = "actual")
        val events = flowOf(provider(failure)).withGitHubTerminalDelivery(true).toList()
        assertEquals(listOf(provider(failure)), events)
    }

    @Test fun rateLimitWithoutDurableWaitKeepsItsActionableCauseAndFailureMetadata() = runTest {
        val failures = listOf(
            ProviderEvent.Failed("HTTP 429: provider rate limit reached. Resume after the provider cooldown.", errorCode = "429", invocationId = "rate-limit"),
            ProviderEvent.Failed(
                "Request rejected",
                errorCode = "HTTP_ERROR",
                failureDetails = buildJsonObject {
                    put("failed_stage", "model_invocation")
                    put("http_status", 429)
                    put("retry_after_ms", 30_000)
                },
                invocationId = "typed-rate-limit"
            )
        )
        for (failure in failures) {
            val events = flowOf(provider(failure)).withGitHubTerminalDelivery(true).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            val text = events.filterIsInstance<ProviderEvent.TextDelta>().single()
            assertEquals("The AI provider reached its request limit. Wait for the cooldown, then resume.", text.text)
            assertEquals(failure.invocationId, text.lineage.invocationId)
            assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, text.lineage.origin)
            assertFalse(text.text.contains("BLOCKED"))
            assertFalse(text.text.contains("GITHUB_RUN_INTERRUPTED"))
            assertEquals(listOf(failure), events.filterIsInstance<ProviderEvent.Failed>())
            assertFalse(events.any { it is ProviderEvent.Completed })
        }
    }

    @Test fun thrownProviderRateLimitRetainsItsStatusAndCooldown() = runTest {
        val events = flow<AgentRunEvent> { throw dev.chungjungsoo.gptmobile.data.network.ProviderHttpFailure(429, 30_000) }
            .withGitHubTerminalDelivery(true).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("429", failure.errorCode)
        assertEquals(kotlinx.serialization.json.JsonPrimitive(429), failure.failureDetails?.get("http_status"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive(30_000), failure.failureDetails?.get("retry_after_ms"))
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("request limit"))
    }

    @Test fun rateLimitNeverReplacesOrAppendsToAnExistingPartialAnswer() = runTest {
        val text = provider(ProviderEvent.TextDelta("The inspected source uses a shared queue."))
        val failure = ProviderEvent.Failed("HTTP 429: provider rate limit reached", errorCode = "429")
        val events = flowOf(text, provider(failure), provider(ProviderEvent.Completed)).withGitHubTerminalDelivery(true).toList()
        assertEquals(listOf(text, provider(failure)), events)
    }

    @Test fun rateLimitStatusCannotReplaceAnUncertainRemoteOutcome() = runTest {
        val failure = ProviderEvent.Failed(
            "Reconcile the dispatched operation",
            errorCode = "RECONCILIATION_REQUIRED",
            failureDetails = buildJsonObject { put("http_status", 429) }
        )
        val events = flowOf(provider(failure)).withGitHubTerminalDelivery(true).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("RECONCILIATION_REQUIRED"))
        assertFalse(text.contains("cooldown"))
        assertEquals(listOf(failure), events.filterIsInstance<ProviderEvent.Failed>())
    }

    @Test fun deniedToolsCannotBePresentedAsPartiallyCompletedActions() = runTest {
        val call = ProviderEvent.ToolCall("denied", "github_commit", buildJsonObject {})
        val denied = AgentToolResult(call.callId, ToolResultContent.Text("Permission denied"), true, dispatched = false)
        val events = flowOf(AgentRunEvent.ToolFinished(call, denied), provider(ProviderEvent.Failed("Stopped")))
            .withGitHubTerminalDelivery(true).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        val text = events.filterIsInstance<ProviderEvent.TextDelta>().single().text
        assertTrue(text.contains("BLOCKED"))
        assertTrue(text.contains("1 tool result(s) were observed"))
        assertFalse(text.contains("retained"))
    }

    @Test fun aDeliveredAnswerAndItsTerminalEventAreNeverRepeated() = runTest {
        val text = provider(ProviderEvent.TextDelta("Verified source findings"))
        val events = flowOf(text, provider(ProviderEvent.Completed), text, provider(ProviderEvent.Completed))
            .withGitHubTerminalDelivery(true).toList()
        assertEquals(listOf(text, provider(ProviderEvent.Completed)), events)
    }

    @Test fun emptyCompletionAndUnexpectedExceptionsCannotLeaveAnEmptyAnswer() = runTest {
        val sources = listOf(flowOf(provider(ProviderEvent.Completed)), flow<AgentRunEvent> { throw IllegalStateException("storage failed") })
        for (source in sources) {
            val events = source.withGitHubTerminalDelivery(true).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
            assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.isNotBlank())
            assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
            assertFalse(events.any { it is ProviderEvent.Completed })
        }
    }

    @Test fun unknownGatewayOutcomeSurvivesTerminalExceptionDelivery() = runTest {
        val events = flow<AgentRunEvent> { throw java.io.IOException("wrapped failure", java.io.IOException("GATEWAY_OUTCOME_UNKNOWN: connection reset")) }
            .withGitHubTerminalDelivery(true).toList().filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", events.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("GATEWAY_OUTCOME_UNKNOWN"))
        assertFalse(events.any { it is ProviderEvent.Completed })
    }

    @Test fun cancellationAndCollectorFailureRemainTransparent() = runTest {
        val cancellation = CancellationException("cancelled")
        try {
            flow<AgentRunEvent> { throw cancellation }.withGitHubTerminalDelivery(true).collect()
            error("Cancellation must escape")
        } catch (caught: CancellationException) {
            assertTrue(caught === cancellation)
        }
        val downstream = IllegalStateException("collector failed")
        try {
            flowOf(provider(ProviderEvent.TextDelta("text"))).withGitHubTerminalDelivery(true).collect { throw downstream }
            error("Collector failure must escape")
        } catch (caught: IllegalStateException) {
            assertTrue(caught === downstream)
        }
    }
}
