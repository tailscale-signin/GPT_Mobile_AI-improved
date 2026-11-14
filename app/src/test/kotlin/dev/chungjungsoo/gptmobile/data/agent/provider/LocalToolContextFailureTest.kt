package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.GitHubToolContextBudgetException
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LocalToolContextFailureTest {
    @Test fun initialGithubRejectionCarriesExplicitAdmissionAndDispatchFacts() {
        val failure = localToolContextFailure(GitHubToolContextBudgetException(5000, 1000), generationDispatched = false)
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", failure.errorCode)
        val details = requireNotNull(failure.failureDetails)
        assertEquals(JsonPrimitive("provider_admission"), details["failed_stage"])
        assertEquals(JsonPrimitive(false), details["dispatched"])
        assertEquals(JsonPrimitive("NO"), details["remote_acceptance"])
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), details["dispatch_state"])
        assertEquals(JsonPrimitive(false), details["retryable"])
        assertEquals(JsonPrimitive(5000), details["required_schema_tokens"])
        assertEquals(JsonPrimitive(1000), details["available_schema_tokens"])
        val diagnostic = requireNotNull(IssueReportContext.fromFields("CHAT_FAILURE", details, 1_000).diagnostics)
        assertEquals("PROVIDER", diagnostic.area)
        assertEquals(JsonPrimitive("GITHUB_TOOL_CONTEXT_BUDGET"), diagnostic.values["provider_failure_code"])
        assertEquals(JsonPrimitive(5000), diagnostic.values["required_schema_tokens"])
        assertEquals(JsonPrimitive(1000), diagnostic.values["available_schema_tokens"])
    }

    @Test fun laterGithubRejectionDoesNotClaimEarlierGenerationOrToolWorkWasUnsent() {
        val failure = localToolContextFailure(GitHubToolContextBudgetException(5000, 1000), generationDispatched = true)
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", failure.errorCode)
        val details = requireNotNull(failure.failureDetails)
        assertEquals(JsonPrimitive("provider_admission"), details["failed_stage"])
        assertNull(details["dispatched"])
        assertEquals(JsonPrimitive(true), details["generation_dispatched"])
        assertEquals(JsonPrimitive("UNKNOWN"), details["remote_acceptance"])
        assertEquals(JsonPrimitive("UNKNOWN"), details["dispatch_state"])
        assertFalse(failure.message.contains("was not dispatched"))
    }

    @Test fun arbitraryErrorTextDoesNotCreateTypedAdmissionEvidence() {
        val failure = localToolContextFailure(IllegalArgumentException("GITHUB_TOOL_CONTEXT_BUDGET: historic provider text"), generationDispatched = false)
        assertNull(failure.errorCode)
        assertNull(failure.failureDetails)
    }
}
