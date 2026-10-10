package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderOutputLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderNestedLimitTest {
    @Test fun nestedSseParameterReachesAdaptiveCeilingWithoutRequestEcho() {
        val raw = """{"error":{"message":"Provider returned error","metadata":{"raw":"data:{\"error\":{\"message\":\"Param Incorrect\",\"param\":\"max_tokens is too large: 256000. This model supports at most 131072 completion tokens, whereas you provided 256000.\"},\"request\":\"PRIVATE PROMPT\"}"}}}"""
        val diagnostic = providerErrorDetails(raw, "HTTP 400")
        assertEquals(131072, ProviderOutputLimits.learn("nested-test", diagnostic, 256000))
        assertFalse(diagnostic.contains("PRIVATE PROMPT"))
    }

    @Test fun arbitraryParamAndContextErrorsDoNotBecomeOutputLimits() {
        val diagnostic = providerErrorDetails("""{"error":{"metadata":{"raw":"{\"error\":{\"message\":\"invalid\",\"param\":\"PRIVATE PROMPT\"}}"}}}""", "400")
        assertFalse(diagnostic.contains("PRIVATE PROMPT"))
        assertNull(ProviderOutputLimits.learn("context-test", "maximum context 4096 tokens", 8000))
    }
}
