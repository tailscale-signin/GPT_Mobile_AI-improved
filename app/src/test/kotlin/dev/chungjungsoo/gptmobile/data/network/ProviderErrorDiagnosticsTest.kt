package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderErrorDiagnosticsTest {
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
