package dev.chungjungsoo.gptmobile.data.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderTransportWarmerTest {
    @Test fun warmupOnlyTouchesPublicHttpsOriginNeverSecretsOrDraftPath() {
        assertEquals("https://api.openai.com/", providerWarmOrigin("https://api.openai.com/v1/chat/completions?token=secret"))
        assertEquals("https://models.example.org:8443/", providerWarmOrigin("https://models.example.org:8443/v1/chat"))
        assertEquals("https://api.openai.com/", providerWarmOrigin("https://api.openai.com"))
    }

    @Test fun localUntrustedOrMalformedEndpointsAreNeverProbed() {
        listOf(
            "http://api.openai.com/v1",
            "http://localhost:8080/v1",
            "https://localhost/v1",
            "https://127.0.0.1/v1",
            "https://192.168.1.9/v1",
            "https://host.local/v1",
            "https://[::1]/v1",
            "https://user:secret@api.openai.com/v1",
            "file:///etc/passwd",
            "",
            "https://api.openai.com:0/"
        ).forEach { assertNull(it, providerWarmOrigin(it)) }
    }
}
