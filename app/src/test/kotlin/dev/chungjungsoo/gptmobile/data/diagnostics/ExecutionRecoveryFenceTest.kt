package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExecutionRecoveryFenceTest {
    @Test fun typedLocalRecoveryRetainsItsObservedOwnershipFence() {
        val details = buildJsonObject {
            put("failure_origin", "local_recovery")
            put("recovery_code", "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE")
            put("ownership_fence", 3)
        }
        assertEquals(JsonPrimitive(3), ExecutionCompletionDiagnostics.compact("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", details)["ownership_fence"])
        assertEquals(JsonPrimitive(3), ExecutionCompletionDiagnostics.publicFields("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", details)["ownership_fence"])
    }

    @Test fun unrelatedOrMalformedFailuresCannotLendARecoveryOwnershipFence() {
        for ((code, origin) in listOf("CHAT_FAILED" to "local_recovery", "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE" to "PROVIDER")) {
            val details = buildJsonObject {
                put("failure_origin", origin)
                put("ownership_fence", 3)
            }
            assertFalse(ExecutionCompletionDiagnostics.publicFields(code, details).containsKey("ownership_fence"))
        }
        for (value in listOf(JsonPrimitive(-1), JsonPrimitive("3"), JsonPrimitive(3.5))) {
            val details = buildJsonObject {
                put("failure_origin", "local_recovery")
                put("ownership_fence", value)
            }
            assertFalse(ExecutionCompletionDiagnostics.publicFields("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", details).containsKey("ownership_fence"))
        }
    }
}
