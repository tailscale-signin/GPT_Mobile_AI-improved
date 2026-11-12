package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewFailureProvenanceTest {
    private val profile = PlatformV2(uid = "selected", name = "Selected", model = "model")
    private fun failure(usage: Boolean = false) = ProviderEvent.Failed(
        "Review repair exhausted",
        errorCode = "COMPLETION_REPAIR_BUDGET_EXHAUSTED",
        invocationId = "reviewer",
        failureDetails = buildJsonObject {
            put("failed_stage", "provider_generation")
            put("review_stage", "answer_review")
            put("server_request_id", "request-reviewer")
            put("dispatched", true)
            put("remote_acceptance", "YES")
            if (usage) put("gateway_recovery", Json.parseToJsonElement("""{"usage_origin":"PROVIDER_REPORTED","usage_estimated":false,"attempts":1,"attempt_usage":[{"prompt_tokens":17237,"completion_tokens":1536,"total_tokens":18773}]}"""))
        }
    )

    @Test fun deliveryFailureCannotBorrowThePrimaryDraftUsageOrFinishReason() {
        val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failure(), ProviderEvent.Usage(outputTokens = 1133, finishReason = "stop", invocationId = "primary"))
        assertEquals(ProviderFailureStage.ANSWER_REVIEW, receipt.stage)
        assertEquals("provider_generation", receipt.causeStage)
        assertEquals("reviewer", receipt.invocationId)
        assertEquals("request-reviewer", receipt.serverRequestId)
        assertNull(receipt.outputTokens)
        assertNull(receipt.usageInvocationId)
        assertEquals(ProviderFinishCategory.UNKNOWN, receipt.finishReason)
    }

    @Test fun matchingErrorUsageRemainsBoundToReviewerInTheOuterDeliveryReceipt() {
        val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failure(usage = true), ProviderEvent.Usage(outputTokens = 1133, finishReason = "stop", invocationId = "primary"))
        assertEquals(1536, receipt.outputTokens)
        assertEquals("gateway_reported", receipt.outputTokenSource)
        assertEquals("reviewer", receipt.usageInvocationId)
        assertEquals(ProviderFinishCategory.UNKNOWN, receipt.finishReason)
    }

    @Test fun unboundDeliveryUsageCannotSupplyTheMissingReviewerIdentity() {
        val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failure().copy(invocationId = null), ProviderEvent.Usage(outputTokens = 1133))
        assertNull(receipt.outputTokens)
    }
}
