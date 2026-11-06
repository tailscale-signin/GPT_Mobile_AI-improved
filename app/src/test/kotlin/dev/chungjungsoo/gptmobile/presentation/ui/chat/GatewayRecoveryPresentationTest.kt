package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRequest
import kotlinx.serialization.json.JsonNull
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

    @Test fun emittedUnknownOperationOutcomeRequiresReconciliation() {
        val job = GatewayEngineeringJob("saved", "owner/repo", "work", state = "NEEDS_INPUT", revision = 7, createdAt = 1.0, deadline = 500.0, message = "Write result unknown", blockedReason = buildJsonObject { put("code", "EXECUTION_OPERATION_UNKNOWN_OUTCOME") })
        val saved = gatewayRecoveryPresentation(binding.copy(job = job))
        assertTrue(saved.nextAction.contains("reconcile the remote result"))
        assertFalse(saved.canReconcileRequest)
    }

    @Test fun actualGatewayHardwareReceiptDisplaysProviderReportedEvidence() {
        val receipt = buildJsonObject {
            put(
                "hardware",
                buildJsonObject {
                    put(
                        "accelerator",
                        buildJsonObject {
                            put("value", "CUDA")
                            put("origin", "PROVIDER_REPORTED")
                        }
                    )
                    put(
                        "offloaded_layers",
                        buildJsonObject {
                            put("value", 24)
                            put("origin", "PROVIDER_REPORTED")
                        }
                    )
                    put(
                        "memory",
                        buildJsonObject {
                            put("value", JsonNull)
                            put("origin", "PROVIDER_REPORTED")
                        }
                    )
                }
            )
        }
        val job = GatewayEngineeringJob("saved", "owner/repo", "work", state = "COMPLETED", revision = 8, createdAt = 1.0, deadline = 500.0, message = "Saved output", latestExecutionReceipt = receipt)
        val saved = gatewayRecoveryPresentation(binding.copy(job = job))
        assertEquals("Accelerator: CUDA (provider reported) · Offloaded layers: 24 (provider reported)", saved.hardware)
    }

    @Test fun missingHardwareEvidenceNeverUsesRequestedConfigurationOrStaleLegacyValues() {
        listOf(
            buildJsonObject { put("status", "NOT_MEASURED") },
            buildJsonObject {
                put(
                    "accelerator",
                    buildJsonObject {
                        put("value", "GPU")
                        put("origin", "REQUESTED")
                    }
                )
            },
            JsonNull
        ).forEach { hardware ->
            val job = GatewayEngineeringJob(
                "saved", "owner/repo", "work", state = "COMPLETED", revision = 8, createdAt = 1.0, deadline = 500.0, message = "Saved output",
                latestExecutionReceipt = buildJsonObject {
                    put("hardware", hardware)
                    put(
                        "observed_hardware",
                        buildJsonObject {
                            put(
                                "accelerator",
                                buildJsonObject {
                                    put("value", "old GPU")
                                    put("origin", "PROVIDER_REPORTED")
                                }
                            )
                        }
                    )
                }
            )
            assertEquals("Not measured", gatewayRecoveryPresentation(binding.copy(job = job)).hardware)
        }
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
