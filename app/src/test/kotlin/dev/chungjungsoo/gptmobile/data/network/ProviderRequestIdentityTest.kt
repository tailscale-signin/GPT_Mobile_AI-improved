package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRequestIdentityTest {
    @Test fun generationHeaderIsAnAcceptedFallback() {
        assertEquals("gen-123", providerRequestIdentity { if (it == "X-Generation-ID") "gen-123" else null })
    }

    @Test fun malformedHeaderIsNeverInsertedIntoTheDiagnosticLog() {
        assertEquals("unavailable", providerRequestIdentity { "secret\nI/Test: Forged" })
        assertEquals("unavailable", providerRequestIdentity { null })
        assertEquals("req-1", providerRequestIdentity { if (it == "X-Request-ID") "req-1" else "gen-2" })
    }
}
