package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorDiagnosticsTest {
    @Test fun templateSourceIsNotRetainedAndItsTypedFailureSurvivesLegacyHttp500() {
        val body = """{"error":{"message":"Jinja Exception: System message must be at the beginning. PRIVATE TEMPLATE SOURCE","backend_status":500,"dispatch_state":"backend_rejected","origin":"backend"}}"""
        val details = requireNotNull(structuredProviderFailure(body, 500))
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("BACKEND_TEMPLATE_INVALID"), details["code"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(false), details["retryable"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(500), details["http_status"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive("backend_template"), details["failed_stage"])
        assertFalse(providerErrorDetails(body, "HTTP 500").contains("PRIVATE TEMPLATE SOURCE"))
        assertFalse(details.toString().contains("PRIVATE TEMPLATE SOURCE"))
    }

    @Test fun failedRepairPreservesOnlyExactBoundedAttemptUsage() {
        val body = """{"error":{"code":"completion_repair_budget_exhausted","terminal_status_code":502,"gateway_recovery":{"attempts":2,"output_allowance":512,"usage_origin":"PROVIDER_REPORTED","usage_estimated":false,"attempt_usage":[{"prompt_tokens":12,"completion_tokens":0,"total_tokens":12,"raw":"private source"},{"completion_tokens":-1,"total_tokens":"23"}],"attempt_backend_tokens":[{"prompt_n":12,"predicted_n":0}],"request":"private prompt"}}}"""
        val details = requireNotNull(structuredProviderFailure(body))
        val recovery = details["gateway_recovery"] as kotlinx.serialization.json.JsonObject
        val usage = recovery["attempt_usage"] as kotlinx.serialization.json.JsonArray
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(0), (usage[0] as kotlinx.serialization.json.JsonObject)["completion_tokens"])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonObject(emptyMap()), usage[1])
        org.junit.Assert.assertEquals(kotlinx.serialization.json.JsonPrimitive(502), details["terminal_status_code"])
        assertFalse(recovery.containsKey("total_tokens"))
        assertFalse(details.toString().contains("private"))
        assertFalse(requireNotNull(structuredProviderFailure(body.replace("\"usage_estimated\":false", "\"usage_estimated\":true"))).containsKey("gateway_recovery"))
    }

    @Test fun gatewayFailureRetainsOriginalCodeStageAndIdentifiersOnly() {
        val body = """{"error":{"code":"client_tool_results_required","failed_stage":"tool_continuation","job_id":"job-1","request_id":"request-1","dispatch_state":"not_dispatched","retryable":false,"request":"private prompt"}}"""
        val details = requireNotNull(structuredProviderFailure(body))
        assertTrue(details.toString().contains("tool_continuation"))
        assertTrue(details.toString().contains("job-1"))
        assertFalse(details.containsKey("request"))
        assertTrue(providerErrorDetails(body, "HTTP 409").contains("retryable=false"))
    }

    @Test fun `OpenRouter metadata explains generic errors without exposing secrets or request echoes`() {
        val result = providerErrorDetails(
            """{"id":"gen-123","error":{"code":400,"message":"Provider returned error","metadata":{"provider_name":"Example","raw":"{\"error\":{\"message\":\"Invalid tool schema. Credential custom-secret\"},\"request\":\"PRIVATE PROMPT\"}","authorization":"OTHER SECRET"}}}""",
            "HTTP 400",
            "custom-secret"
        )
        assertTrue(result.contains("Invalid tool schema"))
        assertTrue(result.contains("provider=Example"))
        assertTrue(result.contains("generation=gen-123"))
        assertFalse(result.contains("custom-secret"))
        assertFalse(result.contains("OTHER SECRET"))
        assertFalse(result.contains("PRIVATE PROMPT"))
    }

    @Test fun `non JSON errors do not dump arbitrary response bodies`() {
        assertTrue(providerErrorDetails("<html>private content</html>", "HTTP 502") == "HTTP 502")
    }
}
