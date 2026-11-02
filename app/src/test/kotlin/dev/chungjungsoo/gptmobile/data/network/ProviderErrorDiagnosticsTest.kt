package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorDiagnosticsTest {
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
