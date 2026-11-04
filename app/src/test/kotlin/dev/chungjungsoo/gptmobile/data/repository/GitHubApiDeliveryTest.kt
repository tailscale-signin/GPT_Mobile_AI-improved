package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.dto.ApiState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiDeliveryTest {
    @Test fun preparationFailureRetainsItsMetadataAndHasOneVisibleResponse() = runTest {
        val failure = ApiState.Error("Authorization changed", errorCode = "DISPATCH_POLICY_CHANGED", taskOutcome = "CONFIGURATION_INVALIDATED")
        val states = flowOf<ApiState>(ApiState.Loading, failure, failure, ApiState.Done, ApiState.Done).withGitHubApiDelivery(true).toList()
        assertEquals(listOf(failure), states.filterIsInstance<ApiState.Error>())
        assertTrue(states.filterIsInstance<ApiState.Success>().single().textChunk.isNotBlank())
        assertEquals(1, states.count { it == ApiState.Done })
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
