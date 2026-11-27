package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DispatchStageDiagnosticsTest {
    @Test fun transportAndInferenceObservationsAcceptOnlyTypedFlagsAndClosedScope() {
        for (field in listOf("http_request_dispatched", "upstream_inference_dispatched")) {
            for (flag in listOf(false, true)) assertEquals(JsonPrimitive(flag), DispatchStageDiagnostics.validValue(field, JsonPrimitive(flag)))
            for (invalid in listOf(JsonPrimitive("false"), JsonPrimitive(1), JsonNull)) assertNull(DispatchStageDiagnostics.validValue(field, invalid))
        }
        for (scope in listOf("HTTP_REQUEST", "UPSTREAM_INFERENCE")) assertEquals(JsonPrimitive(scope), DispatchStageDiagnostics.validValue("dispatch_scope", JsonPrimitive(scope)))
        assertNull(DispatchStageDiagnostics.validValue("dispatch_scope", JsonPrimitive("private endpoint")))
    }
}
