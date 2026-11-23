package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiDeliveryTest {
    @Test fun streamWithoutTerminalRetainsDeliveryStage() = runTest {
        val states = flowOf<ApiState>(ApiState.Success("partial")).withGitHubApiDelivery(true).toList()
        val failure = states.filterIsInstance<ApiState.Error>().single()
        assertEquals("GITHUB_RUN_INTERRUPTED", failure.errorCode)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("delivery"), failure.failureDetails?.get("failed_stage"))
        assertEquals(ApiState.Done, states.last())
    }

    @Test fun repositoryPrecheckKeepsRepairAndDoesNotInventAnUncertainMutation() = runTest {
        val failure = ApiState.Error(
            "Repository unavailable or inaccessible. Refresh access or change repository.",
            errorCode = "GITHUB_REPOSITORY_UNAVAILABLE",
            failureDetails = buildJsonObject {
                put("dispatched", false)
                put("remote_acceptance", "NO")
                put("failed_stage", "repository_admission")
            }
        )
        val states = flowOf<ApiState>(failure, ApiState.Done).withGitHubApiDelivery(true).toList()
        val text = states.filterIsInstance<ApiState.Success>().single().textChunk
        assertTrue(text.contains("Refresh access"))
        assertTrue(text.contains("blocked before execution"))
        assertFalse(text.contains("uncertain"))
        assertEquals(failure, states.filterIsInstance<ApiState.Error>().single())
        assertEquals(dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.SYSTEM_ERROR, states.filterIsInstance<ApiState.Success>().single().lineage.origin)
    }

    @Test fun preparationFailureRetainsItsMetadataAndHasOneVisibleResponse() = runTest {
        val failure = ApiState.Error("Authorization changed", errorCode = "DISPATCH_POLICY_CHANGED", taskOutcome = "CONFIGURATION_INVALIDATED")
        val states = flowOf<ApiState>(ApiState.Loading, failure, failure, ApiState.Done, ApiState.Done).withGitHubApiDelivery(true).toList()
        assertEquals(listOf(failure), states.filterIsInstance<ApiState.Error>())
        assertTrue(states.filterIsInstance<ApiState.Success>().single().textChunk.isNotBlank())
        assertEquals(1, states.count { it == ApiState.Done })
    }

    @Test fun refusedRecoveryAfter103SettledCallsDoesNotClaimTheWholeTaskWasUnexecuted() = runTest {
        val recovery = dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure(
            "PROVIDER_WAIT_CHECKPOINT_CHANGED",
            original = buildJsonObject { put("code", "429") },
            correlation = buildJsonObject {
                put("settled_tool_count", 103)
                put("checkpoint_validation_stage", "TOOL_JOURNAL")
                put("checkpoint_mismatch_reason", "TOOL_RECEIPTS_CHANGED")
            }
        ).event()
        val failure = ApiState.Error(recovery.message, errorCode = recovery.errorCode, taskOutcome = recovery.taskOutcome, failureDetails = recovery.failureDetails)
        val states = flowOf<ApiState>(failure, ApiState.Done).withGitHubApiDelivery(true).toList()
        val text = states.filterIsInstance<ApiState.Success>().single().textChunk
        assertTrue(text.contains("recovery attempt was blocked before dispatch"))
        assertTrue(text.contains("earlier tool results remain retained"))
        assertFalse(text.contains("This request was blocked before execution"))
        assertEquals(failure, states.filterIsInstance<ApiState.Error>().single())
        assertEquals(kotlinx.serialization.json.JsonPrimitive(103), failure.failureDetails?.get("settled_tool_count"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("RECOVERY_ATTEMPT"), failure.failureDetails?.get("dispatch_evidence_scope"))
        assertEquals(buildJsonObject { put("code", "429") }, failure.failureDetails?.get("original_interruption"))
        assertEquals(kotlinx.serialization.json.JsonPrimitive("RECOVERY_ATTEMPT"), dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure.retainedDetails(failure.failureDetails)?.get("dispatch_evidence_scope"))
    }

    @Test fun existingRetainedAnswerDoesNotAcquireAnotherFailureParagraph() = runTest {
        val answer = ApiState.Success("Retained findings; further verification is blocked.")
        val failure = ApiState.Error("Endpoint unavailable")
        val states = flowOf<ApiState>(answer, failure, ApiState.Done).withGitHubApiDelivery(true).toList()
        assertEquals(listOf(answer, failure, ApiState.Done), states)
    }

    @Test fun emptySuccessCannotBeReportedAsACompleteRequest() = runTest {
        val states = flowOf<ApiState>(ApiState.Done).withGitHubApiDelivery(true).toList()
        assertTrue(states.first() is ApiState.Success)
        assertEquals("FINAL_RESPONSE_REQUIRED", states.filterIsInstance<ApiState.Error>().single().errorCode)
        assertEquals(ApiState.Done, states.last())
    }
}
