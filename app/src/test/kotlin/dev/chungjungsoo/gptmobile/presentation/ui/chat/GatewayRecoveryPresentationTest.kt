package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRecoveryPresentationTest {
    private val request = GatewayEngineeringRequest("owner/repo", "work", "main", "Fix the test", "chosen-model", chatId = 3, requestId = "original-request")
    private val binding = GatewayEngineeringBinding("original-profile", "https://gateway.example", request)

    @Test fun missingBindingAndUnobservedHardwareRemainExplicit() {
        val saved = gatewayRecoveryPresentation(binding)
        assertEquals("original-profile", saved.profileUid)
        assertEquals("chosen-model", saved.requestedModel)
        assertEquals("Not measured", saved.observedModel)
        assertEquals("Not measured", saved.hardware)
        assertFalse(saved.canReconcileRequest)
        assertFalse(saved.canCancel)
    }

    @Test fun uncertainWriteHasInspectionGuidanceAndNoGenericRetry() {
        val job = GatewayEngineeringJob("saved", "owner/repo", "work", state = "NEEDS_INPUT", revision = 7, createdAt = 1.0, deadline = 500.0, message = "Write result unknown", blockedReason = buildJsonObject { put("code", "UNKNOWN_WRITE_RESULT") })
        val saved = gatewayRecoveryPresentation(binding.copy(job = job))
        assertEquals("UNKNOWN_WRITE_RESULT", saved.reason)
        assertTrue(saved.nextAction.contains("reconcile the remote result"))
        assertFalse(saved.canReconcileRequest)
        assertTrue(saved.canCancel)
    }

    @Test fun retainedCompletedJobUsesObservedIdentitySeparatelyFromRequestedModel() {
        val job = GatewayEngineeringJob(
            "saved", "owner/repo", "work", state = "COMPLETED", revision = 8, createdAt = 1.0, deadline = 500.0, message = "Saved output",
            executionBinding = buildJsonObject {
                put("profile_uid", "frozen-profile")
                put("requested_model_id", "requested")
            },
            latestExecutionReceipt = buildJsonObject {
                put("observed_model_id", "observed")
                put("identity_status", "VERIFIED")
            }
        )
        val saved = gatewayRecoveryPresentation(binding.copy(job = job))
        assertEquals("frozen-profile", saved.profileUid)
        assertEquals("requested", saved.requestedModel)
        assertEquals("observed", saved.observedModel)
        assertEquals("VERIFIED", saved.executionIdentity)
        assertFalse(saved.canCancel)
        assertEquals("Not measured", saved.hardware)
    }
}
