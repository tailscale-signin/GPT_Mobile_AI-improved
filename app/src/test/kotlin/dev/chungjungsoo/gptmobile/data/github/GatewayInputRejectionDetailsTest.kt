package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentFailureEvidence
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureReceipt
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureStage
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayInputRejectionDetailsTest {
    private val profile = PlatformV2(uid = "selected", name = "Selected", compatibleType = ClientType.LLAMA, model = "selected")
    private fun details(raw: String) = GatewayInputRejectionDetails.from(Json.parseToJsonElement(raw) as JsonObject)

    @Test fun batchGuardPreservesOnlyTheActualAllowedFieldsAndDoesNotInventMissingFields() {
        val version = details("""{"reasonCode":"ENGINEERING_BATCH_POLICY_INVALID","invalid_fields":["batch_policy_version","private-field"]}""")
        assertEquals(Json.parseToJsonElement("""["batch_policy_version"]"""), version["invalid_fields"])
        assertEquals(Json.parseToJsonElement("[]"), details("""{"reasonCode":"ENGINEERING_BATCH_POLICY_INVALID"}""")["invalid_fields"])
        assertFalse(version.toString().contains("private"))
    }

    @Test fun genericGuardKeepsItsTypedCodeWithoutInventingAValidationPhaseOrUnsentRequest() {
        val safe = details("""{"reasonCode":"future-reason","message":"private-field","failed_stage":"private-stage"}""")
        val failure = requireNotNull(AgentFailureEvidence.exception(GatewayEngineeringInputRejected(409, safe)))
        val classified = ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
        assertEquals("ENGINEERING_STATE_OR_INPUT_CHANGED", classified.category)
        assertEquals("engineering_guard", classified.phase)
        assertNull(classified.dispatched)
        assertFalse(classified.retryAllowed)
        val receipt = ProviderFailureReceipt.from(profile, "primary", failure)
        assertEquals(ProviderFailureStage.ENGINEERING_GUARD, receipt.stage)
        assertEquals("ENGINEERING_STATE_OR_INPUT_CHANGED", receipt.errorCode)
        assertTrue(receipt.valid())
        assertFalse(failure.failureDetails.toString().contains("private"))
    }

    @Test fun explicitAdmissionStageIsRetainedWhileEarlierAcceptanceRemainsUnknown() {
        val safe = details("""{"reasonCode":"ENGINEERING_BRANCH_INVALID","invalid_fields":["branch"],"failed_stage":"provider_admission"}""")
        val rejected = GatewayEngineeringInputRejected(409, safe)
        val failure = requireNotNull(AgentFailureEvidence.exception(rejected))
        assertEquals(ProviderFailureStage.ADMISSION, ProviderFailureReceipt.from(profile, "primary", failure).stage)
        assertNull(failure.failureDetails?.get("dispatched"))
        val unknown = requireNotNull(AgentFailureEvidence.exception(rejected.withCommandOutcomeUnknown(true).withCommandOutcomeUnknown(false)))
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", unknown.errorCode)
        assertEquals(JsonPrimitive(true), unknown.failureDetails?.get("requires_reconciliation"))
        assertEquals(JsonPrimitive(false), unknown.failureDetails?.get("request_replay_allowed"))
        assertNull(unknown.failureDetails?.get("dispatched"))
    }
}
