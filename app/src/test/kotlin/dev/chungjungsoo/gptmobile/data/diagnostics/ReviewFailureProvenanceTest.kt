package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFailureProvenanceTest {
    private val profile = PlatformV2(uid = "selected", name = "Selected", model = "model")

    @Test fun noUsageReviewerAttemptKeepsKnownFinishAndBytesInThePersistedFailureReceipt() {
        val recovery = Json.parseToJsonElement("""{"schema_version":1,"attempt_receipts":[{"attempt":1,"output_allowance":1536,"finish_reason":"LENGTH","visible_output_bytes":0,"reasoning_output_bytes":11,"terminal_error_code":"completion_repair_budget_exhausted","terminal_status_code":200,"backend_response_observed":true,"status":"COMPLETED"}]}""")
        val failed = failure().let { it.copy(failureDetails = JsonObject(it.failureDetails.orEmpty() + ("gateway_recovery" to recovery))) }
        val receipt = ProviderFailureReceipt.from(profile, "github-delivery", failed, ProviderEvent.Usage(outputTokens = 1133, finishReason = "stop", invocationId = "primary"))
        assertNull(receipt.outputTokens)
        assertNull(receipt.reasoningTokens)
        assertNull(receipt.usageInvocationId)
        assertEquals(ProviderFinishCategory.LENGTH, receipt.finishReason)
        assertEquals(ProviderFailureStage.ANSWER_REVIEW, receipt.stage)
        val row = (requireNotNull(receipt.gatewayAttemptEvidence).getValue("attempt_receipts") as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive(11), row["reasoning_output_bytes"])
        assertEquals(JsonPrimitive(1536), row["output_allowance"])
        assertTrue(receipt.valid())
        assertEquals(receipt, Json.decodeFromString(ProviderFailureReceipt.serializer(), Json.encodeToString(ProviderFailureReceipt.serializer(), receipt)))
    }
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
