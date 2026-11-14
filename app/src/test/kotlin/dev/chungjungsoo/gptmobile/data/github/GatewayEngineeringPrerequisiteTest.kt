package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentFailureEvidence
import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.diagnostics.FailureDomain
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureReceipt
import dev.chungjungsoo.gptmobile.data.diagnostics.ProviderFailureStage
import dev.chungjungsoo.gptmobile.data.model.ClientType
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayEngineeringPrerequisiteTest {
    private val profile = PlatformV2(uid = "selected", name = "Fixture", compatibleType = ClientType.LLAMA, model = "exact-model")

    @Test fun validationPolicyRejectionRetainsTypedSetupAndFailureReceipt() {
        val rejected = GatewayEngineeringPrerequisites.rejected(
            412,
            GatewayEngineeringPreflight(
                blockers = listOf(GatewayEngineeringBlocker("validation_policy", "VALIDATION_POLICY_REQUIRED", "admission", "private remote path and command", "UNTRUSTED_REMOTE_ACTION")),
                validationPolicyState = "NOT_CONFIGURED"
            )
        ) as GatewayEngineeringPrerequisiteRejected
        val event = requireNotNull(AgentFailureEvidence.exception(rejected))
        val failure = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
        assertEquals("VALIDATION_POLICY_REQUIRED", failure.category)
        assertEquals("admission", failure.phase)
        assertEquals(listOf("CONFIGURE_VALIDATION_POLICY"), failure.recoveryActions)
        assertTrue(failure.isEngineeringSetup)
        assertTrue(failure.isUnsentSetup)
        assertFalse(failure.retryAllowed)
        assertEquals(false, failure.requestReplayAllowed)
        assertEquals("BLOCKED", event.taskOutcome)
        assertTrue(event.transportComplete)
        assertFalse(event.toString().contains("private remote"))
        assertFalse(event.toString().contains("UNTRUSTED_REMOTE_ACTION"))
        assertEquals(JsonPrimitive("NOT_CONFIGURED"), event.failureDetails?.get("validation_policy_state"))
        val receipt = ProviderFailureReceipt.from(profile, "github-delivery", event)
        assertEquals("VALIDATION_POLICY_REQUIRED", receipt.errorCode)
        assertEquals(ProviderFailureStage.ADMISSION, receipt.stage)
        assertEquals(FailureDomain.APPLICATION_VERIFICATION, receipt.failureDomain)
        assertEquals("CONFIGURE_VALIDATION_POLICY", receipt.recoveryAction)
        assertEquals(false, receipt.dispatched)
        assertTrue(receipt.valid())
        assertFalse(receipt.copy(recoveryAction = "RESUME_UNFINISHED_WORK").valid())
    }

    @Test fun partialOutputDoesNotBecomeAnUnsentSetupClaim() {
        val rejected = GatewayEngineeringPrerequisiteRejected(412, "VALIDATION_POLICY_REQUIRED")
        val event = requireNotNull(AgentFailureEvidence.exception(rejected, partialOutput = true))
        val failure = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
        assertNull(failure.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
        assertFalse(failure.isUnsentSetup)
        assertEquals(false, failure.requestReplayAllowed)
    }

    @Test fun unknownCommandOutcomeCannotBecomeASetupRetry() {
        val rejected = GatewayEngineeringPrerequisiteRejected(412, "VALIDATION_POLICY_REQUIRED")
            .withCommandOutcomeUnknown(true).withCommandOutcomeUnknown(false)
        assertTrue(rejected.commandOutcomeUnknown)
        val event = requireNotNull(AgentFailureEvidence.exception(rejected))
        val failure = ProfileFailureRecord.fromProvider(event.message, event.errorCode, event.failureDetails)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.category)
        assertEquals("gateway_dispatch", failure.phase)
        assertEquals(listOf("RECONCILE_REMOTE_OUTCOME"), failure.recoveryActions)
        assertFalse(failure.retryAllowed)
        assertFalse(failure.isUnsentSetup)
        assertFalse(event.transportComplete)
        assertEquals(false, failure.requestReplayAllowed)
        val receipt = ProviderFailureReceipt.from(profile, "github-delivery", event)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", receipt.errorCode)
        assertNull(receipt.recoveryAction)
        assertEquals(false, receipt.logicalContinuationAllowed)
        assertTrue(receipt.valid())
    }

    @Test fun unrecognizedPreflightDoesNotCopyRemoteProseOrInventARepair() {
        val rejected = GatewayEngineeringPrerequisites.rejected(
            412,
            GatewayEngineeringPreflight(blockers = listOf(GatewayEngineeringBlocker("private", "UNRECOGNIZED_POLICY", "private-stage", "private server message", "RUN_PRIVATE_COMMAND")))
        )
        assertEquals("REQUEST_REJECTED", rejected.errorCode)
        assertFalse(rejected.message.orEmpty().contains("private"))
        val event = requireNotNull(AgentFailureEvidence.exception(rejected))
        assertNull(event.failureDetails?.get("recovery_action"))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("request_replay_allowed"))
    }

    @Test fun prerequisiteFieldsAreBoundedAndRecoveryMapsToItsOwnCode() {
        for (code in GatewayEngineeringPrerequisites.codes) {
            val rejected = GatewayEngineeringPrerequisiteRejected(412, code, "private-stage", "private-policy", Int.MAX_VALUE)
            assertEquals("admission", rejected.failedStage)
            assertNull(rejected.validationPolicyState)
            assertEquals(10_000, rejected.blockerCount)
            assertEquals(GatewayEngineeringPrerequisites.recoveryAction(code), rejected.recoveryAction)
            assertFalse(rejected.message.orEmpty().contains("private"))
            val event = requireNotNull(AgentFailureEvidence.exception(rejected))
            val receipt = ProviderFailureReceipt.from(profile, "github-delivery", event)
            assertEquals(code, receipt.errorCode)
            assertEquals(rejected.recoveryAction, receipt.recoveryAction)
            assertTrue(receipt.valid())
        }
    }
}
